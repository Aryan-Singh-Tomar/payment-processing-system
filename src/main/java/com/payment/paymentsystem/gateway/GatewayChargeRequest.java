package com.payment.paymentsystem.gateway;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Request to the payment gateway. Just enough info for the gateway to "process"
 * a charge: which payment we're charging, who the order belongs to, amount, currency.
 *
 * In a real integration this would also carry card details (or a tokenized
 * payment method ID). We omit those — they're not interesting for simulating
 * the outcome.
 */
public record GatewayChargeRequest (
        UUID paymentId,
        UUID orderId,
        BigDecimal amount,
        String currency
){}

