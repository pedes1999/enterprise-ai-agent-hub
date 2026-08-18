package com.enterprisehub.gateway.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One row per triggered agent run (security patch, cross-stack alignment,
 * etc). status transitions QUEUED -> RUNNING -> SUCCEEDED|FAILED, driven by
 * AgentJobWorker's DB-backed poll loop (SELECT ... FOR UPDATE SKIP LOCKED
 * against this table) rather than a message broker -- durable and safe
 * under multiple workers without new infrastructure; see
 * V5__agent_execution_queue.sql for the reasoning and the RLS carve-out
 * that lets the worker find jobs across every tenant.
 *
 * prompt/reply/toolWasUsed back the current prompt-plus-tools flow
 * (AgentPromptRunner); repository_url is the original Week 1 column for a
 * future repository-driven agent, now genuinely used by the
 * InputSourceResolver-driven prompt-assembly path (see AgentPromptRunner);
 * input_parameters is that same path's source-specific parameters, JSON-
 * serialized (see V9); trigger_source is still a fixed placeholder value.
 */
@Entity
@Table(name = "agent_executions")
@Getter
@Setter
@NoArgsConstructor
public class AgentExecution {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /**
     * Which app_user queued this execution -- null for rows created before
     * V23, or if that user has since been removed from the tenant (ON
     * DELETE SET NULL). AgentJobWorker runs asynchronously with no HTTP
     * principal available, so this is how it knows whose vendor credential
     * to resolve (see AgentPromptRunner.resolveApiKey()) now that
     * credentials are per-user, not per-tenant -- see V22.
     */
    @Column(name = "triggered_by")
    private UUID triggeredBy;

    /**
     * Non-null only for a child execution created by the delegate_to_agent
     * tool (see DelegateToAgentTool) -- the execution id of whoever
     * delegated this one. Null for everything triggered directly via
     * POST /agents/execute. See V25__agent_execution_parent_and_planner.sql.
     */
    @Column(name = "parent_execution_id")
    private UUID parentExecutionId;

    // Repurposed from its original Week 1 meaning (a category like
    // SECURITY_PATCH) -- now holds the resolved AgentDefinition.slug this
    // execution ran with (e.g. "coding-agent"), set by
    // AgentExecutionService.enqueue(). See V6__agent_definitions.sql.
    @Column(name = "agent_type", nullable = false)
    private String agentType;

    @Column(name = "trigger_source", nullable = false)
    private String triggerSource; // CI_CD, WEBHOOK, CLI, DASHBOARD

    @Column(name = "repository_url")
    private String repositoryUrl;

    /** Optional, paired with repositoryUrl -- null means "clone the repository's default branch", see V17. */
    @Column(name = "repository_branch")
    private String repositoryBranch;

    /** Optional per-execution override of the token budget -- null means "use the tenant's default, or the server's if the tenant has none", see V21 and AgentPromptRunner. */
    @Column(name = "max_tokens_override")
    private Integer maxTokensOverride;

    // JSON-serialized Map<String, String> -- see V9's rationale. Null for
    // every execution whose AgentDefinition has no input_source_type.
    @Column(name = "input_parameters", columnDefinition = "TEXT")
    private String inputParameters;

    @Column(nullable = false)
    private String status = "QUEUED";

    @Column(name = "llm_provider")
    private String llmProvider;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String prompt;

    @Column(columnDefinition = "TEXT")
    private String reply;

    @Column(name = "tool_was_used")
    private Boolean toolWasUsed;

    // Null means "no usage data available" (pre-V19 row, or every provider
    // response in this execution omitted TokenUsage), not "zero tokens" --
    // see ToolCallingChatEngine's accumulation, which preserves that
    // distinction the same way.
    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Column(name = "output_tokens")
    private Integer outputTokens;

    @Column(name = "total_tokens")
    private Integer totalTokens;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    /**
     * Liveness stamp refreshed by whichever app instance currently owns this
     * RUNNING execution -- see ExecutionHeartbeatMonitor and
     * V32__agent_execution_heartbeat.sql. Null for rows that have never been
     * claimed (QUEUED), for terminal rows, and for rows that were already
     * RUNNING before V32; the reaper falls back to startedAt/createdAt in
     * those cases rather than treating null as "fresh".
     */
    @Column(name = "last_heartbeat_at")
    private Instant lastHeartbeatAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
