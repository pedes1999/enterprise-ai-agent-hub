package com.enterprisehub.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * GET /agents/executions/{id}. reply/toolWasUsed are null until status is
 * SUCCEEDED; errorMessage is null unless status is FAILED.
 */
public record AgentExecutionStatusResponse(
        UUID id,
        String status,
        String llmProvider,
        String agentSlug,
        String prompt,
        String reply,
        Boolean toolWasUsed,
        String errorMessage,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt
) {
}
