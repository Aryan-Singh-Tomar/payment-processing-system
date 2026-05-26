package com.payment.paymentsystem.kafka;

import com.payment.paymentsystem.event.PaymentRequestedEvent;
import com.payment.paymentsystem.exception.PaymentNotFoundException;
import com.payment.paymentsystem.gateway.FakePaymentGatewayClient;
import com.payment.paymentsystem.gateway.GatewayChargeRequest;
import com.payment.paymentsystem.gateway.GatewayChargeResponse;
import com.payment.paymentsystem.service.PaymentDuplicateHandler;
import com.payment.paymentsystem.service.PaymentProcessingService;
import com.payment.paymentsystem.service.ProcessedEventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

/**
 * Subscribes to payment.requested. For each message:
 *   1. Mark the payment PROCESSING (short DB transaction).
 *   2. Call the gateway (slow, no DB connection held).
 *   3. Record the result (short DB transaction).
 *
 * If markProcessing returns false (payment isn't PENDING), we skip the
 * gateway call entirely — this is duplicate-event protection. Day 22
 * replaces this with stronger guarantees via a processed-events log.
 *
 * Exceptions propagate up; Spring Kafka will not commit the offset on
 * a throwing listener, so the message will be redelivered. Day 23 adds
 * proper retry handling with @RetryableTopic and a Dead Letter Topic.
 */
@Component
public class PaymentEventConsumer {
    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);
    private static final String EVENT_TYPE = "PaymentRequested";


    private final FakePaymentGatewayClient gateway;
    private final PaymentProcessingService processingService;
    private final PaymentDuplicateHandler duplicateHandler;
    private final ProcessedEventService processedEventService;

    public PaymentEventConsumer(FakePaymentGatewayClient gateway,
                                PaymentProcessingService processingService,
                                PaymentDuplicateHandler duplicateHandler,
                                ProcessedEventService processedEventService) {
        this.gateway = gateway;
        this.processingService = processingService;
        this.duplicateHandler = duplicateHandler;
        this.processedEventService = processedEventService;
    }


    @RetryableTopic(
            attempts = "3",
            backoff = @Backoff(delay = 1000, multiplier = 2.0),
            dltStrategy = DltStrategy.FAIL_ON_ERROR,
            exclude = { PaymentNotFoundException.class },
            autoCreateTopics = "True"
    )
    @KafkaListener(
            topics = "${app.kafka.topics.payment-requested}",
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void consume(
            @Payload PaymentRequestedEvent event,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset
            ) {
        log.info("Consumed event: paymentId={}, partition={}, offset={}",
                event.paymentId(), partition, offset);

        // Step 0 (NEW Day 22): Claim the event.
        // If this returns false, another thread/instance already processed
        // this event. We skip without doing any work.
        String eventKey = event.paymentId().toString();
        if (processedEventService.isProcessed(eventKey, EVENT_TYPE)) {
            log.info("Event for paymentId={} already processed — skipping", event.paymentId());
            return;
        }

        // Step 1: short transaction to mark PROCESSING.
        boolean shouldProcess = processingService.markProcessing(event.paymentId());
        if (!shouldProcess) {
            log.info("Skipping gateway call for paymentId={}", event.paymentId());
            processedEventService.markProcessed(eventKey, EVENT_TYPE);
            return;
        }

        // Step 2: call the gateway. No DB connection held during this call.
        GatewayChargeRequest gatewayChargeRequest = new GatewayChargeRequest(
                event.paymentId(),
                event.orderId(),
                event.amount(),
                event.currency()
        );

        GatewayChargeResponse response = gateway.charge(gatewayChargeRequest);

        // Step 3: short transaction to record the result.
        // If the gateway said SUCCESS but another payment for this order already
        // succeeded, the unique constraint blocks our UPDATE. We catch the
        // DataIntegrityViolationException and handle it in a SEPARATE transaction
        // by marking this payment as FAILED with reason DUPLICATE_ORDER_SUCCESS.
        try {
            processingService.recordResult(event.paymentId(), response);
        } catch (DataIntegrityViolationException ex) {
            if (response instanceof GatewayChargeResponse.Success success) {
                log.warn("Payment {} hit unique constraint — another payment for order {} " +
                                "already SUCCEEDED. Recovering in a fresh transaction.",
                        event.paymentId(), event.orderId());
                duplicateHandler.markAsDuplicateFailure(event.paymentId(), success);
            } else {
                throw ex;
            }
        }
        // Mark as processed AFTER successful completion.
        processedEventService.markProcessed(eventKey, EVENT_TYPE);

        log.info("Finished processing paymentId={}", event.paymentId());
    }

    /**
     * Invoked when a message exhausts all retries. In production this would
     * trigger an alert, write to a monitoring system, or notify on-call.
     * For now we log loudly with enough context to investigate.
     */
    public void handleDlt(@Payload PaymentRequestedEvent event,
                          @Header(KafkaHeaders.ORIGINAL_TOPIC) String originalTopic,
                          @Header(KafkaHeaders.EXCEPTION_MESSAGE) String exceptionMessage
                          ){
        log.error("DLT received event after exhausted retries: " +
                        "paymentId={}, originalTopic={}, exception={}",
                event.paymentId(), originalTopic, exceptionMessage);
    }



}

