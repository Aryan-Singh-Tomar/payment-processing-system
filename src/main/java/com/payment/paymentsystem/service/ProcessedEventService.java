package com.payment.paymentsystem.service;

import com.payment.paymentsystem.entity.ProcessedEvent;
import com.payment.paymentsystem.repository.ProcessedEventRepository;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import org.slf4j.Logger;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProcessedEventService {

    private static final Logger log = LoggerFactory.getLogger(ProcessedEventService.class);

    private final ProcessedEventCacheService processedEventCacheService;
    private final ProcessedEventRepository processedEventRepository;

    public ProcessedEventService(ProcessedEventRepository processedEventRepository, ProcessedEventCacheService processedEventCacheService){
        this.processedEventRepository = processedEventRepository;
        this.processedEventCacheService = processedEventCacheService;
    }


    /**
     * Read-only check: has this event been processed successfully before?
     * Used at the START of consume() — if true, skip processing entirely.
     */
    public boolean isProcessed(String eventKey, String eventType) {
        // Fast path: cache check
        if (processedEventCacheService.isProcessed(eventKey, eventType)) {
            log.info("Event {}:{} already processed (cache hit)", eventType, eventKey);
            return true;
        }

        // Slow path: DB check
        boolean inDb = processedEventRepository.findByEventKeyAndEventType(eventKey, eventType).isPresent();
        if (inDb) {
            log.info("Event {}:{} already processed (DB check)", eventType, eventKey);
            // Repopulate cache for next time
            processedEventCacheService.markProcessed(eventKey, eventType);
            return true;
        }
        return false;
    }


    /**
     * Mark this event as successfully processed. Used at the END of consume()
     * after all work is complete. If the row already exists (very unlikely but
     * possible under race), we treat it as success — the event IS processed.
     */
    public void markProcessed(String eventKey, String eventType) {
        try {
            insertOrSkip(eventKey, eventType);
            processedEventCacheService.markProcessed(eventKey, eventType);
        } catch (Exception ex) {
            // Even if both the DB insert and cache write failed, we don't
            // re-throw. The work has been done; failing to record the
            // processed-marker would just cause a future duplicate that
            // the idempotent business logic would catch anyway.
            log.warn("Failed to mark event {}:{} as processed. Future duplicates " +
                            "will rely on payment.status check. Error: {}",
                    eventType, eventKey, ex.getMessage());
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean insertOrSkip(String eventKey, String eventType){
        try{
            ProcessedEvent event = new ProcessedEvent(eventKey, eventType);
            processedEventRepository.saveAndFlush(event);
            return true;
        }catch (DataIntegrityViolationException ex){
            log.info("Event {}:{} already claimed (DB constraint hit) — skipping",
                    eventType, eventKey);
            return false;
        }
    }
}
