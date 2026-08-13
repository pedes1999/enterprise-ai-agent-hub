package com.enterprisehub.gateway.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Stores encrypted vendor API tokens (Anthropic / OpenAI / Gemini) only.
 * encryptedToken is ciphertext produced via envelope encryption — the
 * actual data key never lives in this table or in application memory
 * longer than the single decrypt operation requires. See Step 4 of the
 * architecture plan (KMS-backed envelope encryption) for the encrypt/decrypt
 * service that reads/writes this entity.
 *
 * RLS-scoped by tenant_id — the highest-sensitivity table in the schema,
 * so this is the one where DB-level enforcement matters most. Per-user
 * within a tenant (see V22__vendor_credentials_per_user.sql): each
 * app_user owns their own key per provider, not one shared tenant-wide
 * credential -- scoping to the owning user is an application-query
 * concern (VendorCredentialRepository), not a second RLS policy.
 */
@Entity
@Table(name = "vendor_credentials")
@Getter
@Setter
@NoArgsConstructor
public class VendorCredential {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /** The app_user who owns this key -- see V22__vendor_credentials_per_user.sql. Unique together with tenantId+provider, not just tenantId+provider anymore. */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String provider; // ANTHROPIC, OPENAI, GEMINI, LOCAL

    @Column(name = "encrypted_token", nullable = false, columnDefinition = "TEXT")
    private String encryptedToken;

    @Column(name = "encryption_key_id", nullable = false)
    private String encryptionKeyId;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    // Both nullable -- see V11__credential_health_timestamps.sql for the
    // distinction between "actually used" and "explicitly validated".
    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "last_validated_at")
    private Instant lastValidatedAt;
}
