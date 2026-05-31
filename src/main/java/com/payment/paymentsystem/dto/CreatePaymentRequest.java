package com.payment.paymentsystem.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

@Schema(description = "Request body for creating a new payment against an existing order.")
public class CreatePaymentRequest {

    @Schema(
            description = "ID of the order this payment is for. Must already exist and be in CREATED status.",
            example = "11111111-1111-1111-1111-111111111111",
            requiredMode = Schema.RequiredMode.REQUIRED
    )
    @NotNull(message = "orderId is required")
    private UUID orderId;

    @Schema(
            description = "Amount to charge. Must equal the order amount exactly.",
            example = "1500.00",
            requiredMode = Schema.RequiredMode.REQUIRED
    )
    @NotNull(message = "amount is required")
    @DecimalMin(value = "0.0001", message = "amount must be greater than 0")
    @Digits(integer = 15, fraction = 4, message = "amount must have at most 15 digits before and 4 after the decimal")
    private BigDecimal amount;

    @Schema(
            description = "ISO 4217 currency code, 3 uppercase letters. Must match the order's currency.",
            example = "INR",
            requiredMode = Schema.RequiredMode.REQUIRED
    )
    @NotBlank(message = "currency is required")
    @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be a 3-letter uppercase ISO code")
    private String currency;

    @Schema(
            description = "Client-supplied unique key to make this request idempotent. " +
                    "Submitting the same key twice will (from Week 2 onward) return the original payment.",
            example = "idem-key-abc-123",
            requiredMode = Schema.RequiredMode.REQUIRED,
            minLength = 8,
            maxLength = 128
    )
    @NotBlank(message = "idempotencyKey is required")
    @Size(min = 8, max = 128, message = "idempotencyKey must be between 8 and 128 characters")
    private String idempotencyKey;
    @Size(max = 2048, message = "webhookUrl must be at most 2048 characters")
    @Pattern(
            regexp = "^(https?://).*",
            message = "webhookUrl must start with http:// or https://"
    )
    private String webhookUrl;

    // getters and setters (unchanged from Day 4)
    public UUID getOrderId() { return orderId; }
    public void setOrderId(UUID orderId) { this.orderId = orderId; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getWebhookUrl() { return webhookUrl; }
    public void setWebhookUrl(String webhookUrl) { this.webhookUrl = webhookUrl; }
}