package com.enterprisehub.gateway.credential;

import java.util.Arrays;
import java.util.Optional;

public enum VendorProvider {
    ANTHROPIC,
    OPENAI,
    GEMINI;

    public static Optional<VendorProvider> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(provider -> provider.name().equalsIgnoreCase(value))
                .findFirst();
    }
}
