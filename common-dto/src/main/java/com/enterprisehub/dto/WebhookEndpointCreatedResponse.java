package com.enterprisehub.dto;

/**
 * secret is populated ONLY on the response to the create call -- the stored
 * copy is encrypted and never returned again, same show-once principle as
 * {@link ApiKeyCreatedResponse#rawKey()}. Every other read of an endpoint
 * uses {@link WebhookEndpointSummary}, which has no secret field at all.
 *
 * deliveryUrl is the full path to paste into GitHub's webhook settings,
 * assembled here so the caller doesn't have to know the route's shape.
 */
public record WebhookEndpointCreatedResponse(
        String id,
        String agentSlug,
        String label,
        String deliveryUrl,
        String secret
) {
}
