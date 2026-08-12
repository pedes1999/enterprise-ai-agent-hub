package com.enterprisehub.dto;

/** null or blank preferredLlmProvider clears the override -- agent executions fall back to the server-wide app.llm.provider default. */
public record UpdateTenantSettingsRequest(
        String preferredLlmProvider
) {
}
