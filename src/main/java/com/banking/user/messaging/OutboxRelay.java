package com.banking.user.messaging;   // change the package for each service

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository repository;
    private final KafkaTemplate<String, String> kafka;
    private final Tracer tracer;
    private final Propagator propagator;
    private final Counter published;
    private final Counter failures;

    public OutboxRelay(OutboxRepository repository,
                       KafkaTemplate<String, String> kafka,
                       MeterRegistry registry,
                       Tracer tracer,
                       Propagator propagator) {
        this.repository = repository;
        this.kafka = kafka;
        this.tracer = tracer;
        this.propagator = propagator;

        this.published = Counter.builder("outbox.events.published")
                .description("Events sent from the outbox to Kafka")
                .register(registry);
        this.failures = Counter.builder("outbox.publish.failures")
                .description("Failed attempts to send an outbox event to Kafka")
                .register(registry);
        Gauge.builder("outbox.pending.events", repository, OutboxRepository::countByPublishedAtIsNull)
                .description("Events saved in the outbox but not yet published")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${app.messaging.relay-interval-ms:1000}")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> batch = repository.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc();

        for (OutboxEvent event : batch) {
            // The span continues the trace of the request or message that created this event.
            Span span = startSpan(event);
            try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
                kafka.send(event.getTopic(), event.getMessageKey(), event.getEnvelope())
                        .get(10, TimeUnit.SECONDS);
                event.markPublished(Instant.now());
                published.increment();
            } catch (InterruptedException ex) {
                span.error(ex);
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException | TimeoutException ex) {
                span.error(ex);
                String reason = reasonOf(ex);
                event.recordFailure(reason.length() > 500 ? reason.substring(0, 500) : reason);
                failures.increment();
                log.warn("Outbox: could not publish {} {} to {}: {}",
                        event.getEventType(), event.getId(), event.getTopic(), reason);
                // Stop here so a later event never overtakes this one.
                return;
            } finally {
                span.end();
            }
        }
    }

    private Span startSpan(OutboxEvent event) {
        Span span;
        if (event.getTraceParent() == null) {
            span = tracer.nextSpan().name("outbox publish").start();
        } else {
            Map<String, String> carrier = Map.of("traceparent", event.getTraceParent());
            span = propagator.extract(carrier, Map::get).name("outbox publish").start();
        }
        span.tag("eventType", event.getEventType());
        span.tag("topic", event.getTopic());
        String key = event.getMessageKey();
        if (key != null && key.startsWith("TXN-") && key.length() <= 60) {
            span.tag("transferId", key);
        }
        return span;
    }

    @Scheduled(cron = "0 15 3 * * *")
    @Transactional
    public void deleteOldPublishedEvents() {
        int deleted = repository.deletePublishedBefore(Instant.now().minus(7, ChronoUnit.DAYS));
        if (deleted > 0) {
            log.info("Outbox: removed {} published events older than 7 days", deleted);
        }
    }

    private String reasonOf(Exception ex) {
        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}