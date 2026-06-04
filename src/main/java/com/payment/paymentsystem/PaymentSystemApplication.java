package com.payment.paymentsystem;

import com.payment.paymentsystem.gateway.FakeGatewayProperties;
import com.payment.paymentsystem.reconciliation.ReconciliationProperties;
import com.payment.paymentsystem.webhook.WebhookProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableConfigurationProperties({
		FakeGatewayProperties.class,
		WebhookProperties.class,
		ReconciliationProperties.class   // NEW
})
@EnableScheduling
public class PaymentSystemApplication {


	public static void main(String[] args) {
		SpringApplication.run(PaymentSystemApplication.class, args);
	}

}
