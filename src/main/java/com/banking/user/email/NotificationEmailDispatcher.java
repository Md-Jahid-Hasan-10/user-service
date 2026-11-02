package com.banking.user.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
public class NotificationEmailDispatcher implements EmailDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationEmailDispatcher.class);

    /** How often a failed attempt is repeated, and how long it waits in between. */
    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_WAIT_MILLIS = 1_000L;

    private final RestClient restClient;

    public NotificationEmailDispatcher(@Qualifier("notificationRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    @Async("emailExecutor")
    public void send(String to, String subject, String message) {
        // The address is never logged on purpose.
        RuntimeException last = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                restClient.post()
                        .uri("/internal/notifications/email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("to", to, "subject", subject, "message", message))
                        .retrieve()
                        .toBodilessEntity();
                return;
            } catch (Exception ex) {
                last = ex instanceof RuntimeException runtime
                        ? runtime
                        : new IllegalStateException(ex.getMessage(), ex);

                log.warn("Could not hand an email to the notification service "
                        + "(attempt {} of {}): {}", attempt, MAX_ATTEMPTS, ex.getMessage());

                if (attempt < MAX_ATTEMPTS) {
                    try {
                        Thread.sleep(RETRY_WAIT_MILLIS * attempt);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        log.error("Interrupted while retrying an email to the notification service");
                        return;
                    }
                }
            }
        }

        log.error("Gave up handing an email to the notification service after {} attempts",
                MAX_ATTEMPTS, last);
    }
}
