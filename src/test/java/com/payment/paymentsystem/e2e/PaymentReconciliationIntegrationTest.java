package com.payment.paymentsystem.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payment.paymentsystem.entity.Order;
import com.payment.paymentsystem.entity.OrderStatus;
import com.payment.paymentsystem.entity.Payment;
import com.payment.paymentsystem.entity.PaymentStatus;
import com.payment.paymentsystem.reconciliation.ReconciliationService;
import com.payment.paymentsystem.repository.OrderRepository;
import com.payment.paymentsystem.repository.PaymentRepository;
import com.payment.paymentsystem.repository.ProcessedEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@DisplayName("Reconciliation — Stuck Payments Are Recovered End-To-End")
public class PaymentReconciliationIntegrationTest extends AbstractIntegrationTest{
    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private ReconciliationService reconciliationService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final RestTemplate http = new RestTemplate();

    @BeforeEach
    void setUp() {
        processedEventRepository.deleteAll();
        paymentRepository.deleteAll();
        orderRepository.deleteAll();
    }

    @Test
    @DisplayName("Stuck PENDING payment is re-emitted by sweeper and reaches terminal state")
    void stuck_payment_is_recovered_by_reconciliation() throws Exception{
        // ── Arrange: create an order and a payment via the normal API path ──
        Order order = newOrder();

        String requestBody = """
                {
                    "orderId" : "%s",
                    "amount" : 1500,
                    "currency" : "INR",
                    "idempotencyKey" : "e2e-recon-%s"
                }
                """.formatted(order.getId(), UUID.randomUUID());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = http.postForEntity(
                baseUrl() + "/api/payments",
                new HttpEntity<>(requestBody, headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        JsonNode body = objectMapper.readTree(response.getBody());
        UUID paymentId = UUID.fromString(body.get("id").asText());

        // ── Wait for the first processing pass to complete ──
        await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    Payment p = paymentRepository.findById(paymentId).orElseThrow();
                    assertThat(p.getStatus()).isIn(
                            PaymentStatus.SUCCESS,
                            PaymentStatus.FAILED,
                            PaymentStatus.UNKNOWN
                    );
                });

        // ── Verify the dedup record exists (key state for the test) ──
        long dedupCountBefore = processedEventRepository.count();
        assertThat(dedupCountBefore)
                .as("Original processing should have inserted one processed_events row")
                .isEqualTo(1L);

        // ── Force the payment into a 'stuck' state ──
        // Reset it to PENDING with backdated created_at. The processed_events row
        // remains — this is exactly the scenario the Day 29 coordination fix handles.
        forceStuckState(paymentId);

        // ── Verify the stuck condition is real ──
        Payment stuck = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(stuck.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(stuck.getCreatedAt())
                .isBefore(OffsetDateTime.now().minusMinutes(5));

        // Dedup row is still present (this is what would normally block re-processing)
        assertThat(processedEventRepository.count()).isEqualTo(1L);

        // ── Act: trigger the reconciliation sweep directly ──
        reconciliationService.sweep();

        // ── Assert: the payment reaches a terminal state again ──
        // This is the critical assertion. Without the Day 29 unmark fix, the
        // re-emitted event would be silently dropped by the consumer's dedup check
        // and the payment would stay stuck in PENDING forever.
        await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    Payment p = paymentRepository.findById(paymentId).orElseThrow();
                    assertThat(p.getStatus())
                            .as("Reconciliation should have re-driven the payment to a terminal state")
                            .isIn(
                                    PaymentStatus.SUCCESS,
                                    PaymentStatus.FAILED,
                                    PaymentStatus.UNKNOWN
                            );
                    assertThat(p.getProcessedAt())
                            .as("processed_at should be set after reconciliation completes")
                            .isNotNull();
                });

