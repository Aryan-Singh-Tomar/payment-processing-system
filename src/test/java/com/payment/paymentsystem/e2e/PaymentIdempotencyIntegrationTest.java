package com.payment.paymentsystem.e2e;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payment.paymentsystem.entity.Order;
import com.payment.paymentsystem.entity.OrderStatus;
import com.payment.paymentsystem.entity.Payment;
import com.payment.paymentsystem.repository.OrderRepository;
import com.payment.paymentsystem.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Payment Idempotency — Same Key Returns Same Payment")
class PaymentIdempotencyIntegrationTest extends AbstractIntegrationTest {

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
    @DisplayName("Same idempotency key on retry returns the original payment ID")
    void idempotent_retry_returns_same_payment() throws Exception {
        // Arrange: an order, ready for payment
        Order order = newOrder();
        UUID orderId = order.getId();
        String idempotencyKey = "e2e-idem-" + UUID.randomUUID();

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
        HttpEntity<String> request = new HttpEntity<>(requestBody, headers);

        // Act 1: first POST
        ResponseEntity<String> firstResponse = http.postForEntity(
                baseUrl() + "/api/payments", request, String.class);

        // Act 2: second POST (same idempotency key, same payload)
        ResponseEntity<String> secondResponse = http.postForEntity(
                baseUrl() + "/api/payments", request, String.class);

        // Assert: both succeeded
        assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(secondResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        // Assert: same payment ID returned both times
        JsonNode firstBody = objectMapper.readTree(firstResponse.getBody());
        JsonNode secondBody = objectMapper.readTree(secondResponse.getBody());
        UUID firstPaymentId = UUID.fromString(firstBody.get("id").asText());
        UUID secondPaymentId = UUID.fromString(secondBody.get("id").asText());

        assertThat(secondPaymentId)
                .as("Idempotent retry must return the same payment ID as the first attempt")
                .isEqualTo(firstPaymentId);

        // Assert: only ONE payment row in the DB for this order
        List<Payment> payments = paymentRepository.findAllByOrderId(orderId);
        assertThat(payments)
                .as("Idempotent retry must NOT create a second payment row")
                .hasSize(1);
        assertThat(payments.get(0).getIdempotencyKey()).isEqualTo(idempotencyKey);
    }

    private Order newOrder() {
        Order order = new Order();
        order.setCustomerId("CUST-IDEM-" + UUID.randomUUID());
        order.setAmount(new BigDecimal("1500.00"));
        order.setCurrency("INR");
        order.setStatus(OrderStatus.CREATED);
        return orderRepository.saveAndFlush(order);
    }
}

