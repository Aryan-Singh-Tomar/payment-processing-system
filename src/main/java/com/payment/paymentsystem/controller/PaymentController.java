package com.payment.paymentsystem.controller;


import com.payment.paymentsystem.dto.CreatePaymentRequest;
import com.payment.paymentsystem.dto.ErrorResponse;
import com.payment.paymentsystem.dto.PaymentResponse;
import com.payment.paymentsystem.service.OptimisticPaymentApprovalService;
import com.payment.paymentsystem.service.PaymentApprovalService;
import com.payment.paymentsystem.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/payments")
@Tag(name = "Payments", description = "Create and retrieve payments")
public class PaymentController {

    private final PaymentService paymentService;
    private final PaymentApprovalService paymentApprovalService;
    private final OptimisticPaymentApprovalService optimisticApprovalService;   // NEW


    public PaymentController(PaymentService paymentService,
                             PaymentApprovalService paymentApprovalService,
                             OptimisticPaymentApprovalService optimisticApprovalService) {
        this.paymentService = paymentService;
        this.paymentApprovalService = paymentApprovalService;
        this.optimisticApprovalService = optimisticApprovalService;
    }

    @Operation(
            summary = "Approve a PENDING payment (optimistic locking, Day 15)",
            description = """
                    Promotes a payment from PENDING to SUCCESS using optimistic
                    locking via @Version. No row locks are taken; conflicts are
                    detected at UPDATE time and resolved via retry.
                    
                    Compare to POST /api/payments/{id}/approve which uses
                    pessimistic locking.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Payment approved",
                    content = @Content(schema = @Schema(implementation = PaymentResponse.class))),
            @ApiResponse(responseCode = "404", description = "Payment not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "Payment not PENDING, or order already has SUCCESS, or retry exhaustion",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{id}/approve-optimistic")
    public ResponseEntity<PaymentResponse> approveOptimistic(@PathVariable UUID id) {
        return ResponseEntity.ok(optimisticApprovalService.approve(id));
    }

    @Operation(
            summary = "Approve a PENDING payment (Day 13: race-prone, intentionally)",
            description = """
                    Promotes a payment from PENDING to SUCCESS.
                    
                    ⚠️ This endpoint has a known race condition under concurrent calls
                    for the same order. It exists to demonstrate the bug Day 14's
                    pessimistic locking will fix. Do not rely on this in any
                    production-like scenario.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Payment approved",
                    content = @Content(schema = @Schema(implementation = PaymentResponse.class))),
            @ApiResponse(responseCode = "404", description = "Payment not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "Payment not in PENDING or order already has SUCCESS",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Race condition — uniqueness violation under concurrency",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{id}/approve")
    public ResponseEntity<PaymentResponse> approve(@PathVariable UUID id) {
        return ResponseEntity.ok(paymentApprovalService.approve(id));
    }

    @PostMapping
    @Operation(
            summary = "Create a payment",
            description = """
                    Creates a payment for the given order and starts asynchronous processing.

                    The response returns immediately with status PENDING. The payment is then
                    processed via Kafka: the consumer calls the payment gateway and transitions
                    the payment to SUCCESS, FAILED, or UNKNOWN.

                    Idempotency: providing the same `idempotencyKey` on retry returns the
                    original payment without creating a duplicate.

                    State machine: an order can have at most one non-failed payment.
                    Subsequent POSTs for the same order are rejected with 422 unless the
                    previous payment failed.
                    """
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "202",
                    description = "Payment accepted for processing",
                    content = @Content(schema = @Schema(implementation = PaymentResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid request (missing fields, bad currency, etc.)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Order not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "422",
                    description = "Order already has a payment in progress or paid successfully",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ResponseEntity<PaymentResponse> createPayment(
            @Valid @RequestBody CreatePaymentRequest request) {

        PaymentResponse response = paymentService.createPayment(request);

        // Build the Location URI pointing at GET /api/payments/{id}.
        // ServletUriComponentsBuilder grabs the current request's context
        // (host, port, scheme, base path) so the URI works regardless of
        // deployment environment (localhost, staging, prod, behind a proxy).
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(response.getId())
                .toUri();

        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .location(location)
                .body(response);
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