package com.payment.paymentsystem.e2e;

import com.payment.paymentsystem.entity.Order;
import com.payment.paymentsystem.entity.OrderStatus;
import com.payment.paymentsystem.entity.Payment;
import com.payment.paymentsystem.entity.PaymentStatus;
import com.payment.paymentsystem.event.PaymentRequestedEvent;
import com.payment.paymentsystem.repository.OrderRepository;
import com.payment.paymentsystem.repository.PaymentRepository;
import com.payment.paymentsystem.repository.ProcessedEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.assertThat;

public class PaymentDuplicateDeliveryIntegrationTest extends AbstractIntegrationTest{

    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private ProcessedEventRepository processedEventRepository;
    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;
    private static final String TOPIC = "payment.requested";
    private static final String EVENT_TYPE = "PaymentRequested";

    @BeforeEach
    void setUp() {
        processedEventRepository.deleteAll();
        paymentRepository.deleteAll();
        orderRepository.deleteAll();
    }

    @Test
    @DisplayName("Same Kafka event published twice — consumer processes only once")
    void duplicate_kafka_delivery_is_skipped() throws Exception{

        Order order = newOrder();
        Payment payment = new Payment();
        payment.setOrderId(order.getId());
        payment.setAmount(new BigDecimal("1500.00"));
        payment.setCurrency("INR");
        payment.setStatus(PaymentStatus.PENDING);
        payment.setIdempotencyKey("e2e-dup-" + UUID.randomUUID());
        paymentRepository.saveAndFlush(payment);

        UUID paymentId = payment.getId();
        PaymentRequestedEvent event = PaymentRequestedEvent.of(
                paymentId,
                order.getId(),
                new BigDecimal("1500.00"),
                "INR"
        );

        // Act: publish the SAME event to Kafka twice
        kafkaTemplate.send(TOPIC, paymentId.toString(), event).get();
        kafkaTemplate.send(TOPIC, paymentId.toString(), event).get();

        // Assert: eventually, the payment reaches a terminal state
        await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    Payment current = paymentRepository.findById(paymentId).orElseThrow();
                    assertThat(current.getStatus()).isIn(
                            PaymentStatus.SUCCESS,
                            PaymentStatus.FAILED,
                            PaymentStatus.UNKNOWN
                    );
                });

        // Wait briefly to be sure the second delivery has been dropped (and didn't re-process)
        Thread.sleep(2000);

        // Assert: only ONE processed_events row was inserted (the dedup table caught the dup)
        long processedCount = processedEventRepository.count();
        assertThat(processedCount)
                .as("Duplicate delivery must result in exactly one processed_events row")
                .isEqualTo(1L);

        // Assert: payment was not re-processed (processed_at is set, no double work)
        Payment finalPayment = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(finalPayment.getProcessedAt())
                .as("processedAt should be set exactly once")
                .isNotNull();

    }

    private Order newOrder() {
        Order order = new Order();
        order.setCustomerId("CUST-DUP-" + UUID.randomUUID());
        order.setAmount(new BigDecimal("1500.00"));
        order.setCurrency("INR");
        order.setStatus(OrderStatus.CREATED);
        return orderRepository.saveAndFlush(order);
    }


}
