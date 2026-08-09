package com.enterprisehub.gateway.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Issued to a tenant for CI/CD pipelines, webhooks, and CLI auth.
 * Only keyHash is persisted — the raw key is shown to the user exactly
 * once at creation time and never stored or logged in plaintext again,
 * same principle as password storage.
 */
@Entity
@Table(name = "platform_api_keys")
@Getter
@Setter
@NoArgsConstructor
public class PlatformApiKey {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "key_hash", nullable = false, unique = true)
    private String keyHash;

    private String label;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
