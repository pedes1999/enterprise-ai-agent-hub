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
 * the job orchestrator introduced in Phase 3 (message-queue-backed, not
 * in-process @Async — see architecture discussion on why that's decided
 * up front rather than retrofitted).
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

    @Column(name = "agent_type", nullable = false)
    private String agentType; // SECURITY_PATCH, CROSS_STACK_ALIGNMENT, ...

    @Column(name = "trigger_source", nullable = false)
    private String triggerSource; // CI_CD, WEBHOOK, CLI, DASHBOARD

    @Column(name = "repository_url", nullable = false)
    private String repositoryUrl;

    @Column(nullable = false)
    private String status = "QUEUED";

    @Column(name = "llm_provider")
    private String llmProvider;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
