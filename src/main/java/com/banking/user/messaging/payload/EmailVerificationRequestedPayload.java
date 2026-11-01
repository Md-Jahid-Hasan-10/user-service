package com.banking.user.messaging.payload;

import java.time.Instant;

public record EmailVerificationRequestedPayload(
        Long userId,
        String email,
        String fullName,
        String encryptedToken,
        Instant expiresAt
) {}