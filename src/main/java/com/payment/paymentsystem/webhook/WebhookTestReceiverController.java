package com.payment.paymentsystem.webhook;

import io.swagger.v3.oas.annotations.Hidden;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Internal test receiver. Lets you POST a webhook to yourself for end-to-end
 * verification of the delivery flow.
 *
 * Verifies:
 *   - HMAC-SHA256 signature
 *   - Timestamp within tolerance (anti-replay)
 *   - Nonce not seen before (anti-replay)
 *
 * The "seen nonces" set is an in-memory ConcurrentHashMap-backed set. In
 * production this would be Redis with TTL matching the timestamp tolerance.
 */
@Hidden
@RestController
@RequestMapping("/api/internal/webhook-receiver")
public class WebhookTestReceiverController {

    private static final Logger log = LoggerFactory.getLogger(WebhookTestReceiverController.class);

    private final WebhookSigner signer;
    private final WebhookProperties properties;
    private final Set<String> seenNonces = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public WebhookTestReceiverController(WebhookSigner signer, WebhookProperties properties) {
        this.signer = signer;
        this.properties = properties;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> receive(
            @RequestHeader("X-Webhook-Timestamp") long timestamp,
            @RequestHeader("X-Webhook-Nonce") String nonce,
            @RequestHeader("X-Webhook-Signature") String signature,
            @RequestBody String body
    ) {
        Map<String, Object> response = new LinkedHashMap<>();

        // Check 1: timestamp within tolerance
        long now = Instant.now().getEpochSecond();
        long age = Math.abs(now - timestamp);
        if (age > properties.replayToleranceSeconds()) {
            log.warn("Rejected webhook: timestamp {} is {} seconds old (tolerance {})",
                    timestamp, age, properties.replayToleranceSeconds());
            response.put("status", "rejected");
            response.put("reason", "timestamp out of tolerance");
            return ResponseEntity.status(401).body(response);
        }

        // Check 2: signature is valid
        boolean valid = signer.verify(properties.signingSecret(), timestamp, body, signature);
        if (!valid) {
            log.warn("Rejected webhook: signature verification failed");
            response.put("status", "rejected");
            response.put("reason", "invalid signature");
            return ResponseEntity.status(401).body(response);
        }

        // Check 3: nonce not seen before
        if (!seenNonces.add(nonce)) {
            log.warn("Rejected webhook: nonce {} already seen (replay attempt?)", nonce);
            response.put("status", "rejected");
            response.put("reason", "duplicate nonce");
            return ResponseEntity.status(409).body(response);
        }

        // All checks passed.
        log.info("Webhook accepted: nonce={}, body={}", nonce, body);
        response.put("status", "accepted");
        response.put("nonce", nonce);
        return ResponseEntity.ok(response);
    }
}