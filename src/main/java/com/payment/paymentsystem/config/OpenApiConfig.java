package com.payment.paymentsystem.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;

public class OpenApiConfig {
    @Bean
    public OpenAPI paymentSystemOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Event-Driven Payment Processing System")
                        .version("0.1.0")
                        .description("""
                                A production-style payment processing system demonstrating
                                idempotency, distributed locking, async event processing,
                                webhooks, and reconciliation.
                                
                                Tech: Spring Boot 3, PostgreSQL, Redis, Kafka, Docker.
                                """)
                        .contact(new Contact()
                                .name("Aryan")
                                .url("https://github.com/your-username/payment-system"))
                        .license(new License()
                                .name("MIT")
                                .url("https://opensource.org/licenses/MIT")));
    }
}
