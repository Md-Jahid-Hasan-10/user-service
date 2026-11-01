package com.banking.user.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.messaging")
public record MessagingProperties(String producerName, String cryptoKey) {}