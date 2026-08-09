package com.enterprisehub.dto;

public record AuthResponse(
        String token,
        long expiresInSeconds,
        String tenantId,
        String tenantSlug,
        String userId,
        String email,
        String role
) {
}
