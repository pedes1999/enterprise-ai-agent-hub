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

    /**
     * Overrides the tenant's own resolved model name (see
     * TenantLlmProviderResolver.resolveModelName()) for executions of this
     * definition -- null (every existing seeded agent today) means "use
     * whatever the tenant's preference/server default resolves to", same
     * behavior as before this field existed. Lets a simple/cheap agent
     * (e.g. general-assistant) use a cheaper model than a tenant's default
     * without needing a different vendor credential -- only the model
     * name changes, not the provider, so the same per-user credential
     * still resolves. See V27__agent_definition_preferred_model.sql.
     */
    @Column(name = "preferred_model_name")
    private String preferredModelName;

    // Null (the default for every existing row) means this definition takes
    // plain free-text `prompt` only -- no InputSourceResolver runs, no
    // resolved blob gets prepended. Set it (e.g. "MANUAL_TEXT") to have
    // AgentPromptRunner resolve TriggerAgentExecutionRequest.inputParameters
    // through the matching InputSourceResolver before assembling the prompt.
    @Column(name = "input_source_type")
    private String inputSourceType;

    /**
     * Which of a fixed vocabulary this definition needs present on a
     * trigger request before it's allowed to run at all: "prompt",
     * "repositoryUrl", or "inputParameters:{key}" (e.g.
     * "inputParameters:ticketKey") -- see
     * AgentExecutionService.validateRequiredInputs(). Defaults to an empty
     * list (Java-side, mirroring the column's own NOT NULL DEFAULT '{}')
     * so a bare `new AgentDefinition()` in a test is never null here.
     */
    @Column(name = "required_inputs", nullable = false)
    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<String> requiredInputs = List.of();

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
