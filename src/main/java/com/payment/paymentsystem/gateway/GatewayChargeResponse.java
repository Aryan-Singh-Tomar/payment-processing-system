package com.payment.paymentsystem.gateway;

import java.time.OffsetDateTime;
import java.util.UUID;

public sealed interface GatewayChargeResponse
        permits GatewayChargeResponse.Success,
        GatewayChargeResponse.Failure,
        GatewayChargeResponse.Timeout{

    UUID paymentId();
    OffsetDateTime occurredAt();

    record Success(
            UUID paymentId,
            String gatewayTransactionId,
            OffsetDateTime occurredAt
    ) implements GatewayChargeResponse {
    }

    record Failure(
            UUID paymentId,
            String failureCode,
            String failureMessage,
            OffsetDateTime occurredAt
    ) implements GatewayChargeResponse {
    }

    record Timeout(
            UUID paymentId,
            OffsetDateTime occurredAt
    ) implements GatewayChargeResponse {
    }
}
