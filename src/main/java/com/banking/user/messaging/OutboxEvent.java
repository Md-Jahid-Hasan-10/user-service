package com.banking.user.messaging;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox_events", indexes = {
        @Index(name = "idx_outbox_pending", columnList = "publishedAt, createdAt")
})
public class OutboxEvent {

    @Id
    private UUID id;

    @Column(nullable = false, length = 100)
    private String topic;

    @Column(nullable = false, length = 100)
    private String messageKey;

    @Column(nullable = false, length = 100)
    private String eventType;

    @Column(nullable = false, columnDefinition = "text")
    private String envelope;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant publishedAt;

    @Column(nullable = false)
    private int attempts;

    @Column(length = 500)
    private String lastError;
    @Column(length = 100)
    private String traceParent;

    public String getTraceParent() { return traceParent; }
    public void setTraceParent(String traceParent) { this.traceParent = traceParent; }
    
    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public static OutboxEvent of(UUID id, String topic, String messageKey, String eventType, String envelope) {
        OutboxEvent event = new OutboxEvent();
        event.id = id;
        event.topic = topic;
        event.messageKey = messageKey;
        event.eventType = eventType;
        event.envelope = envelope;
        return event;
    }

    public void markPublished(Instant at) {
        this.publishedAt = at;
        this.lastError = null;
    }

    public void recordFailure(String error) {
        this.attempts++;
        this.lastError = error;
    }

    public UUID getId() { return id; }
    public String getTopic() { return topic; }
    public String getMessageKey() { return messageKey; }
    public String getEventType() { return eventType; }
    public String getEnvelope() { return envelope; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getPublishedAt() { return publishedAt; }
    public int getAttempts() { return attempts; }
    public String getLastError() { return lastError; }
}