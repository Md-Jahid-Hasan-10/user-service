package com.banking.user.messaging;

import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class EventCrypto {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public EventCrypto(MessagingProperties properties) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(properties.cryptoKey());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("EVENT_CRYPTO_KEY is not valid Base64", ex);
        }
        if (raw.length != 32) {
            throw new IllegalStateException("EVENT_CRYPTO_KEY must be 32 bytes (use: openssl rand -base64 32)");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    /** Returns Base64( iv + ciphertext + tag ). */
    public String encrypt(String plaintext, String aad) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            ByteBuffer buffer = ByteBuffer.allocate(iv.length + encrypted.length);
            buffer.put(iv).put(encrypted);
            return Base64.getEncoder().encodeToString(buffer.array());
        } catch (GeneralSecurityException ex) {
            throw new EventCryptoException("Could not encrypt the value", ex);
        }
    }

    public String decrypt(String encoded, String aad) {
        try {
            byte[] all = Base64.getDecoder().decode(encoded);
            if (all.length <= IV_BYTES) {
                throw new EventCryptoException("Encrypted value is too short");
            }

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            byte[] plain = cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException | GeneralSecurityException ex) {
            throw new EventCryptoException("Could not decrypt the value", ex);
        }
    }
}