package com.enterprisehub.gateway.security;

import com.enterprisehub.gateway.config.SecurityProperties;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private static final String SECRET = "test-secret-key-that-is-at-least-32-bytes-long!!";

    private JwtService serviceWithExpiration(long minutes) {
        return new JwtService(new SecurityProperties(SECRET, minutes));
    }

    @Test
    void issueToken_thenParseAndValidate_returnsOriginalPrincipal() {
        JwtService service = serviceWithExpiration(60);
        String userId = UUID.randomUUID().toString();
        String tenantId = UUID.randomUUID().toString();

        String token = service.issueToken(userId, tenantId, "ADMIN");
        Optional<PlatformPrincipal> parsed = service.parseAndValidate(token);

        assertThat(parsed).isPresent();
        assertThat(parsed.get().userId()).isEqualTo(userId);
        assertThat(parsed.get().tenantId()).isEqualTo(tenantId);
        assertThat(parsed.get().role()).isEqualTo("ADMIN");
    }

    @Test
    void expirationSeconds_isMinutesTimesSixty() {
        JwtService service = serviceWithExpiration(60);
        assertThat(service.expirationSeconds()).isEqualTo(3600);
    }

    @Test
    void parseAndValidate_expiredToken_returnsEmpty() {
        JwtService service = serviceWithExpiration(-1); // already expired the instant it's issued
        String token = service.issueToken(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "ADMIN");

        assertThat(service.parseAndValidate(token)).isEmpty();
    }

    @Test
    void parseAndValidate_malformedToken_returnsEmpty() {
        JwtService service = serviceWithExpiration(60);
        assertThat(service.parseAndValidate("not-a-real-jwt")).isEmpty();
    }

    @Test
    void parseAndValidate_wrongSigningKey_returnsEmpty() {
        JwtService issuer = new JwtService(new SecurityProperties(SECRET, 60));
        JwtService verifier = new JwtService(new SecurityProperties("a-completely-different-secret-key-32-bytes-min", 60));

        String token = issuer.issueToken(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "ADMIN");

        assertThat(verifier.parseAndValidate(token)).isEmpty();
    }

    @Test
    void parseAndValidate_blankToken_returnsEmpty() {
        JwtService service = serviceWithExpiration(60);
        assertThat(service.parseAndValidate("")).isEmpty();
    }
}
