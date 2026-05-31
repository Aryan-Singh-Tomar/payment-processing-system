package com.payment.paymentsystem.webhook;

import com.payment.paymentsystem.entity.PaymentStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The JSON body sent to webhook receivers when a payment reaches a terminal state.
 *
 * Fields are flat (not nested under "data" or "payment") to make HMAC computation
 * deterministic and receiver parsing simple.
 */
public record WebhookEvent(
        UUID eventId,       // unique per delivery; used for nonce/dedup at receive
        String eventType,    // "payment.succeeded" / "payment.failed" / "payment.unknown"
        UUID paymentId,
        UUID orderId,
        BigDecimal amount,
        String currency,
        PaymentStatus status,
        String gatewayPaymentId,    // null if status != SUCCESS
        String failureReason,       // null if status == SUCCESS
        OffsetDateTime occurredAt


) {
}
