package com.enterprisehub.gateway.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Not RLS-scoped to itself: a tenant needs to be readable before
 * app.current_tenant_id can even be set (e.g. during login, before we know
 * who's asking). Every OTHER table in the schema is RLS-scoped by tenant_id
 * referencing this table.
 */
@Entity
@Table(name = "tenants")
@Getter
@Setter
@NoArgsConstructor
public class Tenant {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String slug;

    @Column(nullable = false)
    private String plan = "FREE";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    /** Null means "no override" -- falls back to the server-wide app.llm.provider default. See TenantLlmProviderResolver. */
    @Column(name = "preferred_llm_provider")
    private String preferredLlmProvider;
}
