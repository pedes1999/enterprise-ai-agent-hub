package com.enterprisehub.dto;

/** agentSlug is only honored by /agents/ping-with-tools -- selects which AgentDefinition's persona/tools to use. Defaults if omitted (see AgentPromptRunner.DEFAULT_AGENT_SLUG). Ignored by the plain /agents/ping endpoint (no tools involved). */
public record AgentPingRequest(
        String prompt,
        String agentSlug
) {
}
