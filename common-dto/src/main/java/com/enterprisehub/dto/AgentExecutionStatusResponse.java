package com.enterprisehub.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * GET /agents/executions/{id} and each row of the paginated GET
 * /agents/executions list. reply/toolWasUsed are null until status is
 * SUCCEEDED; errorMessage is null unless status is FAILED.
 * repositoryUrl/inputParameters mirror whatever was supplied on the
 * original TriggerAgentExecutionRequest -- both can be null/empty for an
 * agent with no inputSourceType configured.
 */
public record AgentExecutionStatusResponse(
        UUID id,
        String status,
        String llmProvider,
        String agentSlug,
        String prompt,
        String repositoryUrl,
        Map<String, String> inputParameters,
        String reply,
        Boolean toolWasUsed,
        String errorMessage,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt
) {
}
