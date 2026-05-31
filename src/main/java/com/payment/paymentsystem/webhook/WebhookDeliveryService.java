package com.payment.paymentsystem.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;


/**
 * Delivers webhook events to client-provided URLs.
 *
 * Each delivery includes three headers for security:
 *   X-Webhook-Timestamp: <epoch-seconds when signature was computed>
 *   X-Webhook-Nonce:     <unique per-delivery UUID>
 *   X-Webhook-Signature: sha256=<hex HMAC of (timestamp + "." + body)>
 *
 * Delivery is best-effort with bounded retries. Failures after all attempts
 * are logged loudly. In production, failed deliveries would be persisted to
 * a retry queue or a dead-letter table for ops to investigate.
 *
 * Why a synchronous HttpClient call from the consumer thread? Because the
 * webhook trigger runs from within the Kafka consumer, which is already on
 * a background thread. Adding more async machinery here would create more
 * complexity than it saves. Production systems with very-high-throughput
 * webhook fanout would use a dedicated thread pool or an async HTTP client.
 */
@Service
public class WebhookDeliveryService {
    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryService.class);
    private final WebhookProperties properties;
    private final WebhookSigner signer;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public WebhookDeliveryService(WebhookProperties properties,
                                  WebhookSigner signer,
                                  ObjectMapper objectMapper) {
        this.properties = properties;
        this.signer = signer;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.timeoutMs()))
                .build();
    }

    /**
     * Attempt delivery with bounded retries. Logs success or final failure.
     */
    public void deliver(String url, WebhookEvent event){
        String body;
        try{
            body = objectMapper.writeValueAsString(event);
        }catch (JsonProcessingException ex){
            log.error("Failed to serialize webhook event for paymentId={}; aborting delivery",
                    event.paymentId(), ex);
            return;
        }

        long timestamp = Instant.now().getEpochSecond();
        String signature = signer.sign(properties.signingSecret(), timestamp, body);

        for (int attempt = 1; attempt <= properties.maxAttempts(); attempt++){
            try{
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofMillis(properties.timeoutMs()))
                        .header("Content-Type", "application/json")
                        .header("X-Webhook-Timestamp", String.valueOf(timestamp))
                        .header("X-Webhook-Nonce", event.eventId().toString())
                        .header("X-Webhook-Signature", signature)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();

                HttpResponse<String> response = httpClient.send(
                        request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    log.info("Webhook delivered successfully on attempt {}: " +
                                    "paymentId={}, url={}, status={}",
                            attempt, event.paymentId(), url, response.statusCode());
                    return;
                }

                log.warn("Webhook attempt {} failed for paymentId={}: " +
                                "url={}, status={}, body={}",
                        attempt, event.paymentId(), url,
                        response.statusCode(), truncate(response.body()));

            }catch (Exception ex) {
                log.warn("Webhook attempt {} threw for paymentId={}: url={}, error={}",
                        attempt, event.paymentId(), url, ex.getMessage());
            }

            // Backoff before next attempt
            if (attempt < properties.maxAttempts()) {
                sleepBackoff(attempt);
            }
        }
        log.error("Webhook delivery exhausted all {} attempts: paymentId={}, url={}. " +
                        "Manual investigation required.",
                properties.maxAttempts(), event.paymentId(), url);
    }

    private void sleepBackoff(int attempt) {
        long delayMs = properties.initialBackoffMs() * (1L << (attempt - 1));
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private String truncate(String s) {
        if (s == null) return null;
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}
