package com.enterprisehub.gateway.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One row per AgentTool.execute() call, success or failure -- written by
 * JpaToolExecutionListener, called from agent-runtime's
 * AbstractSandboxedTool on every sandboxed tool invocation.
 *
 * executionId is NOT (yet) an FK to agent_executions(id) -- see
 * V3__tool_executions.sql for why.
 */
@Entity
@Table(name = "tool_executions")
@Getter
@Setter
@NoArgsConstructor
public class ToolExecution {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "execution_id", nullable = false)
    private String executionId;

    @Column(name = "tool_name", nullable = false)
    private String toolName;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    @Column(nullable = false)
    private String outcome; // SUCCESS, FAILURE

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
