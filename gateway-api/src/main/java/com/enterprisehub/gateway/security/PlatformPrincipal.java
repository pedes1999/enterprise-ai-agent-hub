package com.enterprisehub.gateway.security;

/**
 * The authenticated principal attached to the SecurityContext once a JWT or
 * platform API key has been validated. tenantId is looked up from the DB at
 * authentication time (not trusted blindly from a token claim) — see the
 * class-level note on TenantResolvingFilter for why.
 */
public record PlatformPrincipal(String userId, String tenantId, String role) {
}
