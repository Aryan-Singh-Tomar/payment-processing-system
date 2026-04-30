package com.payment.paymentsystem.dto;

import com.payment.paymentsystem.entity.PaymentStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public class PaymentResponse {

    private UUID id;
    private UUID orderId;
    private BigDecimal amount;
    private String currency;
    private PaymentStatus status;
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

    // getters and setters
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