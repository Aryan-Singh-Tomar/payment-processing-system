package com.payment.paymentsystem.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.util.UUID;

public class CreatePaymentRequest {

    @NotNull(message = "orderId is required")
    private UUID orderId;
    @NotNull(message = "amount is required")
    @DecimalMin(value = "0.0001", message = "amount must be greater than 0")
    @Digits(integer = 15, fraction = 4, message = "amount must have at most 15 digits before and 4 after the decimal")
    private BigDecimal amount;
    @NotBlank(message = "currency is required")
    @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be a 3-letter uppercase ISO code")
    private String currency;
    @NotBlank(message = "idempotencyKey is required")
    @Size(min = 8, max = 128, message = "idempotencyKey must be between 8 and 128 characters")
    private String idempotencyKey;

    // getters and setters
    public UUID getOrderId() { return orderId; }
    public void setOrderId(UUID orderId) { this.orderId = orderId; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

}
