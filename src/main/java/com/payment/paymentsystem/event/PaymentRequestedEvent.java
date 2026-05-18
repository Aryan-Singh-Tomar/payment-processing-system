package com.payment.paymentsystem.event;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Event published when a payment row is successfully created in the DB.
 * The consumer (Day 20) will pick this up and drive the payment through
 * the fake gateway, updating status from PENDING → PROCESSING → SUCCESS/FAILED/UNKNOWN.
 *
 * Designed as a Java record because:
 *   - Immutable by default — events should never be mutated after publishing.
 *   - Compact syntax for value-only data carriers.
 *   - Jackson serializes records cleanly with no extra configuration.
 *
 * Fields are deliberately minimal: just enough for the consumer to do its work.
 * We do NOT include internal fields like `version` or `idempotencyKey` — those
 * are implementation details of the API, not of the event contract.
 */
public record PaymentRequestedEvent(
        UUID paymentId,
        UUID orderId,
        BigDecimal amount,
        String currency,
        OffsetDateTime occurredAt
) {
    public static PaymentRequestedEvent of(UUID paymentId, UUID orderId,
                                           BigDecimal amount, String currency) {
        return new PaymentRequestedEvent(
                paymentId, orderId, amount, currency, OffsetDateTime.now());
    }
}