package com.megagraph.shop.projection;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox_event")
public class OutboxEvent {

    @Id
    private String eventId;
    private String aggregateType;
    private Long aggregateId;
    private Long aggregateVersion;
    private String eventType;
    private String payloadJson;
    private String status;
    private Integer attemptCount;
    private Instant nextAttemptAt;
    private Instant createdAt;
    private Instant publishedAt;

    protected OutboxEvent() {
    }

    public OutboxEvent(
            String aggregateType,
            Long aggregateId,
            Long aggregateVersion,
            String eventType,
            String payloadJson
    ) {
        this.eventId = UUID.randomUUID().toString();
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.aggregateVersion = aggregateVersion;
        this.eventType = eventType;
        this.payloadJson = payloadJson;
        this.status = "PENDING";
        this.attemptCount = 0;
        this.createdAt = Instant.now();
    }

    public String getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public Long getAggregateId() {
        return aggregateId;
    }
}

