package com.payment.paymentsystem.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payment.paymentsystem.entity.Order;
import com.payment.paymentsystem.entity.OrderStatus;
import com.payment.paymentsystem.entity.Payment;
import com.payment.paymentsystem.entity.PaymentStatus;
import com.payment.paymentsystem.repository.OrderRepository;
import com.payment.paymentsystem.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@DisplayName("Payment End-To-End — Happy Path")
public class PaymentHappyPathIntegrationTest extends AbstractIntegrationTest{

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private final RestTemplate http = new RestTemplate();

    @BeforeEach
    void setUp(){
        // Clean state between tests. Order matters: payments reference orders.
        paymentRepository.deleteAll();
        orderRepository.deleteAll();
    }

    @Test
    @DisplayName("POST /api/payments creates payment, consumer processes it to terminal state")
    void payment_reaches_terminal_state() throws Exception {
        // Arrange: an order in the DB ready to be paid
        Order order = new Order();
        order.setCustomerId("CUST-E2E-1");
        order.setAmount(new BigDecimal("1500.00"));
        order.setCurrency("INR");
        order.setStatus(OrderStatus.CREATED);
        order = orderRepository.save(order);
        UUID orderId = order.getId();

        String idempotencyKey = "e2e-happy-" + UUID.randomUUID();
        String requestBody = """
                {
                  "orderId": "%s",
                  "amount": 1500.00,
                  "currency": "INR",
                  "idempotencyKey": "%s"
                }
                """.formatted(orderId, idempotencyKey);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Act: send the POST
        ResponseEntity<String> response = http.postForEntity(
                baseUrl() + "/api/payments",
                new HttpEntity<>(requestBody, headers),
                String.class
        );


        // Assert: 202 and a payment ID
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        JsonNode body = objectMapper.readTree(response.getBody());
        UUID paymentId = UUID.fromString(body.get("id").asText());
        assertThat(body.get("status").asText()).isEqualTo("PENDING");

        // The Location header should point at the GET endpoint
        assertThat(response.getHeaders().getLocation()).isNotNull();
        assertThat(response.getHeaders().getLocation().toString())
                .contains("/api/payments/" + paymentId);

        // Wait for the consumer to process this payment to a terminal state.
        // Gateway returns SUCCESS 85% of the time, FAILED 10%, UNKNOWN 5%.
        // All three are valid terminal states for this test — we only care that
        // it left PENDING/PROCESSING.
        await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    Payment payment = paymentRepository.findById(paymentId).orElseThrow();
                    assertThat(payment.getStatus()).isIn(
                            PaymentStatus.SUCCESS,
                            PaymentStatus.FAILED,
                            PaymentStatus.UNKNOWN
                    );
                });

        // Final state assertions
        Payment finalPayment = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(finalPayment.getProcessedAt())
                .as("processedAt should be set when consumer finishes")
                .isNotNull();

        // The terminal-state guarantees from your state machine:
        Set<PaymentStatus> terminalStates = Set.of(
                PaymentStatus.SUCCESS,
                PaymentStatus.FAILED,
                PaymentStatus.UNKNOWN
        );
        assertThat(finalPayment.getStatus()).isIn(terminalStates);

        // If SUCCESS, gateway_payment_id should be populated
        if (finalPayment.getStatus() == PaymentStatus.SUCCESS) {
            assertThat(finalPayment.getGatewayPaymentId()).isNotNull();
            assertThat(finalPayment.getGatewayPaymentId()).startsWith("gw_");
        }

        // If FAILED, failure_reason should be populated
        if (finalPayment.getStatus() == PaymentStatus.FAILED) {
            assertThat(finalPayment.getFailureReason()).isNotNull();
        }


    }
}
