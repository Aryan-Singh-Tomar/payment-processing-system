package com.payment.paymentsystem.controller;

import com.payment.paymentsystem.dto.CreatePaymentRequest;
import com.payment.paymentsystem.dto.PaymentResponse;
import com.payment.paymentsystem.service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> createPayment(
            @Valid @RequestBody CreatePaymentRequest request){
        PaymentResponse response = paymentService.createPayment(request);
        URI location = URI.create("/api/payments/" + response.getId());

        return ResponseEntity.created(location).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<PaymentResponse> getPayment(@PathVariable UUID id){
        return ResponseEntity.ok(paymentService.getPayment(id));
    }


}
