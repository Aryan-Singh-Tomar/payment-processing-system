package com.payment.paymentsystem.gateway;


import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "app.gateway")
@Validated
public record FakeGatewayProperties (
    @DecimalMin("0.0") @DecimalMax("1.0") double successRate,
    @DecimalMin("0.0") @DecimalMax("1.0") double failureRate,
    @DecimalMin("0.0") @DecimalMax("1.0") double timeoutRate,
    @Min(0) int minLatencyMs,
    @Min(0) int maxLatencyMs,
    @Min(0) int timeoutDurationMs
){}
