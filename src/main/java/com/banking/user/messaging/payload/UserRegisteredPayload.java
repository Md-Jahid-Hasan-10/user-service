package com.banking.user.messaging.payload;

public record UserRegisteredPayload(Long userId, String email, String fullName) {}