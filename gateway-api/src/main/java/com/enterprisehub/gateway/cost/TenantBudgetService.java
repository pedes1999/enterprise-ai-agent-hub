package com.enterprisehub.gateway.cost;

import com.enterprisehub.dto.AgentSpendBreakdown;
import com.enterprisehub.dto.TenantSpendSummary;
import com.enterprisehub.gateway.entity.Tenant;
import com.enterprisehub.gateway.repository.AgentExecutionRepository;
import com.enterprisehub.gateway.repository.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The spend half of cost governance: what a tenant has spent this month, and
 * whether they are allowed to start another run.
 *
 * Two deliberate design choices worth stating plainly, because both are
 * trade-offs rather than obviously-correct answers:
 *
 *  1. THE BUDGET IS CHECKED AT ENQUEUE, NOT ENFORCED MID-RUN. An execution
 *     already RUNNING is never killed for crossing the ceiling. Killing it
 *     would waste everything already spent on that run and can leave a
 *     cloned repository half-modified with no pull request to show for it --
 *     the worst of both worlds. So actual spend can overshoot the budget by
 *     at most the cost of whatever was in flight when it was crossed. That
 *     is a real limitation, not a bug, and it is why this is a budget rather
 *     than a hard cap.
 *
 *  2. THE PERIOD IS THE UTC CALENDAR MONTH. Not a rolling 30 days, and not
 *     the tenant's local month. A calendar month is what a vendor invoice
 *     uses, so a tenant can reconcile this figure against their Anthropic
 *     bill; a rolling window would drift out of alignment with every
 *     statement they receive. UTC because tenants have no timezone field --
 *     inventing one here would be a schema change in service of a detail
 *     nobody has asked for.
 */
@Service
public class TenantBudgetService {

    private static final Logger log = LoggerFactory.getLogger(TenantBudgetService.class);

    private final AgentExecutionRepository executionRepository;
    private final TenantRepository tenantRepository;

    public TenantBudgetService(AgentExecutionRepository executionRepository, TenantRepository tenantRepository) {
        this.executionRepository = executionRepository;
        this.tenantRepository = tenantRepository;
    }

    /** First instant of the current UTC calendar month -- see the class javadoc on why. */
    public Instant currentPeriodStart() {
        return YearMonth.now(ZoneOffset.UTC).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /**
     * Total priced spend for this tenant so far this month. Never null:
     * SUM() over no rows is SQL NULL, coalesced to zero here because a
     * tenant with no completed runs genuinely has spent nothing. Read it
     * alongside {@link #unpricedExecutionsThisPeriod} before treating it as
     * a complete figure.
     */
    public BigDecimal spendThisPeriod(UUID tenantId) {
        BigDecimal spend = executionRepository.sumCostSince(tenantId, currentPeriodStart());
        return spend == null ? BigDecimal.ZERO : spend;
    }

    public long unpricedExecutionsThisPeriod(UUID tenantId) {
        return executionRepository.countUnpricedSince(tenantId, currentPeriodStart());
    }

    /**
     * Rejects the run when this tenant has already spent their monthly
     * budget. Called from AgentExecutionService.enqueue(), so it covers
     * every entry point at once -- the API, the webhook, and delegated
     * sub-agent runs -- rather than each caller remembering to ask.
     *
     * 402 PAYMENT_REQUIRED rather than the 429 the concurrency cap uses, and
     * the distinction is for the caller's benefit: 429 means "try again in a
     * moment" and is true of a concurrency slot, which frees itself. A spent
     * budget does not free itself -- retrying will fail identically until
     * the month rolls over or an admin raises the ceiling. Answering 429
     * there would invite exactly the retry loop that cannot ever succeed,
     * and GitHub's webhook redelivery would happily supply it.
     */
    public void requireWithinBudget(UUID tenantId) {
        BigDecimal budget = tenantRepository.findById(tenantId)
                .map(Tenant::getMonthlyBudgetUsd)
                .orElse(null);
        if (budget == null) {
            // No ceiling configured -- the default. Nothing to enforce.
            return;
        }

        BigDecimal spend = spendThisPeriod(tenantId);
        if (spend.compareTo(budget) < 0) {
            return;
        }

        // WARN, not DEBUG: a tenant hitting their ceiling is a product
        // signal (their limit may genuinely need raising), the same posture
        // the concurrency cap takes.
        log.warn("Tenant {} rejected at the monthly budget (spent {} USD of {} USD this period)", tenantId, spend, budget);
        throw new BudgetExceededException(
                "This tenant has spent $" + spend.setScale(2, RoundingMode.HALF_UP)
                        + " of its $" + budget.setScale(2, RoundingMode.HALF_UP)
                        + " monthly budget. Raise the budget in tenant settings or wait for the next billing period.");
    }

    /** Backs GET /agents/executions/spend. */
    public TenantSpendSummary summarize(UUID tenantId) {
        Instant periodStart = currentPeriodStart();
        BigDecimal spend = spendThisPeriod(tenantId);
        BigDecimal budget = tenantRepository.findById(tenantId).map(Tenant::getMonthlyBudgetUsd).orElse(null);

        BigDecimal remaining = null;
        Double percentUsed = null;
        if (budget != null) {
            // max(0, budget - spend): a tenant that overshot (see the class
            // javadoc on in-flight runs) has zero left, not a negative
            // allowance, which would render as "-$3.00 remaining".
            remaining = budget.subtract(spend).max(BigDecimal.ZERO);
            // A zero budget is a legitimate "spend nothing" setting, so
            // guard the division rather than letting it throw. Any spend at
            // all against a zero budget is 100% used.
            percentUsed = budget.signum() == 0
                    ? (spend.signum() == 0 ? 0.0 : 100.0)
                    : spend.multiply(BigDecimal.valueOf(100))
                        .divide(budget, 2, RoundingMode.HALF_UP)
                        .doubleValue();
        }

        return new TenantSpendSummary(periodStart, spend, budget, remaining, percentUsed,
                executionRepository.countUnpricedSince(tenantId, periodStart),
                breakdown(tenantId, periodStart));
    }

    private List<AgentSpendBreakdown> breakdown(UUID tenantId, Instant periodStart) {
        List<AgentSpendBreakdown> rows = new ArrayList<>();
        for (Object[] row : executionRepository.spendByAgentSince(tenantId, periodStart)) {
            // Nulls preserved rather than coalesced: an agent whose runs were
            // all unpriced has unknown cost, and saying $0.00 would rank it
            // bottom of a list it might top.
            rows.add(new AgentSpendBreakdown(
                    (String) row[0],
                    ((Number) row[1]).longValue(),
                    (BigDecimal) row[2],
                    row[3] == null ? null : ((Number) row[3]).longValue()));
        }
        return rows;
    }
}
