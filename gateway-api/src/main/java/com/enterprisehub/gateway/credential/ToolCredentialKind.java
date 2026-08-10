package com.enterprisehub.gateway.credential;

import java.util.Arrays;
import java.util.Optional;

/** Mirrors agent-runtime's expectations of which credentialKind strings a CredentialResolver may be asked for. */
public enum ToolCredentialKind {
    GIT;

    public static Optional<ToolCredentialKind> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(kind -> kind.name().equalsIgnoreCase(value))
                .findFirst();
    }
}
