package com.banking.user.messaging;   // change the package for each service

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class OutboxEventPublisher implements EventPublisher {

    private final OutboxRepository repository;
    private final ObjectMapper objectMapper;
    private final MessagingProperties properties;
    private final Tracer tracer;
    private final Propagator propagator;

    public OutboxEventPublisher(OutboxRepository repository,
                                ObjectMapper objectMapper,
                                MessagingProperties properties,
                                Tracer tracer,
                                Propagator propagator) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.tracer = tracer;
        this.propagator = propagator;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(String topic, String key, String eventType, String correlationId,
                        String causationId, Object payload) {
        UUID eventId = UUID.randomUUID();
        JsonNode payloadTree = objectMapper.valueToTree(payload);

        EventEnvelope envelope = new EventEnvelope(
                eventId.toString(), eventType, 1, correlationId, causationId,
                Instant.now(), properties.producerName(), payloadTree);

        String json;
        try {
            json = objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not serialize event " + eventType, ex);
        }

        OutboxEvent event = OutboxEvent.of(eventId, topic, key, eventType, json);
        // The relay sends this event later, from another thread. The trace context travels in the row.
        event.setTraceParent(currentTraceParent());
        repository.save(event);
    }

    private String currentTraceParent() {
        Span span = tracer.currentSpan();
        if (span == null) {
            return null;
        }
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(span.context(), carrier, Map::put);
        return carrier.get("traceparent");
    }
}