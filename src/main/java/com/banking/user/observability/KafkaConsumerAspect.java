package com.banking.user.observability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.event.Level;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Records every Kafka message a listener handles: metrics always, one sanitized log line when the level allows. */
@Aspect
@Component
public class KafkaConsumerAspect {

    private static final int MAX_FIELD_LENGTH = 100;
    private static final int MAX_ERROR_LENGTH = 300;

    private final ObjectMapper mapper;
    private final PayloadSanitizer sanitizer;
    private final TrafficLog traffic;
    private final MeterRegistry meters;
    private final BoundedTags tags;

    public KafkaConsumerAspect(ObjectMapper mapper, PayloadSanitizer sanitizer, TrafficLog traffic,
                               MeterRegistry meters, BoundedTags tags) {
        this.mapper = mapper;
        this.sanitizer = sanitizer;
        this.traffic = traffic;
        this.meters = meters;
        this.tags = tags;
    }

    @Around("@annotation(org.springframework.kafka.annotation.KafkaListener)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        ConsumerRecord<?, ?> record = recordOf(pjp.getArgs());
        long start = System.nanoTime();
        Throwable failure = null;
        try {
            return pjp.proceed();
        } catch (Throwable ex) {
            failure = ex;
            throw ex;
        } finally {
            report(record, failure, Duration.ofNanos(System.nanoTime() - start));
        }
    }

    private void report(ConsumerRecord<?, ?> record, Throwable failure, Duration elapsed) {
        try {
            if (record == null) {
                return;
            }
            JsonNode envelope = parse(record.value());
            String topic = tags.limit("topic", record.topic());
            String eventType = tags.limit("eventType", text(envelope, "eventType"));
            String outcome = failure == null ? "ok" : "error";
            String exception = failure == null ? "none" : tags.limit("exception", failure.getClass().getSimpleName());

            Timer.builder("kafka.consume")
                    .tag("topic", topic)
                    .tag("eventType", eventType)
                    .tag("outcome", outcome)
                    .tag("exception", exception)
                    .register(meters)
                    .record(elapsed);

            Level level = failure == null ? Level.INFO : Level.WARN;
            if (!traffic.enabled(level)) {
                return;
            }

            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("event", "kafka.consume");
            fields.put("topic", record.topic());
            fields.put("partition", String.valueOf(record.partition()));
            fields.put("offset", String.valueOf(record.offset()));
            fields.put("messageKey", limit(String.valueOf(record.key()), MAX_FIELD_LENGTH));
            fields.put("eventType", eventType);
            fields.put("eventId", limit(text(envelope, "eventId"), MAX_FIELD_LENGTH));
            fields.put("correlationId", limit(text(envelope, "correlationId"), MAX_FIELD_LENGTH));
            fields.put("outcome", outcome);
            fields.put("durationMs", String.valueOf(elapsed.toMillis()));
            fields.put("payload", sanitizer.describe(envelope == null ? null : envelope.get("payload")));
            if (failure != null) {
                fields.put("error", failure.getClass().getSimpleName() + ": "
                        + limit(failure.getMessage(), MAX_ERROR_LENGTH));
            }

            traffic.log(level, "Message " + eventType + " from " + record.topic() + " "
                    + (failure == null ? "handled" : "failed") + " in " + elapsed.toMillis() + " ms", fields);
        } catch (RuntimeException ex) {
            // Recording must never change how a message is handled.
        }
    }

    private ConsumerRecord<?, ?> recordOf(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof ConsumerRecord<?, ?> record) {
                return record;
            }
        }
        return null;
    }

    private JsonNode parse(Object value) {
        if (!(value instanceof String text) || text.isBlank()) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(text);
            return node != null && node.isObject() ? node : null;
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private String limit(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
