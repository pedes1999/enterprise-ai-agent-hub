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
 * How a tenant admin attaches a knowledge source to an AgentDefinition "via
 * config, without writing new code" -- a row here, not a column on
 * agent_definitions itself. agent_definitions is the platform-wide shared
 * catalog (no tenant_id, every tenant triggers the same rows -- see
 * V6__agent_definitions.sql), so the binding has to live on the tenant side
 * of the relationship: this table, tenant-scoped and RLS-protected like
 * everything else in rag-service, is what makes "tenant A's ticket-resolver
 * uses tenant A's knowledge source" true without ever touching tenant B's
 * view of the same shared agent_definitions row. See RetrievalToolFactory
 * for where this gets read at tool-construction time.
 */
@Entity
@Table(name = "agent_knowledge_source_binding")
@Getter
@Setter
@NoArgsConstructor
public class AgentKnowledgeSourceBinding {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "agent_definition_id", nullable = false)
    private UUID agentDefinitionId;

    @Column(name = "knowledge_source_id", nullable = false)
    private UUID knowledgeSourceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
