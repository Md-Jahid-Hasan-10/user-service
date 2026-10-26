package com.banking.user.dto;

public record UserResponse(
        Long id,
        String fullName,
        String email,
        String role,
        String status
) {}