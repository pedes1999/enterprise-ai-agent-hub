package com.enterprisehub.gateway.security;

import com.enterprisehub.gateway.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Runs after JWT/platform-API-key authentication has populated the
 * SecurityContext, and before the request reaches any @Transactional
 * service method.
 *
 * Deliberately re-resolves the tenant from the authenticated principal on
 * every request rather than trusting a tenant_id embedded directly in a JWT
 * claim — a stale or leaked token should not be able to assert an arbitrary
 * tenant. AuthenticatedPrincipal here is expected to expose the tenant ID
 * that was looked up from the DB during authentication itself.
 *
 * Always clears TenantContext in a finally block — this thread will be
 * reused by the container's thread pool for unrelated requests.
 */
@Component
public class TenantResolvingFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                     @NonNull  HttpServletResponse response,
                                     @NonNull  FilterChain filterChain) throws ServletException, IOException {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof PlatformPrincipal principal) {
                TenantContext.set(principal.tenantId());
            }
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
