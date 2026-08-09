package com.enterprisehub.dto;

public record RegisterRequest(
        String tenantName,
        String tenantSlug,
        String email,
        String password
) {
}
