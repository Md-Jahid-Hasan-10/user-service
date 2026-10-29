package com.banking.user.email;

public interface EmailDispatcher {

    /** Sends an email without making the caller wait. Failures are logged, never thrown. */
    void send(String to, String subject, String message);
}