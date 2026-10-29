package com.banking.user.throttle;

import com.banking.user.config.RegistrationProperties;
import com.banking.user.util.Hashing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Stops the registration form from being used to flood one person's inbox. */
@Component
public class EmailRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(EmailRateLimiter.class);

    private final StringRedisTemplate redis;
    private final RegistrationProperties properties;

    public EmailRateLimiter(StringRedisTemplate redis, RegistrationProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    /** Returns true when one more email may be sent to this address in the current hour. */
    public boolean tryAcquire(String email) {
        try {
            // Hashed, so no email address is stored in Redis.
            String key = "reg-mail:" + Hashing.sha256Hex(email);
            redis.opsForValue().setIfAbsent(key, "0", Duration.ofHours(1));
            Long count = redis.opsForValue().increment(key);
            return count == null || count <= properties.maxEmailsPerAddressPerHour();
        } catch (RuntimeException ex) {
            // Fail open: a Redis problem must not stop registration. It is logged for the operators.
            log.error("Email rate limiter unavailable, allowing the email: {}", ex.getMessage());
            return true;
        }
    }
}