package com.banking.user.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.registration")
public record RegistrationProperties(
        boolean verificationRequired,
        String verifyUrl,
        int tokenTtlHours,
        int minResponseMillis,
        int maxEmailsPerAddressPerHour
) {}