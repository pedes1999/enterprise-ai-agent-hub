package com.enterprisehub.dto;

import java.time.Instant;

public record ApiKeySummary(
        String id,
        String label,
        Instant lastUsedAt,
        Instant revokedAt,
        Instant createdAt
) {
}
