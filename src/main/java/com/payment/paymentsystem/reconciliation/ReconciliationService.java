package com.payment.paymentsystem.reconciliation;


import com.payment.paymentsystem.entity.Payment;
import com.payment.paymentsystem.entity.PaymentStatus;
import com.payment.paymentsystem.event.PaymentRequestedEvent;
import com.payment.paymentsystem.kafka.PaymentEventProducer;
import com.payment.paymentsystem.repository.PaymentRepository;
import com.payment.paymentsystem.service.ProcessedEventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

@Service
public class ReconciliationService {
    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
    private static final List<PaymentStatus> NON_TERMINAL_STATUSES =
            List.of(PaymentStatus.PENDING, PaymentStatus.PROCESSING);

    private final PaymentRepository paymentRepository;
    private final PaymentEventProducer paymentEventProducer;
    private final ReconciliationProperties properties;

    private final ProcessedEventService processedEventService;

    public ReconciliationService(PaymentRepository paymentRepository,
                                 PaymentEventProducer paymentEventProducer,
                                 ReconciliationProperties properties,
                                ProcessedEventService processedEventService) {
        this.paymentRepository = paymentRepository;
        this.paymentEventProducer = paymentEventProducer;
        this.properties = properties;
        this.processedEventService = processedEventService;
    }

    /**
     * Scheduled sweep. Runs every {@code scanIntervalMs} milliseconds.
     */
    @Scheduled(fixedDelayString = "${app.reconciliation.scan-interval-ms}")
    public void sweep(){
        if(!properties.enabled()){
            return;

        }

        OffsetDateTime threshold = OffsetDateTime.now().minusSeconds(properties.stuckThresholdSeconds());

        List<Payment> stuck = paymentRepository.findStuckPayments(
                NON_TERMINAL_STATUSES,
                threshold,
                PageRequest.of(0, properties.maxPerSweep())
        );

        if(stuck.isEmpty()){
            log.debug("Reconciliation sweep: no stuck payments");
            return;
        }

        log.info("Reconciliation sweep: found {} stuck payments (threshold={}s)",
                stuck.size(), properties.stuckThresholdSeconds());

        int remediated = 0;
        for(Payment payment : stuck){
            try{
                remediate(payment);
                remediated++;
            }catch (Exception ex){
                log.error("Failed to remediate stuck payment {}: {}",
                        payment.getId(), ex.getMessage(), ex);
            }
        }

        log.info("Reconciliation sweep complete: remediated {}/{} stuck payments",
                remediated, stuck.size());
    }

    /**
     * Re-emits the Kafka event for a single stuck payment.
     *
     * The consumer's processed_events dedup catches this as a duplicate if the
     * original event was processed; otherwise the consumer processes it fresh.
     */
    public void remediate(Payment payment){
        log.warn("Re-emitting event for stuck payment: paymentId={}, status={}, age={}s",
                payment.getId(),
                payment.getStatus(),
                java.time.Duration.between(payment.getCreatedAt(), OffsetDateTime.now())
                        .toSeconds()
        );

        // Clear prior processed-event markers so the consumer treats this as fresh.
        // The state machine in PaymentProcessingService is the final safety net
        // against terminal-state corruption.
        processedEventService.unmark(payment.getId().toString(), "PaymentRequested");

        PaymentRequestedEvent event = PaymentRequestedEvent.of(
                payment.getId(),
                payment.getOrderId(),
                payment.getAmount(),
                payment.getCurrency()
        );


        paymentEventProducer.publishPaymentRequested(event);
    }


    /**
     * Public method for the admin endpoint to query current stuck-payment count.
     */
    public int countStuckPayments() {
        OffsetDateTime threshold = OffsetDateTime.now()
                .minusSeconds(properties.stuckThresholdSeconds());
        return paymentRepository.findStuckPayments(
                NON_TERMINAL_STATUSES,
                threshold,
                PageRequest.of(0, properties.maxPerSweep())
        ).size();

    }

}
