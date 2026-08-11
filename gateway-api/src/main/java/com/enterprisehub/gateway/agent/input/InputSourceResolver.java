package com.enterprisehub.gateway.agent.input;

import java.util.Map;
import java.util.UUID;

/**
 * Turns a triggering request's source-specific parameters (a Jira ticket
 * key, raw pasted text, an uploaded file reference, ...) into the plain
 * text blob an agent's prompt gets assembled from -- see
 * InputSourceResolverRegistry for how AgentDefinition.inputSourceType picks
 * which implementation runs, and AgentPromptRunner for where the result
 * lands in the final prompt.
 *
 * Deliberately a server-side resolution step, not a tool the model can
 * choose to call: the same "enforce it at the point that matters, not the
 * caller" posture CredentialResolver and OpenPullRequestTool's mandatory
 * testCommand already use. An agent should never be able to decide NOT to
 * read the ticket it was triggered for.
 */
public interface InputSourceResolver {

    /** Must match an AgentDefinition's own input_source_type column, e.g. "MANUAL_TEXT". */
    String sourceType();

    /**
     * Resolves this source's parameters into the text blob to seed the
     * agent's prompt with. tenantId is available for implementations that
     * need to look up tenant-scoped credentials/config (e.g. a future
     * JiraTicketResolver resolving a tenant's own Jira API token) -- see
     * ManualTextInputResolver for the simplest possible case, which ignores it.
     */
    String resolve(UUID tenantId, Map<String, String> parameters);
}
