package com.enterprisehub.runtime.audit;

import java.time.Duration;

/** errorMessage is null on SUCCESS. */
public record ToolExecutionAuditRecord(
        String tenantId,
        String executionId,
        String toolName,
        Duration duration,
        ToolExecutionOutcome outcome,
        String errorMessage) {
}
