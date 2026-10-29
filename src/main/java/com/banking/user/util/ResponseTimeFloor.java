package com.banking.user.util;

import com.banking.user.config.RegistrationProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Makes the registration response take about the same time for every email, so the response
 * time cannot be used to find out whether an email is already registered.
 */
@Component
public class ResponseTimeFloor {

    private final RegistrationProperties properties;

    public ResponseTimeFloor(RegistrationProperties properties) {
        this.properties = properties;
    }

    public void await(long startNanos) {
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;
        long target = properties.minResponseMillis() + ThreadLocalRandom.current().nextLong(0, 60);
        long sleep = target - elapsedMillis;
        if (sleep > 0) {
            try {
                Thread.sleep(sleep);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }
}