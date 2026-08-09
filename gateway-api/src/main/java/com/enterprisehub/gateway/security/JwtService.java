package com.enterprisehub.gateway.security;

import com.enterprisehub.gateway.config.SecurityProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Optional;

/**
 * Issues and validates the platform's own JWTs (session tokens handed out at
 * login). Distinct from platform API keys — JWTs are short-lived and meant
 * for interactive/dashboard-style callers, API keys are long-lived and meant
 * for CI/CD and webhooks. Claims carry tenantId/role because
 * {@link PlatformPrincipal} needs to be reconstructed from the token alone,
 * without a DB round trip on every request.
 */
@Service
public class JwtService {

    private static final String CLAIM_TENANT_ID = "tenantId";
    private static final String CLAIM_ROLE = "role";

    private final SecretKey signingKey;
    private final long expirationMinutes;

    public JwtService(SecurityProperties securityProperties) {
        this.signingKey = Keys.hmacShaKeyFor(
                securityProperties.jwtSecret().getBytes(StandardCharsets.UTF_8));
        this.expirationMinutes = securityProperties.jwtExpirationMinutes();
    }

    public String issueToken(String userId, String tenantId, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId)
                .claim(CLAIM_TENANT_ID, tenantId)
                .claim(CLAIM_ROLE, role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expirationMinutes, ChronoUnit.MINUTES)))
                .signWith(signingKey)
                .compact();
    }

    public long expirationSeconds() {
        return expirationMinutes * 60;
    }

    /**
     * Returns empty on any validation failure (expired, malformed, bad
     * signature) rather than throwing — callers (the auth filter) treat an
     * invalid token identically to a missing one and fall through to
     * "unauthenticated", never surfacing why parsing failed.
     */
    public Optional<PlatformPrincipal> parseAndValidate(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            String userId = claims.getSubject();
            String tenantId = claims.get(CLAIM_TENANT_ID, String.class);
            String role = claims.get(CLAIM_ROLE, String.class);

            if (userId == null || tenantId == null || role == null) {
                return Optional.empty();
            }
            return Optional.of(new PlatformPrincipal(userId, tenantId, role));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
