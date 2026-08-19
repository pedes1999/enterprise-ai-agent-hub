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
 *
 * null monthlyBudgetUsd clears the spend ceiling entirely (unlimited, the
 * default). Zero is NOT the same as null and is a legitimate setting: it
 * means "spend nothing", which is how an admin freezes a tenant's agent runs
 * without deleting their configuration. Negative values are rejected.
 */
public record UpdateTenantSettingsRequest(
        String preferredLlmProvider,
        String preferredModelName,
        Integer maxTokensPerExecution,
        java.math.BigDecimal monthlyBudgetUsd
) {
}
