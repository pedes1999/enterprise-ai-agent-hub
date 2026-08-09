package com.enterprisehub.dto;

public record LoginRequest(
        String tenantSlug,
        String email,
        String password
) {
}
