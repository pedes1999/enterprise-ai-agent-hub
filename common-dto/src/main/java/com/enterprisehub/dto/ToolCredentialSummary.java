package com.enterprisehub.dto;

import java.time.Instant;

/** No value/token field, same reasoning as VendorCredentialSummary -- never shown back, not even once. */
public record ToolCredentialSummary(
        String id,
        String credentialKind,
        boolean active,
        Instant createdAt,
        Instant updatedAt
) {
}
