package com.banking.user.security;

import com.banking.user.util.Hashing;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

@Component
public class VerificationTokens {

    private final SecureRandom random = new SecureRandom();

    /** 256 bits of randomness, safe to put in a URL. */
    public String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String hash(String token) {
        return Hashing.sha256Hex(token);
    }
}