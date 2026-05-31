package com.payment.paymentsystem.service;

import com.payment.paymentsystem.entity.Payment;
import com.payment.paymentsystem.entity.PaymentStatus;
import com.payment.paymentsystem.exception.PaymentNotFoundException;
import com.payment.paymentsystem.gateway.GatewayChargeResponse;
import com.payment.paymentsystem.repository.PaymentRepository;
import com.payment.paymentsystem.webhook.WebhookDeliveryService;
import com.payment.paymentsystem.webhook.WebhookEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Holds the @Transactional database operations performed by the consumer.
 *
 * Why separate from the consumer:
 *   - The consumer (PaymentEventConsumer) orchestrates: load → call gateway → save.
 *   - The gateway call takes 200-3000ms; we cannot hold a DB transaction across it.
 *   - So we break the work into two short transactions: markProcessing and recordResult.
 *   - Each method here is one DB transaction; the consumer calls them across
 *     the gateway interaction.
 *
 * Why Spring's proxy-based @Transactional works here:
 *   - The consumer is one bean; this service is a different bean.
 *   - The consumer calls service methods through the Spring container, so the
 *     proxy intercepts and opens a transaction (same lesson as Day 10/15).
 */

@Service
public class PaymentProcessingService {

    private static final Logger log = LoggerFactory.getLogger(PaymentProcessingService.class);
    private final PaymentRepository paymentRepository;
    private final WebhookDeliveryService webhookDeliveryService;

    public PaymentProcessingService(PaymentRepository paymentRepository,
                                    WebhookDeliveryService webhookDeliveryService) {
        this.paymentRepository = paymentRepository;
        this.webhookDeliveryService = webhookDeliveryService;
    }

    /**
     * Transition PENDING → PROCESSING. Short transaction; the gateway call
     * happens AFTER this commits, with no DB connection held.
     *
     * If the payment isn't in PENDING (already processed, or in some other state),
     * we silently do nothing — the consumer will see the existing state and skip
     * gateway invocation. This is the simplest form of consumer-side idempotency
     * (we improve it on Day 22 with an explicit processed-events table).
     */

    @Transactional
    public boolean markProcessing(UUID paymentId){
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));

        if (payment.getStatus() == PaymentStatus.PENDING) {
            payment.setStatus(PaymentStatus.PROCESSING);
            paymentRepository.save(payment);
            log.info("Payment {} transitioned PENDING → PROCESSING", paymentId);
            return true;
        }

        if (payment.getStatus() == PaymentStatus.PROCESSING) {
            log.info("Payment {} is already PROCESSING — retry attempt, proceeding with gateway call", paymentId);
            return true;   // allow retry
        }

        // SUCCESS, FAILED, UNKNOWN — terminal, don't reprocess
        log.info("Skipping payment {}: status is {}, terminal — not processing", paymentId, payment.getStatus());
        return false;
    }

    /**
     * Record the gateway response on the payment row. Final transition:
     *   PROCESSING → SUCCESS  (on Success)
     *   PROCESSING → FAILED   (on Failure)
     *   PROCESSING → UNKNOWN  (on Timeout)
     *
     * Idempotent at the @Transactional boundary: if called twice for the
     * same paymentId, the second call will see status != PROCESSING and
     * skip. Day 22 adds stronger guarantees via a processed-events log.
     */

    @Transactional
    public void recordResult(UUID paymentId, GatewayChargeResponse response){
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));

        if (payment.getStatus() != PaymentStatus.PROCESSING) {
            log.warn("Payment {} status is {}, not PROCESSING; skipping result recording. " +
                            "Likely a duplicate event or out-of-order processing.",
                    paymentId, payment.getStatus());
            return;
        }

        if (response instanceof GatewayChargeResponse.Success success) {
            payment.setStatus(PaymentStatus.SUCCESS);
            payment.setGatewayPaymentId(success.gatewayTransactionId());
            payment.setGatewayResponse("Success at " + success.occurredAt());
            payment.setProcessedAt(OffsetDateTime.now());
            paymentRepository.save(payment);
            log.info("Payment {} → SUCCESS (gateway payment id: {})",
                    paymentId, success.gatewayTransactionId());
        }else if(response instanceof GatewayChargeResponse.Failure failure){
            payment.setStatus(PaymentStatus.FAILED);
            payment.setFailureReason(failure.failureCode());
            payment.setGatewayResponse(failure.failureMessage());
            payment.setProcessedAt(OffsetDateTime.now());
            paymentRepository.save(payment);
            log.info("Payment {} → FAILED (reason: {})",
                    paymentId, failure.failureCode());
        }else if (response instanceof GatewayChargeResponse.Timeout timeout){
            payment.setStatus(PaymentStatus.UNKNOWN);
            payment.setFailureReason("TIMEOUT");
            payment.setGatewayResponse("Gateway did not respond by " + timeout.occurredAt());
            payment.setProcessedAt(OffsetDateTime.now());
            paymentRepository.save(payment);
            log.warn("Payment {} → UNKNOWN (gateway timeout). " +
                            "Will be resolved by webhook (Week 5) or reconciliation (Week 6).",
                    paymentId);
        }else {
            // Defensive: should be unreachable because the sealed interface only
            // permits the three types above. If a fourth type is ever added without
            // updating this method, we want to fail loudly rather than silently skip.
            throw new IllegalStateException(
                    "Unhandled GatewayChargeResponse type: " + response.getClass());
        }

        if (payment.getWebhookUrl() != null && !payment.getWebhookUrl().isBlank()) {
            WebhookEvent event = buildWebhookEvent(payment);
            webhookDeliveryService.deliver(payment.getWebhookUrl(), event);
        }

    }

    private WebhookEvent buildWebhookEvent(Payment payment) {
        String eventType = switch (payment.getStatus()) {
            case SUCCESS -> "payment.succeeded";
            case FAILED -> "payment.failed";
            case UNKNOWN -> "payment.unknown";
            default -> "payment.unknown";   // unreachable; defensive
        };

        return new WebhookEvent(
                UUID.randomUUID(),               // fresh eventId per delivery
                eventType,
                payment.getId(),
                payment.getOrderId(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getStatus(),
                payment.getGatewayPaymentId(),
                payment.getFailureReason(),
                OffsetDateTime.now()
        );
    }




}
