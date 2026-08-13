package com.enterprisehub.dto;

/**
 * Backs GET /agents/executions/token-usage-stats -- lets the trigger form
 * show "past runs of this agent used ~X-Y tokens" instead of a caller
 * having to guess a maxTokens override with no reference point. Scoped to
 * this tenant's own past executions of one agentSlug (RLS-filtered, same
 * as every other query here), and only over rows that actually recorded
 * usage (see AgentExecution.totalTokens' javadoc on null vs zero) --
 * sampleCount is that filtered count, not this agent's total execution
 * count. sampleCount == 0 means no past execution of this agent has
 * recorded usage yet -- minTokens/avgTokens/maxTokens are all null in that
 * case, not zero.
 */
public record AgentTokenUsageStats(
        String agentSlug,
        long sampleCount,
        Integer minTokens,
        Double avgTokens,
        Integer maxTokens
) {
}
