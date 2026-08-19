package com.enterprisehub.gateway.cost;

import com.enterprisehub.gateway.entity.ModelPricing;
import com.enterprisehub.gateway.repository.ModelPricingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Optional;

/**
 * Prices one execution: (tokens / 1,000,000) x the per-million rate that
 * applied when it ran, input and output charged separately because every
 * vendor charges them separately (output is 5x input on every Anthropic
 * model, so averaging the two would misprice an output-heavy coding run
 * badly).
 *
 * The single rule this class exists to enforce: <b>an unpriced run is never
 * a free run.</b> When a model has no price on file, or a run recorded no
 * token usage, the answer is {@link Outcome#NO_PRICE}/{@link Outcome#NO_USAGE}
 * and the stored cost stays NULL -- not BigDecimal.ZERO. Those two look
 * identical in a SUM(), and that is exactly the failure this whole feature
 * is meant to prevent: a tenant running entirely on an unpriced model would
 * show $0.00 spend forever and sail through every budget check while
 * spending real money. NULL propagates as "unknown" and is reported as
 * unpriced; zero would quietly assert something false.
 *
 * BigDecimal throughout, never double -- see ModelPricing.
 */
@Component
public class ExecutionCostCalculator {

    private static final Logger log = LoggerFactory.getLogger(ExecutionCostCalculator.class);

    private static final BigDecimal TOKENS_PER_MILLION = new BigDecimal("1000000");

    /**
     * Six decimal places, matching agent_executions.cost_usd. Sub-cent
     * precision is not fussiness: a cheap Haiku run genuinely costs a few
     * thousandths of a dollar, and rounding each one to the cent would round
     * most of them to zero and lose the entire month's total for a
     * high-volume tenant. Rounding happens once, at the end, on a figure
     * already summed at full precision.
     */
    private static final int COST_SCALE = 6;

    private final ModelPricingRepository pricingRepository;

    public ExecutionCostCalculator(ModelPricingRepository pricingRepository) {
        this.pricingRepository = pricingRepository;
    }

    public enum Outcome {
        /** Priced successfully -- costUsd is non-null. */
        PRICED,
        /** The run reported no token usage at all, so there is nothing to price. */
        NO_USAGE,
        /** No model_pricing row covers this model at this date. NOT free -- just unknown. */
        NO_PRICE
    }

    /**
     * costUsd is non-null if and only if outcome is {@link Outcome#PRICED}.
     */
    public record ExecutionCost(Outcome outcome, BigDecimal costUsd) {

        public static ExecutionCost priced(BigDecimal costUsd) {
            return new ExecutionCost(Outcome.PRICED, costUsd);
        }

        public static ExecutionCost unpriced(Outcome outcome) {
            return new ExecutionCost(outcome, null);
        }

        public boolean isPriced() {
            return outcome == Outcome.PRICED;
        }
    }

    /**
     * @param modelName    the model that actually ran (agent_executions.model_name)
     * @param inputTokens  may be null -- treated as 0 provided the other side is present
     * @param outputTokens may be null -- likewise
     * @param at           when the run completed; picks the price in effect then
     */
    public ExecutionCost calculate(String modelName, Integer inputTokens, Integer outputTokens, Instant at) {
        // No usage recorded at all. Distinct from a genuine zero-token run
        // (which cannot happen -- a request that reached the model always
        // consumed input tokens), so this really does mean "the provider
        // returned no usage" or "the run never reached the model".
        if (inputTokens == null && outputTokens == null) {
            return ExecutionCost.unpriced(Outcome.NO_USAGE);
        }
        if (modelName == null || modelName.isBlank()) {
            // Rows created before V35 stamped the model, and any run whose
            // model could not be resolved. Unknowable rather than free.
            return ExecutionCost.unpriced(Outcome.NO_PRICE);
        }

        Optional<ModelPricing> pricing = pricingRepository.findEffectivePrice(modelName, at);
        if (pricing.isEmpty()) {
            // Logged at WARN with the model name because this is genuinely
            // actionable -- somebody switched a tenant onto a model nobody
            // priced, and every run on it is invisible to budgets until an
            // operator adds the row. Silence here would mean discovering it
            // from an unexpectedly large vendor invoice.
            log.warn("No pricing on file for model '{}' -- execution recorded as unpriced, NOT as free. "
                    + "Add a model_pricing row so this model's spend counts against tenant budgets.", modelName);
            return ExecutionCost.unpriced(Outcome.NO_PRICE);
        }

        ModelPricing price = pricing.get();
        BigDecimal inputCost = costFor(inputTokens, price.getInputUsdPerMtok());
        BigDecimal outputCost = costFor(outputTokens, price.getOutputUsdPerMtok());

        return ExecutionCost.priced(inputCost.add(outputCost).setScale(COST_SCALE, RoundingMode.HALF_UP));
    }

    /** A null side of a run that reported the other side counts as zero, not as unknown. */
    private BigDecimal costFor(Integer tokens, BigDecimal usdPerMtok) {
        if (tokens == null || tokens <= 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(tokens)
                .multiply(usdPerMtok)
                .divide(TOKENS_PER_MILLION, COST_SCALE + 4, RoundingMode.HALF_UP);
    }
}
