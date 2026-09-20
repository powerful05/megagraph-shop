package com.megagraph.shop.payment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.megagraph.shop.projection.OutboxEvent;
import com.megagraph.shop.projection.OutboxEventRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.ObjectProvider;

@Component
public class CommerceOutboxWriter {

    private final OutboxEventRepository events;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<OutboxFaultInjector> faultInjector;

    public CommerceOutboxWriter(
            OutboxEventRepository events,
            ObjectMapper objectMapper,
            ObjectProvider<OutboxFaultInjector> faultInjector
    ) {
        this.events = events;
        this.objectMapper = objectMapper;
        this.faultInjector = faultInjector;
    }

    public void save(
            String aggregateType,
            Long aggregateId,
            Long aggregateVersion,
            String eventType,
            Map<String, ?> details
    ) {
        try {
            OutboxFaultInjector injector = faultInjector.getIfAvailable();
            if (injector != null) {
                injector.beforeSave(eventType);
            }
            Map<String, Object> payload = new LinkedHashMap<>(details);
            payload.putIfAbsent("occurredAt", Instant.now());
            events.save(new OutboxEvent(
                    aggregateType,
                    aggregateId,
                    aggregateVersion,
                    eventType,
                    objectMapper.writeValueAsString(payload)
            ));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize commerce outbox payload", exception);
        }
    }
}
