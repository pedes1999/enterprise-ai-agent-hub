package com.enterprisehub.gateway.auth;

import java.util.Arrays;
import java.util.Optional;

/**
 * The 3-role model: ADMIN (users, vendor credentials, platform API keys,
 * trigger agents, view history), DEVELOPER (trigger agents, view history),
 * READONLY (view history only). AppUser.role stores this as a plain string
 * (not a JPA @Enumerated column) so the DB and JwtService claim stay
 * simple strings -- this enum exists purely to validate incoming role
 * values server-side before they ever reach the DB or a JWT claim.
 */
public enum Role {
    ADMIN,
    DEVELOPER,
    READONLY;

    public static Optional<Role> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(role -> role.name().equalsIgnoreCase(value))
                .findFirst();
    }
}
