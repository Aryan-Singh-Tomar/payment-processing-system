package com.payment.paymentsystem.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Hidden;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Endpoint the payment gateway calls to notify us of finalized charge outcomes.
 *
 * Security: same HMAC-SHA256 verification as Day 25 outbound webhooks, but
 * applied to incoming requests. The gateway and our system share a secret;
 * the gateway signs each delivery; we verify before acting.
 *
 * Status codes:
 *   200 — accepted (or ignored for legitimate reasons like already-terminal)
 *   400 — malformed request (bad JSON, missing fields)
 *   401 — signature or timestamp invalid
 *   404 — payment not found (gateway should retry later)
 *
 * Marked @Hidden — operations-only, not exposed in public API docs.
 */
@Hidden
@RestController
@RequestMapping("/api/webhooks/gateway")
public class GatewayWebhookController {

    private static final Logger log = LoggerFactory.getLogger(GatewayWebhookController.class);

    private final WebhookSigner signer;
    private final WebhookProperties properties;
    private final ObjectMapper objectMapper;
    private final GatewayWebhookService service;

    public GatewayWebhookController(WebhookSigner signer,
                                    WebhookProperties properties,
                                    ObjectMapper objectMapper,
                                    GatewayWebhookService service) {
        this.signer = signer;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> receive(
            @RequestHeader(value = "X-Webhook-Timestamp", required = false) Long timestamp,
            @RequestHeader(value = "X-Webhook-Signature", required = false) String signature,
            @RequestBody String body
    ) {
        log.info("=== INCOMING WEBHOOK DEBUG ===");
        log.info("Body length: {}", body.length());
        log.info("Body bytes (hex first 80): {}",
                java.util.HexFormat.of().formatHex(
                        body.substring(0, Math.min(80, body.length())).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        log.info("Body content: <<<{}>>>", body);
        log.info("Timestamp header: {}", timestamp);
        log.info("Signature header: {}", signature);

// Compute what we expect, and compare
        String expectedSig = signer.sign(properties.signingSecret(), timestamp, body);
        log.info("Expected signature: {}", expectedSig);
        log.info("Received signature: {}", signature);
        log.info("Match? {}", expectedSig.equals(signature));
        log.info("Secret in use: '{}'", properties.signingSecret());
        log.info("=== END WEBHOOK DEBUG ===");
        // Step 1: required headers
        if (timestamp == null || signature == null) {
            log.warn("Gateway webhook missing required headers");
            return badRequest("missing required headers (X-Webhook-Timestamp, X-Webhook-Signature)");
        }

        // Step 2: timestamp tolerance
        long now = Instant.now().getEpochSecond();
        long age = Math.abs(now - timestamp);
        if (age > properties.replayToleranceSeconds()) {
            log.warn("Gateway webhook timestamp out of tolerance: age={}s, tolerance={}s",
                    age, properties.replayToleranceSeconds());
            return unauthorized("timestamp out of tolerance");
        }

        // Step 3: signature
        if (!signer.verify(properties.signingSecret(), timestamp, body, signature)) {
            log.warn("Gateway webhook signature verification failed");
            return unauthorized("invalid signature");
        }

        // Step 4: parse the body
        GatewayWebhookEvent event;
        try {
            event = objectMapper.readValue(body, GatewayWebhookEvent.class);
        } catch (Exception ex) {
            log.warn("Gateway webhook body parse failed: {}", ex.getMessage());
            return badRequest("malformed body");
        }

        // Step 5: apply
        GatewayWebhookService.ApplyResult result = service.apply(event);

        return switch (result) {
            case APPLIED, ALREADY_PROCESSED, IGNORED_TERMINAL, IGNORED_OTHER -> ok(result);
            case PAYMENT_NOT_FOUND -> notFound(event.paymentId().toString());
        };
    }

    private ResponseEntity<Map<String, Object>> ok(GatewayWebhookService.ApplyResult result) {
        return ResponseEntity.ok(Map.of("status", result.name().toLowerCase()));
    }

    private ResponseEntity<Map<String, Object>> badRequest(String reason) {
        return ResponseEntity.badRequest().body(Map.of("error", reason));
    }

    private ResponseEntity<Map<String, Object>> unauthorized(String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", reason);
        return ResponseEntity.status(401).body(body);
    }

    private ResponseEntity<Map<String, Object>> notFound(String paymentId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "payment not found");
        body.put("paymentId", paymentId);
        return ResponseEntity.status(404).body(body);
    }
}