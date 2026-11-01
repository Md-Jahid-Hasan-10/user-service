package com.banking.user.messaging;

public class EventCryptoException extends RuntimeException {

    public EventCryptoException(String message, Throwable cause) {
        super(message, cause);
    }

    public EventCryptoException(String message) {
        super(message);
    }
}