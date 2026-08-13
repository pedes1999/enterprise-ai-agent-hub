package com.enterprisehub.gateway.security;

import com.enterprisehub.gateway.error.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Runs right after JwtAuthFilter has populated the SecurityContext.
 * An admin-invited user's JWT carries mustChangePassword=true (see
 * JwtService) until they set their own password -- this filter locks them
 * out of every endpoint except POST /auth/change-password until that
 * happens, so the "you must change your temporary password" rule can't be
 * bypassed by a client that simply ignores it. Rejects at the filter level
 * (before DispatcherServlet/@PreAuthorize) so it can't be missed on a new
 * endpoint the way per-controller checks could be.
 *
 * Deliberately whitelist-based (not a hardcoded single path) so /auth/login,
 * /auth/register, /actuator/health, and /webhooks/** — already publicly
 * reachable per SecurityConfig's permitAll — stay reachable for this
 * caller too, even though none of them need an authenticated principal in
 * the first place.
 */
@Component
public class PasswordChangeRequiredFilter extends OncePerRequestFilter {

    private final ObjectMapper objectMapper;

    public PasswordChangeRequiredFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                     @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof PlatformPrincipal principal
                && principal.mustChangePassword() && !isAllowedWhileChangeRequired(request)) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getWriter(),
                    new ApiError("You must change your temporary password before continuing -- POST /auth/change-password"));
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean isAllowedWhileChangeRequired(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/auth/") || path.equals("/actuator/health") || path.startsWith("/webhooks/");
    }
}
