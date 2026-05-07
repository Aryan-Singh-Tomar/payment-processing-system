package com.payment.paymentsystem.controller;


import com.payment.paymentsystem.dto.CreatePaymentRequest;
import com.payment.paymentsystem.dto.ErrorResponse;
import com.payment.paymentsystem.dto.PaymentResponse;
import com.payment.paymentsystem.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/payments")
@Tag(name = "Payments", description = "Create and retrieve payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @Operation(
            summary = "Create a new payment",
            description = """
                    Creates a new payment against an existing order in CREATED status.
                    The order must exist and be payable. The payment amount and
                    currency must match the order exactly.

                    Idempotency: each request must include an idempotencyKey.
                    Replaying the same key with the same payload returns the original payment.
                    Reusing the same key with a different payload returns 422.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Payment created in PENDING status",
                    content = @Content(schema = @Schema(implementation = PaymentResponse.class))),
            @ApiResponse(responseCode = "400", description = "Validation failure or malformed request",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Order not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Conflict — e.g., duplicate idempotency key (today)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "Business rule violation (amount/currency mismatch, order not payable)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping
    public ResponseEntity<PaymentResponse> createPayment(
            @Valid @RequestBody CreatePaymentRequest request) {

        PaymentResponse response = paymentService.createPayment(request);
        URI location = URI.create("/api/payments/" + response.getId());
        return ResponseEntity.created(location).body(response);
    }

    @Operation(
            summary = "Get a payment by ID",
            description = "Retrieves the current state of a payment, including its status."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Payment found",
                    content = @Content(schema = @Schema(implementation = PaymentResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid UUID in path",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Payment not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{id}")
    public ResponseEntity<PaymentResponse> getPayment(@PathVariable UUID id) {
        return ResponseEntity.ok(paymentService.getPayment(id));
    }
}