package com.enterprisehub.gateway.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One entry in the platform-wide agent catalog -- a named, reusable
 * combination of a system prompt and a curated subset of tool names (see
 * ToolCatalog). Not tenant-scoped: every tenant triggers from the same
 * shared catalog (see V6__agent_definitions.sql for why). Added via
 * migration today, not a self-service admin API yet.
 */
@Entity
@Table(name = "agent_definitions")
@Getter
@Setter
@NoArgsConstructor
public class AgentDefinition {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, unique = true)
    private String slug;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(name = "system_prompt", nullable = false, columnDefinition = "TEXT")
    private String systemPrompt;

    @Column(name = "tool_names", nullable = false)
    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<String> toolNames;

    @Column(name = "llm_provider", nullable = false)
    private String llmProvider = "ANTHROPIC";

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
