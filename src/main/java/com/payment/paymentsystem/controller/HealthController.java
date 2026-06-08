package com.payment.paymentsystem.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api")
@Tag(name = "Health", description = "Service liveness check")
public class HealthController {

    @Operation(summary = "Liveness check", description = "Returns UP if the service is responsive.")
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "service", "payment-system",
                "timestamp", Instant.now().toString()
        );
    }

}