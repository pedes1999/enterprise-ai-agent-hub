package com.enterprisehub.gateway.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One wiring of "GitHub events arriving at this URL" to "run this agent".
 * The row's own {@code id} is the URL path segment
 * (POST /webhooks/github/{endpointId}), which makes it the only tenant
 * discriminator a webhook request carries -- there is no JWT and no API key
 * on that route. See V34's SELECT policy for why looking this row up is
 * allowed before any tenant context exists, and why that is safe.
 *
 * secretCiphertext is ENCRYPTED rather than hashed, unlike
 * {@link PlatformApiKey#getKeyHash()}: verifying a GitHub delivery means
 * recomputing an HMAC over the request body with the shared secret, which
 * needs the plaintext back. Same envelope-encryption shape as
 * {@link VendorCredential} -- ciphertext plus the id of the key that
 * produced it (see com.enterprisehub.gateway.security.EncryptedCredential).
 *
 * runAsUserId is non-null by schema constraint, not by convention: vendor
 * credentials are per-user with no tenant fallback, and
 * AgentPromptRunner.resolveApiKey() rejects a null user outright, so an
 * endpoint without one could only ever produce failing executions.
 */
@Entity
@Table(name = "webhook_endpoints")
@Getter
@Setter
@NoArgsConstructor
public class WebhookEndpoint {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /** Which agent a delivery on this endpoint triggers -- an agent_definitions.slug. */
    @Column(name = "agent_slug", nullable = false)
    private String agentSlug;

    /** The app_user whose vendor credential pays for runs triggered here. */
    @Column(name = "run_as_user_id", nullable = false)
    private UUID runAsUserId;

    @Column(name = "secret_ciphertext", nullable = false, columnDefinition = "TEXT")
    private String secretCiphertext;

    @Column(name = "secret_key_id", nullable = false)
    private String secretKeyId;

    /** Currently always "pull_request" -- see V34 on why this is single-valued for now. */
    @Column(name = "event_type", nullable = false)
    private String eventType = "pull_request";

    private String label;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
