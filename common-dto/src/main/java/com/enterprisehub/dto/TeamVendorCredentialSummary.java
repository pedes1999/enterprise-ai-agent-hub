package com.enterprisehub.dto;

import java.time.Instant;

/**
 * Backs GET /vendor-credentials/team (ADMIN-only) -- one row per active
 * teammate credential, across every user in the tenant, so an admin can see
 * who has what connected without being able to see, edit, or use the key
 * itself. Same "no secret ever comes back" discipline as
 * VendorCredentialSummary, plus userEmail/userId to identify the owner
 * since this spans multiple users instead of just the caller.
 */
public record TeamVendorCredentialSummary(
        String userId,
        String userEmail,
        String provider,
        boolean active,
        Instant lastUsedAt,
        Instant lastValidatedAt
) {
}
