package com.banking.user.dto;

public record AuthResponse(
        String token,
        String tokenType,
        long expiresIn,
        Long userId,
        String fullName,
        String email,
        String role
) {}