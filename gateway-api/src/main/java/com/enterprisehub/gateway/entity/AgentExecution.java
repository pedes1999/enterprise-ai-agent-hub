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
 * (AgentPromptRunner); repository_url/agent_type/trigger_source are the
 * original Week 1 columns for a future repository-driven agent and are
 * either unused or given a fixed placeholder value by that flow today.
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

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
