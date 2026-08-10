package com.enterprisehub.dto;

import java.util.UUID;

/** Returned immediately by POST /agents/execute -- the job is QUEUED, not run yet. */
public record AgentExecutionAccepted(
        UUID executionId,
        String status
) {
}
