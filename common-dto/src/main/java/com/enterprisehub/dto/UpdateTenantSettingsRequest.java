package com.enterprisehub.dto;

/**
 * A full replace, not a partial patch -- callers always send every field
 * together (matching every other PUT in this API). null or blank
 * preferredLlmProvider clears the provider override, falling back to the
 * server-wide app.llm.provider default. null or blank preferredModelName
 * clears the model override the same way, independent of the provider.
 * null maxTokensPerExecution clears that override too, falling back to
 * app.llm.max-tokens-per-execution -- a non-null value must be positive,
 * see TenantSettingsService.
 */
public record UpdateTenantSettingsRequest(
        String preferredLlmProvider,
        String preferredModelName,
        Integer maxTokensPerExecution
) {
}
