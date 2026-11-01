package com.banking.user.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/** The shape of every message on Kafka. See docs/event-contract.md in event-bus-service. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventEnvelope(
        String eventId,
        String eventType,
        int version,
        String correlationId,
        String causationId,
        Instant occurredAt,
        String producer,
        JsonNode payload
) {}