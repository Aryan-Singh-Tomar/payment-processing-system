package com.payment.paymentsystem.controller;

import com.payment.paymentsystem.gateway.FakePaymentGatewayClient;
import com.payment.paymentsystem.gateway.GatewayChargeRequest;
import com.payment.paymentsystem.gateway.GatewayChargeResponse;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
@RequestMapping("/api/internal/gateway")
public class GatewayDebugController {

    private final FakePaymentGatewayClient gateway;

    public GatewayDebugController(FakePaymentGatewayClient gateway) {
        this.gateway = gateway;
    }

    @PostMapping("/charge")
    public GatewayChargeResponse charge(@RequestBody GatewayChargeRequest request) {
        return gateway.charge(request);
    }
}