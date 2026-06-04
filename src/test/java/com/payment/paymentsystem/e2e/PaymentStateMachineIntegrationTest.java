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
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.catchThrowableOfType;
import static org.awaitility.Awaitility.await;

@DisplayName("State Machine — Second Payment For Same Order Is Rejected")
public class PaymentStateMachineIntegrationTest extends AbstractIntegrationTest{

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private final RestTemplate http = new RestTemplate();

    @BeforeEach
    void setUp() {
        paymentRepository.deleteAll();
        orderRepository.deleteAll();
    }

    @Test
    @DisplayName("Sequential POST for same order (different idempotency key) returns 422")
    void second_payment_for_same_order_is_rejected() throws Exception{
        // Arrange: an order
        Order order = newOrder();
        UUID orderId = order.getId();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        String firstBody = """
                {
                "orderId" : "%s",
                "amount" : 1500.00,
                "currency" : "INR",
                "idempotencyKey" : "e2e-sm-1-%s"
                }
                """.formatted(orderId, UUID.randomUUID());

        String secondBody = """
                {
                  "orderId": "%s",
                  "amount": 1500.00,
                  "currency": "INR",
                  "idempotencyKey": "e2e-sm-2-%s"
                }
                """.formatted(orderId, UUID.randomUUID());

        // Act 1: first POST — accepted
        ResponseEntity<String> firstResponse = http.postForEntity(
                baseUrl() + "/api/payments",
                new HttpEntity<>(firstBody, headers),
                String.class
        );

        assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        JsonNode firstResponseBody = objectMapper.readTree(firstResponse.getBody());
        UUID firstPaymentId = UUID.fromString(firstResponseBody.get("id").asText());

        // Wait for the first payment to reach a terminal state so the state machine
        // sees a concrete blocking state (SUCCESS / FAILED / UNKNOWN / or in-flight PROCESSING)
        await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    Payment current = paymentRepository.findById(firstPaymentId).orElseThrow();
                    assertThat(current.getStatus()).isIn(
                            PaymentStatus.SUCCESS,
                            PaymentStatus.FAILED,
                            PaymentStatus.UNKNOWN
                    );
                });

        // Act 2: second POST with different idempotency key — should be rejected
        HttpClientErrorException ex = catchThrowableOfType(
                () -> http.postForEntity(
                        baseUrl() + "/api/payments",
                        new HttpEntity<>(secondBody, headers),
                        String.class
                ),
                HttpClientErrorException.class
        );

        // Assert: 422 Unprocessable Entity
        assertThat(ex).isNotNull();
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        // Whether the body says "already been paid" or "already has a payment in progress"
        // depends on which terminal state the first payment reached. Either way, the
        // response body should mention the order or payment being already handled.
        String responseBody = ex.getResponseBodyAsString();
        assertThat(responseBody.toLowerCase())
                .as("422 response should explain why the second payment was rejected")
                .containsAnyOf("already", "in progress", "paid");

        // Assert: ONLY one payment was created for this order
        List<Payment> payments = paymentRepository.findAllByOrderId(orderId);
        assertThat(payments)
                .as("State machine must block the second payment from being created")
                .hasSize(1);

    }


    private Order newOrder() {
        Order order = new Order();
        order.setCustomerId("CUST-SM-" + UUID.randomUUID());
        order.setAmount(new BigDecimal("1500.00"));
        order.setCurrency("INR");
        order.setStatus(OrderStatus.CREATED);
        return orderRepository.saveAndFlush(order);
    }
}
