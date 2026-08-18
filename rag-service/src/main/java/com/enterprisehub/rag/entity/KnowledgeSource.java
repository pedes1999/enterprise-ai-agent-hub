package com.enterprisehub.rag.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One tenant's ingested corpus (e.g. "Internal API docs") -- a container for
 * DocumentChunk rows, not a document itself. Plain tenant_id column, no base
 * class, matching every other tenant-scoped entity (see VendorCredential,
 * ToolCredential) -- isolation is enforced by RLS at the DB, not by any JPA
 * mechanism here.
 */
@Entity
@Table(name = "knowledge_source")
@Getter
@Setter
@NoArgsConstructor
public class KnowledgeSource {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String name;

    /** upload | url | repo -- only 'upload' is implemented today, see IngestionService. */
    @Column(name = "source_type", nullable = false)
    private String sourceType;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
