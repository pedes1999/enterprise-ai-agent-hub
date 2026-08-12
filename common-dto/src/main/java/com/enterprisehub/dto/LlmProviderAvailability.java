package com.enterprisehub.dto;

/** One row per known provider (ANTHROPIC/OPENAI/GEMINI/LOCAL) -- lets the UI grey out providers the tenant can't actually pick yet. */
public record LlmProviderAvailability(
        String provider,
        boolean hasActiveCredential
) {
}
