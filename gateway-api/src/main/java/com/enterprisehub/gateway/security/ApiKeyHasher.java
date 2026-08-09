package com.enterprisehub.gateway.security;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Platform API keys can't use BCrypt like passwords: lookup happens by exact
 * hash match (findByKeyHash), which requires a deterministic digest, not a
 * salted one-way hash where the same input produces different output every
 * time. SHA-256 is fine here specifically because the input is a
 * high-entropy random value the app generates itself, not a low-entropy
 * user-chosen secret — offline brute-force of a 256-bit random key is
 * infeasible regardless of hash speed, unlike passwords.
 */
@Component
public class ApiKeyHasher {

    private static final String KEY_PREFIX = "ahk_";
    private static final int RAW_KEY_BYTES = 32;

    private final SecureRandom secureRandom = new SecureRandom();

    public String generateRawKey() {
        byte[] bytes = new byte[RAW_KEY_BYTES];
        secureRandom.nextBytes(bytes);
        return KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String hash(String rawKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(rawKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
