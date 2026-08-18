package com.enterprisehub.gateway.agent;

import java.util.Map;
import java.util.UUID;

/**
 * Everything AgentPromptRunner needs for one run, as a single named value
 * instead of a nine-parameter argument list.
 *
 * The first four are what every run always has -- which tenant, as which
 * user, under which execution id, running which agent -- so they're required
 * up front via {@link #of}; prompt and the rest are optional and additive.
 * This replaced a family of three overloaded {@code run(...)} methods that
 * had grown as features landed, where the widest call site read as a row of
 * positional values and its Mockito stubs read as nine consecutive
 * {@code any()} matchers with nothing naming what they stood for.
 *
 * Every field means exactly what it did as a parameter -- see
 * AgentPromptRunner.run() and TriggerAgentExecutionRequest for the per-field
 * contracts, in particular that repositoryBranch is only meaningful
 * alongside a non-blank repositoryUrl, and that a null maxTokensOverride
 * falls back to the tenant's own resolved default.
 */
public record AgentRunRequest(
        UUID tenantId,
        UUID userId,
        String executionId,
        String agentSlug,
        String prompt,
        String repositoryUrl,
        String repositoryBranch,
        Map<String, String> inputParameters,
        Integer maxTokensOverride) {

    /** Starts a request for the four values every run always has. */
    public static Builder of(UUID tenantId, UUID userId, String executionId, String agentSlug) {
        return new Builder(tenantId, userId, executionId, agentSlug);
    }

    public static final class Builder {

        private final UUID tenantId;
        private final UUID userId;
        private final String executionId;
        private final String agentSlug;
        private String prompt;
        private String repositoryUrl;
        private String repositoryBranch;
        private Map<String, String> inputParameters;
        private Integer maxTokensOverride;

        private Builder(UUID tenantId, UUID userId, String executionId, String agentSlug) {
            this.tenantId = tenantId;
            this.userId = userId;
            this.executionId = executionId;
            this.agentSlug = agentSlug;
        }

        public Builder prompt(String prompt) {
            this.prompt = prompt;
            return this;
        }

        /** Branch is only meaningful alongside a url -- paired here so a call site can't set one and silently forget the other. */
        public Builder repository(String repositoryUrl, String repositoryBranch) {
            this.repositoryUrl = repositoryUrl;
            this.repositoryBranch = repositoryBranch;
            return this;
        }

        public Builder inputParameters(Map<String, String> inputParameters) {
            this.inputParameters = inputParameters;
            return this;
        }

        /** Per-execution token budget override -- null falls back to the tenant's resolved default. */
        public Builder maxTokensOverride(Integer maxTokensOverride) {
            this.maxTokensOverride = maxTokensOverride;
            return this;
        }

        public AgentRunRequest build() {
            return new AgentRunRequest(tenantId, userId, executionId, agentSlug, prompt, repositoryUrl,
                    repositoryBranch, inputParameters, maxTokensOverride);
        }
    }
}
