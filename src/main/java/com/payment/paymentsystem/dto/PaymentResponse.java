package com.payment.paymentsystem.dto;

import com.payment.paymentsystem.entity.PaymentStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Schema(description = "Payment details returned after creation or status query")
public class PaymentResponse {

    @Schema(
            description = "System-generated payment ID",
            example = "557c9e62-ef6e-4715-929d-25d4c77f5a76"
    )
    private UUID id;

    @Schema(
            description = "Order this payment was made for",
            example = "31310001-3131-3131-3131-313131313131"
    )
    private UUID orderId;

    @Schema(
            description = "Payment amount",
            example = "1500.00"
    )
    private BigDecimal amount;

    @Schema(
            description = "ISO 4217 currency code",
            example = "INR"
    )
    private String currency;

    @Schema(
            description = """
                    Current status of the payment.
                    - PENDING: just created, queued for processing
                    - PROCESSING: consumer is actively calling the gateway
                    - SUCCESS: payment completed successfully
                    - FAILED: gateway rejected the payment
                    - UNKNOWN: gateway response was lost; reconciliation will resolve
                    """,
            example = "SUCCESS"
    )
    private PaymentStatus status;

    @Schema(
            description = "When the payment was created",
            example = "2026-06-08T15:03:05.488774+00:00"
    )
    private OffsetDateTime createdAt;

    public PaymentResponse() {
    }

    public PaymentResponse(
            UUID id,
            UUID orderId,
            BigDecimal amount,
            String currency,
            PaymentStatus status,
            OffsetDateTime createdAt
    ) {
        this.id = id;
        this.orderId = orderId;
        this.amount = amount;
        this.currency = currency;
        this.status = status;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public void setOrderId(UUID orderId) {
        this.orderId = orderId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public void setStatus(PaymentStatus status) {
        this.status = status;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}