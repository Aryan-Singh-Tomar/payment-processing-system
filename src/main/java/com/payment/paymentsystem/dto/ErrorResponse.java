package com.payment.paymentsystem.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.List;

@Schema(description = "Standard error response shape")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {

    @Schema(
            description = "When the error occurred",
            example = "2026-06-08T15:03:05.488774+00:00"
    )
    private OffsetDateTime timestamp;

    @Schema(
            description = "HTTP status code",
            example = "422"
    )
    private int status;

    @Schema(
            description = "Short error category",
            example = "Unprocessable Entity"
    )
    private String error;

    @Schema(
            description = "Human-readable explanation",
            example = "Order already has a payment in progress (paymentId=..., status=PENDING)"
    )
    private String message;

    @Schema(
            description = "The request path that produced the error",
            example = "/api/payments"
    )
    private String path;

    @Schema(
            description = "Additional validation details when applicable",
            example = "[\"amount: amount must be greater than 0\"]"
    )
    private List<String> details;

    public ErrorResponse() {
    }

    public ErrorResponse(
            OffsetDateTime timestamp,
            int status,
            String error,
            String message,
            String path,
            List<String> details
    ) {
        this.timestamp = timestamp;
        this.status = status;
        this.error = error;
        this.message = message;
        this.path = path;
        this.details = details;
    }

    public OffsetDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(OffsetDateTime timestamp) {
        this.timestamp = timestamp;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public List<String> getDetails() {
        return details;
    }

    public void setDetails(List<String> details) {
        this.details = details;
    }
}