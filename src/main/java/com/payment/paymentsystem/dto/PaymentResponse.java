package com.payment.paymentsystem.dto;


import com.payment.paymentsystem.entity.PaymentStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Schema(description = "Payment resource returned by create and get endpoints.")
public class PaymentResponse {

    @Schema(description = "Server-generated payment ID.",
            example = "8f4d2b1a-9c3e-4d5b-bc6a-7e8f9a0b1c2d")
    private UUID id;

    @Schema(description = "ID of the order this payment belongs to.",
            example = "11111111-1111-1111-1111-111111111111")
    private UUID orderId;

    @Schema(description = "Amount charged.", example = "1500.00")
    private BigDecimal amount;

    @Schema(description = "ISO 4217 currency code.", example = "INR")
    private String currency;

    @Schema(description = """
            Current payment status.
              - PENDING: created, not yet processing
              - PROCESSING: gateway call in flight
              - SUCCESS: confirmed by gateway (terminal)
              - FAILED: declined by gateway (terminal)
              - UNKNOWN: gateway timeout or ambiguous; reconciliation will resolve
            """,
            example = "PENDING")
    private PaymentStatus status;

    @Schema(description = "ISO-8601 timestamp when the payment row was created.",
            example = "2026-04-30T14:32:01Z")
    private OffsetDateTime createdAt;

    public PaymentResponse() {}

    public PaymentResponse(UUID id, UUID orderId, BigDecimal amount, String currency,
                           PaymentStatus status, OffsetDateTime createdAt) {
        this.id = id;
        this.orderId = orderId;
        this.amount = amount;
        this.currency = currency;
        this.status = status;
        this.createdAt = createdAt;
    }

    // getters and setters (unchanged)
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getOrderId() { return orderId; }
    public void setOrderId(UUID orderId) { this.orderId = orderId; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public PaymentStatus getStatus() { return status; }
    public void setStatus(PaymentStatus status) { this.status = status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}