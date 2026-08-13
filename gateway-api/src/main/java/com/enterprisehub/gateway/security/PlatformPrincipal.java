package com.enterprisehub.gateway.security;

/**
 * The authenticated principal attached to the SecurityContext once a JWT or
 * platform API key has been validated. tenantId is looked up from the DB at
 * authentication time (not trusted blindly from a token claim) — see the
 * class-level note on TenantResolvingFilter for why.
 *
 * mustChangePassword mirrors AppUser.mustChangePassword at the moment the
 * JWT was issued (see JwtService) -- PasswordChangeRequiredFilter reads it
 * to lock an admin-invited user out of everything except
 * POST /auth/change-password until they set their own password. Platform
 * API keys never carry it (there's no interactive login to force a change
 * on), so every call site that isn't JWT-based keeps using the 3-arg
 * constructor, which defaults it to false.
 */
public record PlatformPrincipal(String userId, String tenantId, String role, boolean mustChangePassword) {

    public PlatformPrincipal(String userId, String tenantId, String role) {
        this(userId, tenantId, role, false);
    }
}
