package com.payment.paymentsystem.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "processed_events")
public class ProcessedEvent {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "event_key", nullable = false, updatable = false)
    private String eventKey;

    @Column(name = "event_type", nullable = false, updatable = false)
    private String eventType;

    @Column(name = "processed_at", nullable = false, updatable = false)
    private OffsetDateTime processedAt;

    public ProcessedEvent(String eventKey, String eventType) {
        this.id = UUID.randomUUID();
        this.eventKey = eventKey;
        this.eventType = eventType;
        this.processedAt = OffsetDateTime.now();
    }

    public ProcessedEvent() {

    }

    public UUID getId() { return id; }
    public String getEventKey() { return eventKey; }
    public String getEventType() { return eventType; }
    public OffsetDateTime getProcessedAt() { return processedAt; }
}
