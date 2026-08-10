package com.enterprisehub.gateway.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Stores encrypted credentials sandboxed tools need at execution time (e.g.
 * a git PAT for GitCloneTool) -- separate from VendorCredential (LLM
 * provider API keys), same encryption mechanism (CredentialEncryptor).
 *
 * RLS-scoped by tenant_id, FORCE ROW LEVEL SECURITY set from creation (see
 * V4__tool_credentials.sql).
 */
@Entity
@Table(name = "tool_credentials")
@Getter
@Setter
@NoArgsConstructor
public class ToolCredential {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "credential_kind", nullable = false)
    private String credentialKind; // GIT, ...

    @Column(name = "encrypted_value", nullable = false, columnDefinition = "TEXT")
    private String encryptedValue;

    @Column(name = "encryption_key_id", nullable = false)
    private String encryptionKeyId;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
