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
 *
 * inputTokens/outputTokens/totalTokens are null until status is SUCCEEDED
 * or FAILED (an execution that never reached the model, or whose provider
 * response carried no usage data, reports null here too) -- see
 * ToolCallingChatEngine.ToolChatResult's javadoc for why null and 0 mean
 * different things.
 *
 * maxTokensOverride mirrors whatever was supplied on the original
 * TriggerAgentExecutionRequest.maxTokens -- null means this run used the
 * tenant's (or server's) default budget instead of its own override, same
 * shape as repositoryBranch above.
 *
 * parentExecutionId is non-null only for a child execution the
 * delegate_to_agent tool queued on behalf of another, already-running
 * execution -- null for everything triggered directly via POST
 * /agents/execute. See GET /agents/executions/{id}/children for the
 * reverse direction (a parent's own list of what it delegated).
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
        Instant completedAt,
        Integer inputTokens,
        Integer outputTokens,
        Integer totalTokens,
        Integer maxTokensOverride,
        UUID parentExecutionId
) {
}
