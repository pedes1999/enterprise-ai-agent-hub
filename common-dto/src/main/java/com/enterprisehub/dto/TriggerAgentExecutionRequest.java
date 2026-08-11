package com.enterprisehub.dto;

import java.util.Map;

/**
 * agentSlug selects which AgentDefinition's persona/tools to use --
 * defaults if omitted (see AgentPromptRunner.DEFAULT_AGENT_SLUG).
 *
 * repositoryUrl and inputParameters are both optional and additive: an
 * agent whose AgentDefinition has no inputSourceType configured (e.g.
 * general-assistant) ignores them entirely and behaves exactly as before --
 * prompt alone is the whole user turn. For an agent that DOES have an
 * inputSourceType, inputParameters is resolved via the matching
 * InputSourceResolver into a text blob that gets prepended to prompt
 * (along with repositoryUrl, if given) -- see AgentPromptRunner. A flat
 * string map rather than a typed field per resolver so adding resolver #2
 * (e.g. a future Jira ticket resolver) never requires reshaping this DTO.
 */
public record TriggerAgentExecutionRequest(
        String prompt,
        String agentSlug,
        String repositoryUrl,
        Map<String, String> inputParameters
) {
}
