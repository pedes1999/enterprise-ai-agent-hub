package com.enterprisehub.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * GET /agents/executions/{id} and each row of the paginated GET
 * /agents/executions list. reply/toolWasUsed are null until status is
 * SUCCEEDED; errorMessage is null unless status is FAILED.
 * repositoryUrl/repositoryBranch/inputParameters mirror whatever was
 * supplied on the original TriggerAgentExecutionRequest --
 * repositoryBranch is null both when no branch was given (default branch
 * used) and whenever repositoryUrl itself is null.
 */
public record AgentExecutionStatusResponse(
        UUID id,
        String status,
        String llmProvider,
        String agentSlug,
        String prompt,
        String repositoryUrl,
        String repositoryBranch,
        Map<String, String> inputParameters,
        String reply,
        Boolean toolWasUsed,
        String errorMessage,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt
) {
}
