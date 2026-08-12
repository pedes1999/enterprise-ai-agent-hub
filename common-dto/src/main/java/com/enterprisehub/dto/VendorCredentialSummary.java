package com.enterprisehub.dto;

import java.time.Instant;

/**
 * Deliberately has no token/ciphertext field -- unlike an API key's rawKey,
 * a vendor credential is never shown back to the caller even once, since
 * the caller already has it (they typed it in); the only thing worth
 * confirming is that it's stored and active. lastUsedAt is stamped
 * whenever this credential is actually decrypted for a real agent run;
 * lastValidatedAt only by an explicit POST /vendor-credentials/test call --
 * both null until that's happened at least once.
 */
public record VendorCredentialSummary(
        String id,
        String provider,
        boolean active,
        Instant createdAt,
        Instant updatedAt,
        Instant lastUsedAt,
        Instant lastValidatedAt
) {
}
