package com.enterprisehub.dto;

import java.time.Instant;

/**
 * Deliberately has no token/ciphertext field -- unlike an API key's rawKey,
 * a vendor credential is never shown back to the caller even once, since
 * the caller already has it (they typed it in); the only thing worth
 * confirming is that it's stored and active.
 */
public record VendorCredentialSummary(
        String id,
        String provider,
        boolean active,
        Instant createdAt,
        Instant updatedAt
) {
}
