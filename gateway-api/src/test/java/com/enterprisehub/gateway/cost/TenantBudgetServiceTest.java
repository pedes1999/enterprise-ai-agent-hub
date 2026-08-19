package com.enterprisehub.gateway.cost;

import com.enterprisehub.dto.TenantSpendSummary;
import com.enterprisehub.gateway.entity.Tenant;
import com.enterprisehub.gateway.repository.AgentExecutionRepository;
import com.enterprisehub.gateway.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TenantBudgetServiceTest {

    private AgentExecutionRepository executionRepository;
    private TenantRepository tenantRepository;
    private TenantBudgetService service;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        executionRepository = mock(AgentExecutionRepository.class);
        tenantRepository = mock(TenantRepository.class);
        service = new TenantBudgetService(executionRepository, tenantRepository);
        when(executionRepository.spendByAgentSince(any(), any())).thenReturn(List.of());
    }

    private void tenantWithBudget(String budget) {
        Tenant tenant = new Tenant();
        tenant.setMonthlyBudgetUsd(budget == null ? null : new BigDecimal(budget));
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
    }

    private void spent(String amount) {
        when(executionRepository.sumCostSince(eq(tenantId), any()))
                .thenReturn(amount == null ? null : new BigDecimal(amount));
    }

    @Test
    void tenantWithNoBudgetIsNeverBlocked() {
        // The default for every existing tenant -- V35 must not retroactively
        // cut anyone off.
        tenantWithBudget(null);
        spent("9999.00");

        assertThatCode(() -> service.requireWithinBudget(tenantId)).doesNotThrowAnyException();
    }

    @Test
    void spendBelowBudgetIsAllowed() {
        tenantWithBudget("50.00");
        spent("12.34");

        assertThatCode(() -> service.requireWithinBudget(tenantId)).doesNotThrowAnyException();
    }

    @Test
    void spendAtOrAboveBudgetIsRejectedWith402() {
        tenantWithBudget("50.00");
        spent("50.00");

        assertThatThrownBy(() -> service.requireWithinBudget(tenantId))
                .isInstanceOf(BudgetExceededException.class)
                // 402, not the 429 the concurrency cap uses: retrying cannot
                // help until the period rolls over or an admin raises it.
                .hasFieldOrPropertyWithValue("status", org.springframework.http.HttpStatus.PAYMENT_REQUIRED)
                .hasMessageContaining("$50.00");
    }

    @Test
    void zeroBudgetFreezesTheTenantCompletely() {
        // Zero is a real setting distinct from null -- "spend nothing".
        tenantWithBudget("0");
        spent(null);

        assertThatThrownBy(() -> service.requireWithinBudget(tenantId))
                .isInstanceOf(BudgetExceededException.class);
    }

    @Test
    void noCompletedRunsReadsAsZeroSpendNotAsNull() {
        // SUM() over no rows is SQL NULL; a tenant who has run nothing really
        // has spent nothing, so this one is honestly coalesced.
        tenantWithBudget("25.00");
        spent(null);

        assertThat(service.spendThisPeriod(tenantId)).isEqualByComparingTo("0");
    }

    @Test
    void summaryReportsRemainingAndPercentAgainstTheBudget() {
        tenantWithBudget("40.00");
        spent("10.00");

        TenantSpendSummary summary = service.summarize(tenantId);

        assertThat(summary.spendUsd()).isEqualByComparingTo("10.00");
        assertThat(summary.budgetUsd()).isEqualByComparingTo("40.00");
        assertThat(summary.remainingUsd()).isEqualByComparingTo("30.00");
        assertThat(summary.percentUsed()).isEqualTo(25.0);
    }

    @Test
    void summaryWithoutABudgetLeavesRemainingAndPercentNull() {
        // Null budget means unlimited; there is nothing to be a percentage of,
        // and reporting 0% would imply a ceiling that does not exist.
        tenantWithBudget(null);
        spent("7.00");

        TenantSpendSummary summary = service.summarize(tenantId);

        assertThat(summary.budgetUsd()).isNull();
        assertThat(summary.remainingUsd()).isNull();
        assertThat(summary.percentUsed()).isNull();
        assertThat(summary.spendUsd()).isEqualByComparingTo("7.00");
    }

    @Test
    void overshootReportsZeroRemainingRatherThanANegativeAllowance() {
        // Reachable by design: runs already in flight when the budget is
        // crossed are never killed (see TenantBudgetService's javadoc), so
        // actual spend can exceed the ceiling.
        tenantWithBudget("10.00");
        spent("13.50");

        TenantSpendSummary summary = service.summarize(tenantId);

        assertThat(summary.remainingUsd()).isEqualByComparingTo("0");
        assertThat(summary.percentUsed()).isEqualTo(135.0);
    }

    @Test
    void summarySurfacesUnpricedExecutionsSoAPartialTotalIsNotReadAsComplete() {
        tenantWithBudget("100.00");
        spent("4.00");
        when(executionRepository.countUnpricedSince(eq(tenantId), any())).thenReturn(900L);

        TenantSpendSummary summary = service.summarize(tenantId);

        // $4.00 of $100 looks comfortable until you see that 900 runs went
        // unpriced -- the figure is a lower bound, and the report says so.
        assertThat(summary.spendUsd()).isEqualByComparingTo("4.00");
        assertThat(summary.unpricedExecutions()).isEqualTo(900L);
    }

    @Test
    void periodStartIsTheFirstInstantOfTheCurrentUtcMonth() {
        Instant periodStart = service.currentPeriodStart();

        assertThat(periodStart).isBefore(Instant.now());
        assertThat(periodStart.toString()).endsWith("-01T00:00:00Z");
    }
}
