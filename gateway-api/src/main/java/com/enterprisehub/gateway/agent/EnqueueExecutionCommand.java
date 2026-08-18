package com.enterprisehub.gateway.agent;

import java.util.Map;
import java.util.UUID;

/**
 * Everything needed to queue one agent execution, as a single named value
 * instead of a nine-parameter argument list.
 *
 * tenantId and agentSlug are the only two things every caller always has, so
 * they're required up front via {@link #forAgent}; the rest are optional and
 * additive, and each has a named setter. This replaced a family of five
 * overloaded {@code enqueue(...)} methods that had grown one parameter at a
 * time as features landed (per-execution token budget, then the triggering
 * user, then the delegating parent execution). The overloads made call sites
 * genuinely ambiguous -- DelegateToAgentTool's read
 * {@code enqueue(tenantId, prompt, agentSlug, repositoryUrl, null, null, null, triggeredBy, parentExecutionId)},
 * where nothing at the call site said what the four consecutive nulls were.
 *
 * Every field is nullable and means exactly what it did as a parameter:
 * see AgentExecutionService.enqueue() and TriggerAgentExecutionRequest for
 * the per-field contracts. Whether a null prompt/repositoryUrl/inputParameters
 * key is actually acceptable is not decided here -- it depends on the
 * resolved AgentDefinition's own required_inputs, checked in
 * AgentExecutionService.validateRequiredInputs().
 */
public record EnqueueExecutionCommand(
        UUID tenantId,
        String agentSlug,
        String prompt,
        String repositoryUrl,
        String repositoryBranch,
        Map<String, String> inputParameters,
        Integer maxTokens,
        UUID triggeredBy,
        UUID parentExecutionId) {

    /** Starts a command for the two values every caller always has. */
    public static Builder forAgent(UUID tenantId, String agentSlug) {
        return new Builder(tenantId, agentSlug);
    }

    public static final class Builder {

        private final UUID tenantId;
        private final String agentSlug;
        private String prompt;
        private String repositoryUrl;
        private String repositoryBranch;
        private Map<String, String> inputParameters;
        private Integer maxTokens;
        private UUID triggeredBy;
        private UUID parentExecutionId;

        private Builder(UUID tenantId, String agentSlug) {
            this.tenantId = tenantId;
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

        public Builder repositoryUrl(String repositoryUrl) {
            this.repositoryUrl = repositoryUrl;
            return this;
        }

        public Builder inputParameters(Map<String, String> inputParameters) {
            this.inputParameters = inputParameters;
            return this;
        }

        /** Per-execution token budget override -- null falls back to the tenant's/server's default. */
        public Builder maxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        /** Which app_user this runs as -- AgentPromptRunner resolves that person's vendor credential later. */
        public Builder triggeredBy(UUID triggeredBy) {
            this.triggeredBy = triggeredBy;
            return this;
        }

        /** Set only by delegate_to_agent, on behalf of an already-running parent execution. */
        public Builder parentExecutionId(UUID parentExecutionId) {
            this.parentExecutionId = parentExecutionId;
            return this;
        }

        public EnqueueExecutionCommand build() {
            return new EnqueueExecutionCommand(tenantId, agentSlug, prompt, repositoryUrl, repositoryBranch,
                    inputParameters, maxTokens, triggeredBy, parentExecutionId);
        }
    }
}
