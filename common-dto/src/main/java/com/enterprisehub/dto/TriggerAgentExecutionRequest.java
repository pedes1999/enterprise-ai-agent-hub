package com.enterprisehub.dto;

/** agentSlug selects which AgentDefinition's persona/tools to use -- defaults if omitted (see AgentPromptRunner.DEFAULT_AGENT_SLUG). */
public record TriggerAgentExecutionRequest(
        String prompt,
        String agentSlug
) {
}
