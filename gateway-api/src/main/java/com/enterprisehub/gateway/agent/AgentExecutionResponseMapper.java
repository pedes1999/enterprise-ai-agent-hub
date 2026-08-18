package com.enterprisehub.gateway.agent;

import com.enterprisehub.dto.AgentExecutionStatusResponse;
import com.enterprisehub.gateway.entity.AgentExecution;
import org.springframework.stereotype.Component;

/**
 * One place that turns an AgentExecution row into its API representation.
 * Extracted when ExecutionStreamService needed the identical mapping the
 * controller already had privately -- two copies of a 7-line positional
 * constructor call over a 20-field record is exactly the shape that drifts
 * (a field added to one and forgotten in the other shows up as a silently
 * wrong value in the streamed copy but not the polled one, or vice versa).
 */
@Component
public class AgentExecutionResponseMapper {

    private final AgentExecutionService executionService;

    public AgentExecutionResponseMapper(AgentExecutionService executionService) {
        this.executionService = executionService;
    }

    public AgentExecutionStatusResponse toResponse(AgentExecution execution) {
        return new AgentExecutionStatusResponse(
                execution.getId(), execution.getStatus(), execution.getLlmProvider(), execution.getAgentType(), execution.getPrompt(),
                execution.getRepositoryUrl(), execution.getRepositoryBranch(), executionService.deserializeInputParameters(execution),
                execution.getReply(), execution.getToolWasUsed(), execution.getErrorMessage(),
                execution.getCreatedAt(), execution.getStartedAt(), execution.getCompletedAt(),
                execution.getInputTokens(), execution.getOutputTokens(), execution.getTotalTokens(),
                execution.getMaxTokensOverride(), execution.getParentExecutionId(), execution.getCancellationRequestedAt());
    }
}
