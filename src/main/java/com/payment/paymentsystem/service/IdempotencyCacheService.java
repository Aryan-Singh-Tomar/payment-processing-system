package com.payment.paymentsystem.service;

import com.payment.paymentsystem.dto.PaymentResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

@Service
public class IdempotencyCacheService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCacheService.class);

    private final RedisTemplate<String, Object> redisTemplate;
    private final String keyPrefix;
    private final Duration ttl;

    public IdempotencyCacheService(RedisTemplate<String, Object> redisTemplate,
                                   @Value("${app.idempotency.key-prefix}") String keyPrefix,
                                   @Value("${app.idempotency.cache-ttl}") Duration ttl) {
        this.redisTemplate = redisTemplate;
        this.keyPrefix = keyPrefix;
        this.ttl = ttl;
    }

    public Optional<PaymentResponse> get(String idempotencyKey){
        String key = buildKey(idempotencyKey);
        try {
            Object cached = redisTemplate.opsForValue().get(key);
            if(cached instanceof PaymentResponse response){
                log.debug("Cache HIT for idempotencyKey={}", idempotencyKey);
                return Optional.of(response);
            }
            log.debug("Cache MISS for idempotencyKey={}", idempotencyKey);
            return Optional.empty();
        }catch (Exception ex){
            log.warn("Redis GET failed for key={}, falling back to DB. cause={}",
                    idempotencyKey, ex.getMessage());
            return Optional.empty();
        }
    }

    public void put(String idempotencyKey, PaymentResponse response){
        String key = buildKey(idempotencyKey);
        try {
            redisTemplate.opsForValue().set(key, response, ttl);
            log.debug("Cached response for idempotencyKey={}, ttl={}",
                    idempotencyKey, ttl);
        }catch (Exception ex){
            log.warn("Redis SET failed for key={}. cause={}",
                    idempotencyKey, ex.getMessage());
        }
    }

    private String buildKey(String idempotencyKey){
        return keyPrefix + idempotencyKey;
    }
}
