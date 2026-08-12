package com.enterprisehub.dto;

import java.time.Instant;

/**
 * No value/token field, same reasoning as VendorCredentialSummary -- never
 * shown back, not even once. lastUsedAt is stamped whenever this credential
 * is actually decrypted for a real tool execution; lastValidatedAt only by
 * an explicit POST /tool-credentials/test call -- both null until that's
 * happened at least once.
 */
public record ToolCredentialSummary(
        String id,
        String credentialKind,
        boolean active,
        Instant createdAt,
        Instant updatedAt,
        Instant lastUsedAt,
        Instant lastValidatedAt
) {
}
