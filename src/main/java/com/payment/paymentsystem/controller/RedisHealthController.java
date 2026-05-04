package com.payment.paymentsystem.controller;


import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

@Hidden  // exclude from Swagger — this is a temporary smoke-test endpoint
@RestController
@RequestMapping("/api/internal/redis")
public class RedisHealthController {

    private final StringRedisTemplate redis;

    public RedisHealthController(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @GetMapping("/ping")
    public Map<String, Object> ping() {
        String key = "smoke-test:" + Instant.now().toEpochMilli();
        String value = "hello-redis";

        redis.opsForValue().set(key, value, Duration.ofSeconds(30));
        String roundTripped = redis.opsForValue().get(key);
        Boolean exists = redis.hasKey(key);
        Long ttlSeconds = redis.getExpire(key);

        return Map.of(
                "key", key,
                "writtenValue", value,
                "readValue", roundTripped == null ? "<null>" : roundTripped,
                "exists", exists != null && exists,
                "ttlSeconds", ttlSeconds == null ? -1L : ttlSeconds
        );
    }
}