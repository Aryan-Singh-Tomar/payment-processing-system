package com.payment.paymentsystem.dto;


import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

@Schema(description = "Request to create a new payment")
public class CreatePaymentRequest {

    @Schema(
            description = "Unique identifier of the order to pay for",
            example = "31310001-3131-3131-3131-313131313131",
            requiredMode = Schema.RequiredMode.REQUIRED
    )
    @NotNull(message = "orderId is required")
    private UUID orderId;

    @Schema(
            description = "Payment amount in the smallest currency unit, e.g. paise for INR",
            example = "1500.00",
            requiredMode = Schema.RequiredMode.REQUIRED
    )
    @NotNull(message = "amount is required")
    @Positive(message = "amount must be greater than 0")
    private BigDecimal amount;

    @Schema(
            description = "ISO 4217 currency code",
            example = "INR",
            requiredMode = Schema.RequiredMode.REQUIRED,
            allowableValues = {"INR", "USD", "EUR"}
    )
    @NotBlank(message = "currency is required")
    @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be a 3-letter uppercase ISO code")
    private String currency;

    @Schema(
            description = "Client-chosen idempotency key. Retrying with the same key returns the original payment without creating a duplicate.",
            example = "client-payment-001",
            requiredMode = Schema.RequiredMode.REQUIRED
    )
    @NotBlank(message = "idempotencyKey is required")
    @Size(min = 8, max = 128, message = "idempotencyKey must be between 8 and 128 characters")
    private String idempotencyKey;

    @Schema(
            description = "Optional URL where the system will POST status updates as the payment progresses",
            example = "https://merchant.example.com/webhooks/payments",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            nullable = true
    )
    @Size(max = 2048, message = "webhookUrl must be at most 2048 characters")
    @Pattern(
            regexp = "^(https?://).*",
            message = "webhookUrl must start with http:// or https://"
    )
    private String webhookUrl;

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

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getWebhookUrl() {
        return webhookUrl;
    }

    public void setWebhookUrl(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }
}