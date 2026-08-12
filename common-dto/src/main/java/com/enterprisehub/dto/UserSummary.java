package com.enterprisehub.dto;

import java.time.Instant;

public record UserSummary(
        String id,
        String email,
        String name,
        String role,
        Instant createdAt
) {
}
