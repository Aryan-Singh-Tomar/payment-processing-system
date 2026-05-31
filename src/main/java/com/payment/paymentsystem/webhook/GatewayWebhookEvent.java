package com.payment.paymentsystem.webhook;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record GatewayWebhookEvent(
        UUID eventId,                  // unique per logical delivery, used for dedup
        String eventType,              // "gateway.charge.succeeded" or "gateway.charge.failed"
        UUID paymentId,                // identifies which payment this is about
        String gatewayPaymentId,       // the gateway's transaction ID (present on success)
        BigDecimal chargedAmount,      // what the gateway actually charged (present on success)
        String failureCode,            // e.g. "CARD_DECLINED" (present on failure)
        String failureMessage,         // human-readable description (present on failure)
        OffsetDateTime occurredAt      // when the gateway finalized the decision
) {
}
