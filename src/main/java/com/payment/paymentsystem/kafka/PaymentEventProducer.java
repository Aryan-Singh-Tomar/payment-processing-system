package com.payment.paymentsystem.kafka;

import com.payment.paymentsystem.event.PaymentRequestedEvent;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Wraps KafkaTemplate with payment-specific publishing logic.
 *
 * Why a separate component (not calling KafkaTemplate directly from the service):
 *   - Centralizes topic name + key strategy + logging in one place.
 *   - Lets the service stay free of Kafka-specific imports.
 *   - Easier to test (mock this, not the underlying template).
 *
 * Why the key is the payment ID:
 *   - All events for one payment land in the same partition.
 *   - Strict per-payment ordering on the consumer side.
 *   - If we later add events like payment.status.changed, they share
 *     the same partition as the original payment.requested event.
 */

@Component
public class PaymentEventProducer {
    private static final Logger log = LoggerFactory.getLogger(PaymentEventProducer.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String paymentRequestedTopic;

    public PaymentEventProducer(KafkaTemplate<String, Object> kafkaTemplate,
                                @Value("${app.kafka.topics.payment-requested}") String paymentRequestedTopic) {
        this.kafkaTemplate = kafkaTemplate;
        this.paymentRequestedTopic = paymentRequestedTopic;
    }

    public void publishPaymentRequested(PaymentRequestedEvent event){
        String key = event.paymentId().toString();

        log.info("Publishing PaymentRequestedEvent: paymentId={}, topic={}",
                event.paymentId(), paymentRequestedTopic);

        ProducerRecord<String, Object> record = new ProducerRecord<>(
                paymentRequestedTopic,
                event.paymentId().toString(),
                event
        );

        CompletableFuture<SendResult<String, Object>> future = kafkaTemplate.send(record);

        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to publish event for paymentId={}: {}",
                        event.paymentId(), ex.getMessage(), ex);
            } else {
                log.info("Published paymentId={} to partition={} offset={}",
                        event.paymentId(),
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            }
        });
    }
}
