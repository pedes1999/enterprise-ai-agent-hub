package com.enterprisehub.dto;

import java.time.Instant;

/**
 * The listable view of a webhook endpoint. Carries no secret -- not even the
 * ciphertext -- because nothing outside signature verification ever needs it.
 */
public record WebhookEndpointSummary(
        String id,
        String agentSlug,
        String label,
        String eventType,
        String runAsUserId,
        String deliveryUrl,
        Instant createdAt
) {
}
