package com.payment.paymentsystem.webhook;

import com.payment.paymentsystem.entity.Payment;
import com.payment.paymentsystem.entity.PaymentStatus;
import com.payment.paymentsystem.repository.PaymentRepository;
import com.payment.paymentsystem.service.ProcessedEventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Applies a gateway webhook event to the corresponding payment.
 *
 * Idempotency: every incoming webhook has an eventId. We record processed
 * eventIds in the existing processed_events table (reusing Day 22's
 * infrastructure with a new event_type, "GatewayWebhook"). Replays are
 * detected and acknowledged with 200 — the gateway should NOT keep retrying
 * a webhook we've already processed.
 *
 * State transitions: gateway webhooks can transition UNKNOWN → SUCCESS or
 * UNKNOWN → FAILED. They CANNOT transition payments out of an already-terminal
 * state (SUCCESS or FAILED). The first authoritative answer wins.
 *
 * If the referenced payment doesn't exist yet (the gateway's webhook beat
 * our own consumer to the punch), we return 404 so the gateway can retry
 * later. This is a known race window; production systems sometimes add a
 * small staging queue to hold unmatched webhooks for a few minutes.
 */
@Service
public class GatewayWebhookService {

    private static final Logger log = LoggerFactory.getLogger(GatewayWebhookService.class);
    private static final String EVENT_TYPE = "GatewayWebhook";

    private final PaymentRepository paymentRepository;
    private final ProcessedEventService processedEventService;

    public GatewayWebhookService(PaymentRepository paymentRepository,
                                 ProcessedEventService processedEventService) {
        this.paymentRepository = paymentRepository;
        this.processedEventService = processedEventService;
    }

    public enum ApplyResult {
        APPLIED,           // 200 — payment transitioned as expected
        ALREADY_PROCESSED, // 200 — dedup hit (replay); ack the gateway
        PAYMENT_NOT_FOUND, // 404 — payment doesn't exist yet
        IGNORED_TERMINAL,  // 200 — payment is already in a terminal state, not UNKNOWN; do nothing
        IGNORED_OTHER      // 200 — payment is in PENDING/PROCESSING; consumer will resolve
    }

    @Transactional
    public ApplyResult apply(GatewayWebhookEvent event) {
        // Step 1: replay check
        String eventKey = event.eventId().toString();
        if (processedEventService.isProcessed(eventKey, EVENT_TYPE)) {
            log.info("Gateway webhook replay detected: eventId={}, paymentId={} — acknowledging",
                    event.eventId(), event.paymentId());
            return ApplyResult.ALREADY_PROCESSED;
        }

        // Step 2: lookup payment
        Optional<Payment> maybePayment = paymentRepository.findById(event.paymentId());
        if (maybePayment.isEmpty()) {
            log.warn("Gateway webhook for unknown paymentId={}, eventId={}. " +
                            "Possibly arrived before our consumer processed the event. " +
                            "Returning 404 so gateway can retry.",
                    event.paymentId(), event.eventId());
            return ApplyResult.PAYMENT_NOT_FOUND;
            // NOTE: we deliberately do NOT mark this eventId as processed.
            // We want the gateway to retry — maybe next time the payment exists.
        }

        Payment payment = maybePayment.get();
        PaymentStatus currentStatus = payment.getStatus();

        // Step 3: state-machine check
        if (currentStatus == PaymentStatus.SUCCESS || currentStatus == PaymentStatus.FAILED) {
            log.info("Gateway webhook for paymentId={} ignored — payment is already in terminal " +
                            "state {}. First answer wins.",
                    event.paymentId(), currentStatus);
            // Record the event as processed so future replays are quick. We've made a
            // deliberate decision not to act on it; that decision is final.
            processedEventService.markProcessed(eventKey, EVENT_TYPE);
            return ApplyResult.IGNORED_TERMINAL;
        }

        if (currentStatus == PaymentStatus.PENDING || currentStatus == PaymentStatus.PROCESSING) {
            log.info("Gateway webhook for paymentId={} arrived early — payment is in {}. " +
                            "Our consumer will resolve this; ignoring webhook.",
                    event.paymentId(), currentStatus);
            // Mark as processed so the gateway stops retrying. Our consumer is in flight
            // and will reach a terminal state on its own.
            processedEventService.markProcessed(eventKey, EVENT_TYPE);
            return ApplyResult.IGNORED_OTHER;
        }

        // currentStatus == UNKNOWN: this is the case we're built for
        if (currentStatus != PaymentStatus.UNKNOWN) {
            // Should be unreachable; defensive log
            log.warn("Unexpected payment status {} for paymentId={}", currentStatus, event.paymentId());
            return ApplyResult.IGNORED_OTHER;
        }

        // Step 4: apply the transition
        switch (event.eventType()) {
            case "gateway.charge.succeeded" -> {
                payment.setStatus(PaymentStatus.SUCCESS);
                payment.setGatewayPaymentId(event.gatewayPaymentId());
                payment.setGatewayResponse("Resolved via gateway webhook at " + event.occurredAt());
                payment.setProcessedAt(OffsetDateTime.now());
                payment.setFailureReason(null);  // clear any stale TIMEOUT reason
                log.info("Payment {} transitioned UNKNOWN → SUCCESS via gateway webhook " +
                                "(eventId={}, gatewayPaymentId={})",
                        payment.getId(), event.eventId(), event.gatewayPaymentId());
            }
            case "gateway.charge.failed" -> {
                payment.setStatus(PaymentStatus.FAILED);
                payment.setFailureReason(event.failureCode());
                payment.setGatewayResponse(event.failureMessage());
                payment.setProcessedAt(OffsetDateTime.now());
                log.info("Payment {} transitioned UNKNOWN → FAILED via gateway webhook " +
                                "(eventId={}, reason={})",
                        payment.getId(), event.eventId(), event.failureCode());
            }
            default -> {
                log.warn("Unknown event type '{}' for gateway webhook eventId={}; ignoring",
                        event.eventType(), event.eventId());
                return ApplyResult.IGNORED_OTHER;
            }
        }

        paymentRepository.save(payment);
        processedEventService.markProcessed(eventKey, EVENT_TYPE);

        return ApplyResult.APPLIED;
    }
}