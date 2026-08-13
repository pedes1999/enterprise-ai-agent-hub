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
 *
 * repositoryBranch is optional and only meaningful alongside repositoryUrl
 * (ignored if repositoryUrl is blank) -- null/blank means "clone the
 * repository's default branch", matching git_clone's own optional branch
 * argument (see GitCloneTool). Not part of required_inputs' fixed
 * vocabulary since it's never required on its own.
 */
public record TriggerAgentExecutionRequest(
        String prompt,
        String agentSlug,
        String repositoryUrl,
        String repositoryBranch,
        Map<String, String> inputParameters
) {
}
