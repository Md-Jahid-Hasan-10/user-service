package com.banking.user.messaging;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class OutboxRelay {

    /**
     * Longer than max.block.ms + delivery.timeout.ms, so the wait here does
     * not end before the broker gave up on its own. Otherwise a publish can
     * be reported as failed while the message is still on its way, and the
     * retry sends it a second time.
     */
    private static final long PUBLISH_TIMEOUT_SECONDS = 30;

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository repository;
    private final KafkaTemplate<String, String> kafka;
    private final Counter published;
    private final Counter failures;

    public OutboxRelay(OutboxRepository repository,
                       KafkaTemplate<String, String> kafka,
                       MeterRegistry registry) {
        this.repository = repository;
        this.kafka = kafka;

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
            try {
                kafka.send(event.getTopic(), event.getMessageKey(), event.getEnvelope())
                        // Longer than max.block.ms + delivery.timeout.ms, so the wait
                        // does not end before the broker gave up on its own.
                        .get(PUBLISH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                event.markPublished(Instant.now());
                published.increment();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException | TimeoutException ex) {
                String reason = reasonOf(ex);
                event.recordFailure(reason.length() > 500 ? reason.substring(0, 500) : reason);
                failures.increment();
                log.warn("Outbox: could not publish {} {} to {}: {}",
                        event.getEventType(), event.getId(), event.getTopic(), reason);
                // Stop here so a later event never overtakes this one.
                return;
            }
        }
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