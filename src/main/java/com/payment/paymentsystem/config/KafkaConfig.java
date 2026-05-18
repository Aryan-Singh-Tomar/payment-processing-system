package com.payment.paymentsystem.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    @Value("${app.kafka.topics.payment-requested}")
    private String paymentRequestedTopic;

    @Bean
    public NewTopic paymentRequestedTopic(){
        return TopicBuilder.name(paymentRequestedTopic)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
