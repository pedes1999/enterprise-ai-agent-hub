package com.enterprisehub.gateway.agent.catalog;

import java.util.UUID;

/**
 * Everything a ToolFactory might need at construction time that ISN'T a
 * per-call concern (SandboxSession/CredentialResolver already cover the
 * "runs sandboxed commands" and "needs a tool credential" cases) -- added
 * for RetrievalToolFactory, which needs to know which AgentDefinition is
 * being assembled (to look up its AgentKnowledgeSourceBinding, see
 * V30__agent_knowledge_source_binding.sql) and which user triggered this
 * execution (to resolve their own OpenAI/Gemini vendor credential for
 * embedding query text, matching AgentPromptRunner.resolveApiKey()'s
 * existing per-user resolution for the chat model). Every other ToolFactory
 * ignores this parameter entirely -- it exists so a fifth tool with its own
 * unusual construction-time need doesn't require yet another ToolFactory.
 * create() signature change.
 */
public record ToolCreationContext(String tenantId, String userId, UUID agentDefinitionId) {
}
