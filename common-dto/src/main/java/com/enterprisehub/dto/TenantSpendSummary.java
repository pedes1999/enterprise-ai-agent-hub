package com.enterprisehub.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Backs GET /agents/executions/spend -- what this tenant has spent this
 * billing period, against what they are allowed to spend.
 *
 * Field contracts, because several of them are deliberately nullable:
 *
 *  - spendUsd is never null. Zero here genuinely means "nothing priced has
 *    completed yet", which is only honest alongside unpricedExecutions.
 *  - budgetUsd is null when the tenant has no ceiling configured, which is
 *    the default. Null means unlimited, not zero.
 *  - remainingUsd and percentUsed are null exactly when budgetUsd is --
 *    there is nothing to be a percentage of.
 *  - unpricedExecutions is the honesty counter: completed runs in this
 *    window whose cost could not be determined (no pricing row for their
 *    model). A non-zero value means spendUsd is a LOWER BOUND, not a total,
 *    and the frontend is expected to say so rather than present a partial
 *    figure as complete.
 */
public record TenantSpendSummary(
        Instant periodStart,
        BigDecimal spendUsd,
        BigDecimal budgetUsd,
        BigDecimal remainingUsd,
        Double percentUsed,
        long unpricedExecutions,
        List<AgentSpendBreakdown> byAgent
) {
}
