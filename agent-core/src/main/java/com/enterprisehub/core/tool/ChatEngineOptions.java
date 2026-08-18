package com.enterprisehub.core.tool;

/**
 * The optional, tunable knobs of a ToolCallingChatEngine run, as one named
 * value instead of a positional tail of five nullable parameters.
 *
 * These accumulated one feature at a time -- a system prompt, then a token
 * budget, then a configurable round cap, then Anthropic prompt caching, then
 * a history-compaction window. Each addition previously meant a new
 * constructor overload on ToolCallingChatEngine AND a matching one on
 * SharedExecutionContext AND a matching create() on
 * SharedExecutionContextFactory -- three parallel telescoping chains that
 * had to be edited in lockstep, ending in call sites like
 * {@code (model, tools, ctx, systemPrompt, null, null, false, null)} where
 * three consecutive nullable Integers were trivially transposable. Adding
 * the next knob is now one field here plus one builder method.
 *
 * {@link #DEFAULTS} is what a caller that tunes nothing gets, and is exactly
 * what the three-argument ToolCallingChatEngine constructor uses: no system
 * prompt, no token budget, the engine's own DEFAULT_MAX_TOOL_ROUNDS, no
 * Anthropic cache breakpoints, and its own DEFAULT_COMPACTION_WINDOW_ROUNDS.
 * See ToolCallingChatEngine's own javadoc for what each one does and why the
 * defaults are what they are.
 */
public record ChatEngineOptions(
        String systemPrompt,
        Integer maxTokensBudget,
        Integer maxToolRounds,
        boolean cacheConversationHistory,
        Integer compactionWindowRounds) {

    /** Tune nothing -- every value falls back to the engine's own default. */
    public static final ChatEngineOptions DEFAULTS = new ChatEngineOptions(null, null, null, false, null);

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private String systemPrompt;
        private Integer maxTokensBudget;
        private Integer maxToolRounds;
        private boolean cacheConversationHistory;
        private Integer compactionWindowRounds;

        private Builder() {
        }

        /** An AgentDefinition's persona/instructions -- null or blank means the model sees no system message at all. */
        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        /** Cost-priced stop condition -- null means "no budget, rely on maxToolRounds alone". */
        public Builder maxTokensBudget(Integer maxTokensBudget) {
            this.maxTokensBudget = maxTokensBudget;
            return this;
        }

        /** Defence-in-depth round ceiling -- null means ToolCallingChatEngine.DEFAULT_MAX_TOOL_ROUNDS. */
        public Builder maxToolRounds(Integer maxToolRounds) {
            this.maxToolRounds = maxToolRounds;
            return this;
        }

        /** Anthropic-only prompt caching of the conversation history -- a silent no-op on every other provider. */
        public Builder cacheConversationHistory(boolean cacheConversationHistory) {
            this.cacheConversationHistory = cacheConversationHistory;
            return this;
        }

        /** How many rounds of tool results stay uncompacted -- null means ToolCallingChatEngine's own default window. */
        public Builder compactionWindowRounds(Integer compactionWindowRounds) {
            this.compactionWindowRounds = compactionWindowRounds;
            return this;
        }

        public ChatEngineOptions build() {
            return new ChatEngineOptions(systemPrompt, maxTokensBudget, maxToolRounds, cacheConversationHistory,
                    compactionWindowRounds);
        }
    }
}
