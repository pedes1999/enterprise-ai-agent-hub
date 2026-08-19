package com.enterprisehub.gateway.cost;

import com.enterprisehub.gateway.entity.ModelPricing;
import com.enterprisehub.gateway.repository.ModelPricingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The arithmetic is easy to get subtly wrong in ways nobody notices until an
 * invoice disagrees, so these tests pin the exact figures rather than
 * asserting "greater than zero".
 *
 * The unpriced cases matter at least as much as the priced ones: every one
 * of them must yield a NULL cost, never BigDecimal.ZERO, because those two
 * are indistinguishable once summed and only one of them is true.
 */
class ExecutionCostCalculatorTest {

    private ModelPricingRepository pricingRepository;
    private ExecutionCostCalculator calculator;
    private final Instant now = Instant.parse("2026-08-19T12:00:00Z");

    @BeforeEach
    void setUp() {
        pricingRepository = mock(ModelPricingRepository.class);
        calculator = new ExecutionCostCalculator(pricingRepository);
    }

    private void priced(String model, String inputPerMtok, String outputPerMtok) {
        ModelPricing pricing = new ModelPricing();
        pricing.setProvider("ANTHROPIC");
        pricing.setModelName(model);
        pricing.setInputUsdPerMtok(new BigDecimal(inputPerMtok));
        pricing.setOutputUsdPerMtok(new BigDecimal(outputPerMtok));
        when(pricingRepository.findEffectivePrice(eq(model), any())).thenReturn(Optional.of(pricing));
    }

    @Test
    void pricesInputAndOutputSeparatelyAtTheirOwnRates() {
        // Sonnet 4.5 -- the server default. $3/MTok in, $15/MTok out.
        priced("claude-sonnet-4-5-20250929", "3", "15");

        // 1,000,000 in + 100,000 out = $3.00 + $1.50 = $4.50
        ExecutionCostCalculator.ExecutionCost cost =
                calculator.calculate("claude-sonnet-4-5-20250929", 1_000_000, 100_000, now);

        assertThat(cost.isPriced()).isTrue();
        assertThat(cost.costUsd()).isEqualByComparingTo("4.50");
    }

    @Test
    void chargesOutputAtItsHigherRateRatherThanAveragingTheTwo() {
        // The whole reason input and output are separate columns: on every
        // Anthropic model output costs 5x input, so an output-heavy coding
        // run is mispriced badly by any blended rate. Same 200k total tokens,
        // split two ways -- the costs must differ.
        priced("claude-opus-5", "5", "25");

        BigDecimal inputHeavy = calculator.calculate("claude-opus-5", 190_000, 10_000, now).costUsd();
        BigDecimal outputHeavy = calculator.calculate("claude-opus-5", 10_000, 190_000, now).costUsd();

        assertThat(inputHeavy).isEqualByComparingTo("1.20");   // 0.95 + 0.25
        assertThat(outputHeavy).isEqualByComparingTo("4.80");  // 0.05 + 4.75
        assertThat(outputHeavy).isGreaterThan(inputHeavy);
    }

    @Test
    void keepsSubCentPrecisionSoCheapRunsDoNotRoundAwayToNothing() {
        // A small Haiku run costs a fraction of a cent. Rounding each one to
        // the nearest cent would round it to zero, and a tenant doing
        // thousands of them would show no spend at all.
        priced("claude-haiku-4-5-20251001", "1", "5");

        BigDecimal cost = calculator.calculate("claude-haiku-4-5-20251001", 1_000, 200, now).costUsd();

        // 1000/1M * $1 = $0.001 ; 200/1M * $5 = $0.001 => $0.002
        assertThat(cost).isEqualByComparingTo("0.002");
        assertThat(cost.signum()).isPositive();
    }

    @Test
    void unknownModelIsUnpricedAndNeverFree() {
        when(pricingRepository.findEffectivePrice(any(), any())).thenReturn(Optional.empty());

        ExecutionCostCalculator.ExecutionCost cost = calculator.calculate("gpt-4o-mini", 50_000, 5_000, now);

        assertThat(cost.outcome()).isEqualTo(ExecutionCostCalculator.Outcome.NO_PRICE);
        // The assertion this whole class exists for: null, not ZERO. A zero
        // would let a tenant on an unpriced model spend forever inside any
        // budget, because SUM() cannot tell "free" from "unknown".
        assertThat(cost.costUsd()).isNull();
        assertThat(cost.isPriced()).isFalse();
    }

    @Test
    void runThatRecordedNoUsageIsUnpricedRatherThanZero() {
        ExecutionCostCalculator.ExecutionCost cost = calculator.calculate("claude-opus-5", null, null, now);

        assertThat(cost.outcome()).isEqualTo(ExecutionCostCalculator.Outcome.NO_USAGE);
        assertThat(cost.costUsd()).isNull();
    }

    @Test
    void executionWithNoRecordedModelIsUnpriced() {
        // Rows predating V35 have no model_name, so they cannot be costed.
        ExecutionCostCalculator.ExecutionCost cost = calculator.calculate(null, 1_000, 1_000, now);

        assertThat(cost.outcome()).isEqualTo(ExecutionCostCalculator.Outcome.NO_PRICE);
        assertThat(cost.costUsd()).isNull();
    }

    @Test
    void oneSidedUsageCountsTheMissingSideAsZeroNotAsUnknown() {
        // A run that reported input but no output really did use zero output
        // tokens' worth of billing -- distinct from reporting nothing at all.
        priced("claude-opus-5", "5", "25");

        ExecutionCostCalculator.ExecutionCost cost = calculator.calculate("claude-opus-5", 200_000, null, now);

        assertThat(cost.isPriced()).isTrue();
        assertThat(cost.costUsd()).isEqualByComparingTo("1.00");
    }

    @Test
    void pricesAtTheRateInEffectWhenTheRunCompleted() {
        // The point of effective-dated pricing: the calculator asks for the
        // price "at" the completion instant, so a later price change cannot
        // retroactively re-cost this run.
        priced("claude-sonnet-5", "2", "10");
        Instant completedAt = Instant.parse("2026-08-01T09:30:00Z");

        calculator.calculate("claude-sonnet-5", 1_000_000, 0, completedAt);

        org.mockito.Mockito.verify(pricingRepository).findEffectivePrice("claude-sonnet-5", completedAt);
    }
}
