package com.payment.paymentsystem.config;


import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI / Swagger configuration.
 *
 * Provides global metadata about the API: title, description, version,
 * contact info, server URLs. This information appears at the top of the
 * Swagger UI page and in the generated /v3/api-docs JSON.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Payment Processing API")
                        .description("""
                                Event-driven payment processing system.

                                ## Overview
                                Accepts payment requests, processes them asynchronously through Kafka,
                                and calls a payment gateway. Provides idempotency, state machine
                                guarantees, and reconciliation for stuck payments.

                                ## Key concepts
                                - **Idempotency**: Use the `idempotencyKey` field to safely retry
                                  requests. The same key returns the same payment.
                                - **Async processing**: A successful POST returns 202 with status
                                  PENDING. The payment processes asynchronously and transitions to
                                  SUCCESS, FAILED, or UNKNOWN.
                                - **Correlation**: Pass an `X-Correlation-Id` header to trace requests
                                  across logs.
                                """)
                        .version("0.5.0")
                        .contact(new Contact()
                                .name("Aryan")
                                .url("https://github.com/aryan/payment-system"))
                        .license(new License()
                                .name("MIT")
                                .url("https://opensource.org/licenses/MIT")))
                .servers(List.of(
                        new Server()
                                .url("http://localhost:8090")
                                .description("Local development")
                ));
    }
}