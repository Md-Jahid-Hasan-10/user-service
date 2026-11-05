package com.banking.user.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.event.Level;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Records every event a service hands to Kafka. Services with an outbox only save it there ("outbox"),
 * the relay sends it a moment later. Services without an outbox send at once ("direct").
 */
@Aspect
@Component
public class EventPublishAspect {

    private static final int MAX_FIELD_LENGTH = 100;
    private static final int MAX_ERROR_LENGTH = 300;

    private final PayloadSanitizer sanitizer;
    private final TrafficLog traffic;
    private final MeterRegistry meters;
    private final BoundedTags tags;

    public EventPublishAspect(PayloadSanitizer sanitizer, TrafficLog traffic, MeterRegistry meters, BoundedTags tags) {
        this.sanitizer = sanitizer;
        this.traffic = traffic;
        this.meters = meters;
        this.tags = tags;
    }

    // publish(topic, key, eventType, correlationId, causationId, payload)
    @Around("execution(* com.banking..messaging.*EventPublisher.publish(..))")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        Object[] args = pjp.getArgs();
        String stage = stageOf(pjp.getTarget());
        Throwable failure = null;
        try {
            return pjp.proceed();
        } catch (Throwable ex) {
            failure = ex;
            throw ex;
        } finally {
            report(args, stage, failure);
        }
    }

    private void report(Object[] args, String stage, Throwable failure) {
        try {
            String topic = text(args, 0);
            String key = text(args, 1);
            String eventType = text(args, 2);
            String outcome = failure == null ? "ok" : "error";

            Counter.builder("kafka.published")
                    .tag("topic", tags.limit("topic", topic))
                    .tag("eventType", tags.limit("eventType", eventType))
                    .tag("stage", stage)
                    .tag("outcome", outcome)
                    .tag("exception", failure == null ? "none" : tags.limit("exception", failure.getClass().getSimpleName()))
                    .register(meters)
                    .increment();

            Level level = failure == null ? Level.INFO : Level.WARN;
            if (!traffic.enabled(level)) {
                return;
            }

            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("event", "kafka.publish");
            fields.put("stage", stage);
            fields.put("topic", topic);
            fields.put("messageKey", limit(key, MAX_FIELD_LENGTH));
            fields.put("eventType", eventType);
            fields.put("correlationId", limit(text(args, 3), MAX_FIELD_LENGTH));
            fields.put("outcome", outcome);
            fields.put("transferId", PayloadSanitizer.isTransferId(key) ? key : null);
            fields.put("payload", sanitizer.describe(args.length > 5 ? args[5] : null));
            if (failure != null) {
                fields.put("error", failure.getClass().getSimpleName() + ": "
                        + limit(failure.getMessage(), MAX_ERROR_LENGTH));
            }

            String verb = failure != null ? "could not be handed to Kafka"
                    : "outbox".equals(stage) ? "saved in the outbox" : "sent to Kafka";
            traffic.log(level, "Event " + eventType + " " + verb + " (topic " + topic + ")", fields);
        } catch (RuntimeException ex) {
            // Recording must never change whether an event is published.
        }
    }

    private String stageOf(Object target) {
        return target != null && target.getClass().getSimpleName().contains("Outbox") ? "outbox" : "direct";
    }

    private String text(Object[] args, int index) {
        return args.length > index && args[index] != null ? String.valueOf(args[index]) : null;
    }

    private String limit(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
