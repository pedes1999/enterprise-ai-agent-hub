package com.enterprisehub.dto;

import java.util.List;

/** preferredLlmProvider is null when the tenant has no override -- see TenantLlmProviderResolver for the server-default fallback. */
public record TenantSettingsResponse(
        String preferredLlmProvider,
        List<LlmProviderAvailability> availableProviders
) {
}
