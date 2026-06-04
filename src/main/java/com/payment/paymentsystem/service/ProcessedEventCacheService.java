package com.payment.paymentsystem.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class ProcessedEventCacheService {
    private static final Logger log = LoggerFactory.getLogger(ProcessedEventCacheService.class);
    private static final String KEY_PREFIX = "processed:event:";
    private static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;

    public ProcessedEventCacheService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }


    public boolean isProcessed(String eventKey, String eventType){
        String key = buildKey(eventKey, eventType);
        try{
            Boolean exists = redisTemplate.hasKey(key);
            return Boolean.TRUE.equals(exists);
        }catch (Exception ex){
            log.warn("Cache check failed for event {}:{} — falling through to DB. Error: {}",
                    eventType, eventKey, ex.getMessage());
            return false;
        }
    }


    public void markProcessed(String eventKey, String eventType){
        String key = buildKey(eventKey, eventType);
        try{
            redisTemplate.opsForValue().set(key, "1", TTL);
        }catch (Exception ex){
            log.warn("Cache write failed for event {}:{} — DB record stands. Error: {}",
                    eventType, eventKey, ex.getMessage());
        }
    }


    public void evict(String eventKey, String eventType) {
        try {
            String key = buildKey(eventKey, eventType);
            redisTemplate.delete(key);
        } catch (Exception ex) {
            log.warn("Failed to evict processed event cache: {}", ex.getMessage());
        }
    }



    private String buildKey(String eventKey, String eventType) {
        return KEY_PREFIX + eventType + ":" + eventKey;
    }

}
