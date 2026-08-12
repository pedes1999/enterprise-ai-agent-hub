package com.enterprisehub.gateway.credential;

import java.util.Arrays;
import java.util.Optional;

public enum VendorProvider {
    ANTHROPIC,
    OPENAI,
    GEMINI,
    /** Any OpenAI-API-compatible server on the tenant's own machine (Ollama, LM Studio, vLLM, etc.) -- see LlmEngineFactory. No real secret is needed; any non-blank placeholder value is accepted as the token. */
    LOCAL;

    public static Optional<VendorProvider> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(provider -> provider.name().equalsIgnoreCase(value))
                .findFirst();
    }
}
