package com.payment.paymentsystem.repository;

import com.payment.paymentsystem.entity.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {
    Optional<ProcessedEvent> findByEventKeyAndEventType(String eventKey, String eventType);

}
