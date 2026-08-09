package com.enterprisehub.dto;

/**
 * rawKey is populated ONLY on the response to the create call — it is never
 * persisted or retrievable again after this. Every other read of an API key
 * (list, etc) must use {@link ApiKeySummary} instead.
 */
public record ApiKeyCreatedResponse(
        String id,
        String label,
        String rawKey
) {
}
