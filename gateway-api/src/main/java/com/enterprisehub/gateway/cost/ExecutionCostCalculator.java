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
 * The mirror of that rule matters just as much: a genuinely free run must not
 * be reported as unknown. Self-hosted inference (LlmProvider.LOCAL -- Ollama
 * and friends) has no vendor invoice, so it is costed at an honest $0.00 and
 * a tenant running entirely on it sees a complete total with nothing flagged,
 * rather than a wall of "unpriced" that implies missing data.
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

    /** LlmProvider.LOCAL -- any OpenAI-compatible server on the operator's own machine. */
    private static final String LOCAL_PROVIDER = "LOCAL";

    private final ModelPricingRepository pricingRepository;

    public ExecutionCostCalculator(ModelPricingRepository pricingRepository) {
        this.pricingRepository = pricingRepository;
    }

    public enum Outcome {
        /** Priced successfully -- costUsd is non-null. */
        PRICED,
        /**
         * A self-hosted model (Ollama, LM Studio, vLLM). Genuinely free, and
         * costUsd is an honest 0.00 rather than null -- there is no vendor to
         * bill, so this is a known cost, not a missing one.
         */
        FREE_SELF_HOSTED,
        /** The run reported no token usage at all, so there is nothing to price. */
        NO_USAGE,
        /** No model_pricing row covers this model at this date. NOT free -- just unknown. */
        NO_PRICE
    }

    /**
     * costUsd is non-null exactly when the run could be costed -- i.e. for
     * {@link Outcome#PRICED} and {@link Outcome#FREE_SELF_HOSTED}. It is null
     * for the two unknown cases, and those are the only ones a report must
     * flag.
     */
    public record ExecutionCost(Outcome outcome, BigDecimal costUsd) {

        public static ExecutionCost priced(BigDecimal costUsd) {
            return new ExecutionCost(Outcome.PRICED, costUsd);
        }

        /** Self-hosted inference: known to cost nothing, as opposed to unknown. */
        public static ExecutionCost free() {
            return new ExecutionCost(Outcome.FREE_SELF_HOSTED, BigDecimal.ZERO);
        }

        public static ExecutionCost unpriced(Outcome outcome) {
            return new ExecutionCost(outcome, null);
        }

        public boolean isPriced() {
            return outcome == Outcome.PRICED || outcome == Outcome.FREE_SELF_HOSTED;
        }
    }

    /**
     * @param provider     the resolved LlmProvider (agent_executions.llm_provider)
     * @param modelName    the model that actually ran (agent_executions.model_name)
     * @param inputTokens  may be null -- treated as 0 provided the other side is present
     * @param outputTokens may be null -- likewise
     * @param at           when the run completed; picks the price in effect then
     */
    public ExecutionCost calculate(String provider, String modelName, Integer inputTokens, Integer outputTokens, Instant at) {
        // Self-hosted inference costs zero, and that is a FACT rather than a
        // gap in the price list -- there is no vendor invoice for a model
        // running on your own hardware. Checked before everything below,
        // because a LOCAL model name is whatever the operator happened to
        // pull ("qwen2.5-coder:7b", "llama3.1:8b", ...) and could never be
        // seeded by name. Without this branch an all-Ollama tenant reports
        // every run as unpriced -- technically "unknown", but misleading,
        // since the honest answer is $0.00 and the total really is complete.
        //
        // Note this is vendor spend, not total cost of ownership: the
        // electricity and the hardware are real, they are simply not billed
        // through this system and are not what a budget here governs.
        if (LOCAL_PROVIDER.equals(provider)) {
            return ExecutionCost.free();
        }

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
