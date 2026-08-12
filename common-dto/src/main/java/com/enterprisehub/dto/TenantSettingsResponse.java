package com.enterprisehub.dto;

import java.util.List;

/** preferredLlmProvider/preferredModelName are null when the tenant has no override -- see TenantLlmProviderResolver for the server-default fallback. */
public record TenantSettingsResponse(
        String preferredLlmProvider,
        String preferredModelName,
        List<LlmProviderAvailability> availableProviders
) {
}
