package com.enterprisehub.dto;

import java.math.BigDecimal;

/**
 * One agent's share of a tenant's spend in the reporting window -- the
 * "which agent is costing us the money" view a budget warning immediately
 * prompts.
 *
 * costUsd is null when every execution of this agent in the window was
 * unpriced (see AgentExecution.costUsd). Null is not zero: an agent running
 * entirely on a model with no price on file has unknown cost, not free cost,
 * and rendering it as $0.00 would rank it last in a list it might well top.
 */
public record AgentSpendBreakdown(
        String agentSlug,
        long executionCount,
        BigDecimal costUsd,
        Long totalTokens
) {
}
