package com.enterprisehub.dto;

import java.util.List;

/**
 * preferredLlmProvider/preferredModelName/maxTokensPerExecution are null
 * when the tenant has no override -- see TenantLlmProviderResolver for the
 * server-default fallback. effectiveMaxTokensPerExecution is never null:
 * it's whatever TenantLlmProviderResolver.resolveMaxTokens() actually
 * resolves to (this tenant's override, or the server-wide default when
 * maxTokensPerExecution itself is null) -- lets the frontend show a
 * concrete number ("Default: 500,000") instead of vague placeholder text.
 */
public record TenantSettingsResponse(
        String preferredLlmProvider,
        String preferredModelName,
        Integer maxTokensPerExecution,
        Integer effectiveMaxTokensPerExecution,
        List<LlmProviderAvailability> availableProviders
) {
}
