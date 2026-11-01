package com.banking.user.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Component
public class OutboxEventPublisher implements EventPublisher {

    private final OutboxRepository repository;
    private final ObjectMapper objectMapper;
    private final MessagingProperties properties;

    public OutboxEventPublisher(OutboxRepository repository,
                                ObjectMapper objectMapper,
                                MessagingProperties properties) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.properties = properties;
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

        repository.save(OutboxEvent.of(eventId, topic, key, eventType, json));
    }
}