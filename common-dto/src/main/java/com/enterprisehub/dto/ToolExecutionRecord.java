package com.enterprisehub.dto;

import java.time.Instant;

/**
 * One row of GET /agents/executions/{id}/tool-executions -- the ordered
 * tool-call trace a skeptical teammate opens to verify what an agent
 * actually did. errorMessage is null on SUCCESS.
 */
public record ToolExecutionRecord(
        String toolName,
        long durationMs,
        String outcome,
        String errorMessage,
        Instant createdAt
) {
}
