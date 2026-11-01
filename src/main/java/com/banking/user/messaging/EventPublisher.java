package com.banking.user.messaging;

public interface EventPublisher {

    /**
     * Saves an event in the outbox. Must be called inside the transaction that changes the data,
     * so the change and the event are saved together or not at all.
     */
    void publish(String topic, String key, String eventType, String correlationId,
                 String causationId, Object payload);
}