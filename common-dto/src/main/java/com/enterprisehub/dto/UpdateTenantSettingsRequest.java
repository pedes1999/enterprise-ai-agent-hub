package com.enterprisehub.dto;

/**
 * A full replace, not a partial patch -- callers always send both fields
 * together (matching every other PUT in this API). null or blank
 * preferredLlmProvider clears the provider override, falling back to the
 * server-wide app.llm.provider default. null or blank preferredModelName
 * clears the model override the same way, independent of the provider.
 */
public record UpdateTenantSettingsRequest(
        String preferredLlmProvider,
        String preferredModelName
) {
}
