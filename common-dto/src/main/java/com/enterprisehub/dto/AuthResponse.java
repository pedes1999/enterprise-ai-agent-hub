package com.enterprisehub.dto;

public record AuthResponse(
        String token,
        long expiresInSeconds,
        String tenantId,
        String tenantSlug,
        String userId,
        String email,
        String role,
        /** True until an admin-invited user sets their own password -- see UserService.create() / AuthService.changePassword(). */
        boolean mustChangePassword
) {
}
