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


    public boolean tryClaim(String eventKey, String eventType){
        if(processedEventCacheService.isProcessed(eventKey, eventType)){
            log.info("Event {}:{} already processed (cache hit) — skipping", eventType, eventKey);
            return false;
        }

        boolean inserted = insertOrSkip(eventKey, eventType);
        if(inserted){
            processedEventCacheService.markProcessed(eventKey, eventType);
        }else{
            processedEventCacheService.markProcessed(eventKey, eventType);
        }

        return inserted;
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
