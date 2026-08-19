package com.enterprisehub.dto;

/**
 * agentSlug is which agent a delivery on this endpoint triggers.
 *
 * runAsUserId is optional: omitted, the endpoint runs as the ADMIN creating
 * it. It exists so an admin can wire a webhook to run under a service
 * account's vendor credential instead of their own personal key -- the
 * target user must belong to the same tenant, which
 * WebhookEndpointService verifies rather than trusting the caller.
 *
 * There is deliberately no secret field: the secret is GENERATED server-side
 * and returned exactly once in {@link WebhookEndpointCreatedResponse}, so a
 * weak or reused caller-chosen value can't undermine signature verification.
 */
public record CreateWebhookEndpointRequest(
        String agentSlug,
        String label,
        String runAsUserId
) {
}