        // ── Assert: a fresh dedup record exists for the reconciliation-driven processing ──
        // The original was deleted by unmark; the consumer inserted a new one when processing
        // the re-emitted event.
        assertThat(processedEventRepository.count())
                .as("Consumer should have inserted a fresh dedup row after re-processing")
                .isEqualTo(1L);

    }


    @Test
    @DisplayName("Sweep is a no-op when no payments are stuck")
    void sweep_is_noop_when_nothing_is_stuck() {
        // Arrange: a fresh order with no payments
        newOrder();

        // Act: trigger sweep
        reconciliationService.sweep();

        // Assert: no payments exist, no errors thrown
        assertThat(paymentRepository.count()).isZero();
    }


    @Test
    @DisplayName("Sweep ignores payments that are not yet old enough")
    void sweep_ignores_recently_created_pending_payments() throws Exception {
        // Arrange: create a payment but DON'T backdate it
        Order order = newOrder();

        String requestBody = """
                {
                  "orderId": "%s",
                  "amount": 1500.00,
                  "currency": "INR",
                  "idempotencyKey": "e2e-recon-fresh-%s"
                }
                """.formatted(order.getId(), UUID.randomUUID());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = http.postForEntity(
                baseUrl() + "/api/payments",
                new HttpEntity<>(requestBody, headers),
                String.class
        );

        JsonNode body = objectMapper.readTree(response.getBody());
        UUID paymentId = UUID.fromString(body.get("id").asText());

        // ── Wait for it to terminate normally ──
        await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    Payment p = paymentRepository.findById(paymentId).orElseThrow();
                    assertThat(p.getStatus()).isIn(
                            PaymentStatus.SUCCESS,
                            PaymentStatus.FAILED,
                            PaymentStatus.UNKNOWN
                    );
                });

        Payment terminalPayment = paymentRepository.findById(paymentId).orElseThrow();
        PaymentStatus statusBefore = terminalPayment.getStatus();
        OffsetDateTime processedAtBefore = terminalPayment.getProcessedAt();
        long dedupRowsBefore = processedEventRepository.count();

        // ── Act: trigger sweep ──
        // Payment is in a terminal state, and even if it weren't, it was just created
        // (well under the 5-minute threshold). Sweep should do nothing.
        reconciliationService.sweep();

        // Brief wait to confirm nothing async fires
        Thread.sleep(1000);

        // ── Assert: nothing changed ──
        Payment after = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(statusBefore);
        assertThat(after.getProcessedAt()).isEqualTo(processedAtBefore);
        assertThat(processedEventRepository.count()).isEqualTo(dedupRowsBefore);
    }


    // ── Helpers ──
    private Order newOrder(){
        Order order = new Order();
        order.setCustomerId("CUST-RECON-" + UUID.randomUUID());
        order.setAmount(new BigDecimal("1500.00"));
        order.setCurrency("INR");
        order.setStatus(OrderStatus.CREATED);
        return orderRepository.saveAndFlush(order);
    }

    /**
     * Reverts a payment to PENDING with a backdated created_at, simulating a
     * payment that has been stuck in a non-terminal state for longer than the
     * reconciliation threshold.
     *
     * IMPORTANT: This deliberately bypasses JPA via JdbcTemplate because the
     * Payment entity's createdAt field is correctly marked updatable=false to
     * preserve the audit-log invariant in production. Bypassing JPA here is
     * the right approach for test setup: it lets us simulate a stuck state
     * without compromising the production constraint that prevents application
     * code from ever modifying created_at.
     *
     * The processed_events row from the original processing is left intact to
     * exercise the Day 22 / Day 29 coordination fix.
     */

    @Transactional
    void forceStuckState(UUID paymentId) {
        int rowsUpdated = jdbcTemplate.update(
                """
                UPDATE payments
                SET status = 'PENDING',
                    processed_at = NULL,
                    gateway_payment_id = NULL,
                    failure_reason = NULL,
                    created_at = NOW() - INTERVAL '10 minutes'
                WHERE id = ?
                """,
                paymentId
        );

        if (rowsUpdated != 1) {
            throw new IllegalStateException(
                    "Expected to update exactly 1 payment row, but updated " + rowsUpdated);
        }
    }


}
