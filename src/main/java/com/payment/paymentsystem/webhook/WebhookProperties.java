package com.payment.paymentsystem.webhook;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Webhook delivery configuration.
 *
 * @param signingSecret  HMAC-SHA256 shared secret. In production, this would
 *                       be per-client (each integration partner has their own
 *                       secret). For the learning project, we use one secret
 *                       for the test receiver.
 * @param timeoutMs      HTTP timeout for a single delivery attempt.
 * @param maxAttempts    Total attempts (original + retries) before giving up.
 * @param initialBackoffMs  Backoff for first retry; doubles each subsequent retry.
 * @param replayToleranceSeconds  Receiver-side: reject deliveries with
 *                                timestamps older than this.
 */
@ConfigurationProperties(prefix = "app.webhook")
public record WebhookProperties(
        String signingSecret,
        int timeoutMs,
        int maxAttempts,
        int initialBackoffMs,
        int replayToleranceSeconds
) {
}
