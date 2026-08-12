package com.enterprisehub.core.llm;

import java.util.Arrays;
import java.util.Optional;

/**
 * Mirrors gateway-api's VendorProvider by name (ANTHROPIC/OPENAI/GEMINI) but
 * is defined here separately rather than shared -- agent-core has no
 * dependency on gateway-api, only the reverse, so the two enums are kept in
 * sync by convention (same 3 provider names) rather than by a shared type.
 */
public enum LlmProvider {
    ANTHROPIC,
    OPENAI,
    GEMINI,
    /** Any OpenAI-API-compatible server running on the caller's own machine (Ollama, LM Studio, vLLM, etc.) -- see LlmEngineFactory. */
    LOCAL;

    public static Optional<LlmProvider> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(provider -> provider.name().equalsIgnoreCase(value))
                .findFirst();
    }
}
