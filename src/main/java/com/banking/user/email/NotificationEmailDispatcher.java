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

    private final RestClient restClient;

    public NotificationEmailDispatcher(@Qualifier("notificationRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    @Async("emailExecutor")
    public void send(String to, String subject, String message) {
        try {
            restClient.post()
                    .uri("/internal/notifications/email")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("to", to, "subject", subject, "message", message))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception ex) {
            // The address is not logged on purpose. Phase K2 adds retries and a dead-letter topic.
            log.error("Could not hand an email to the notification service: {}", ex.getMessage());
        }
    }
}