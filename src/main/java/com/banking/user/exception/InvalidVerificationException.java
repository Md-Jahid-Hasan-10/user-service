package com.banking.user.exception;

public class InvalidVerificationException extends RuntimeException {
    public InvalidVerificationException(String message) {
        super(message);
    }
}