package com.enterprisehub.core;

import com.enterprisehub.core.llm.LlmEngineFactory;
import com.enterprisehub.core.llm.LlmProvider;
import com.enterprisehub.core.tool.AgentTool;
import com.enterprisehub.core.tool.ChatEngineOptions;
import dev.langchain4j.model.chat.ChatModel;

import java.util.List;

/**
 * Assembles a SharedExecutionContext given a tenant's chosen provider and
 * already-decrypted credential. gateway-api owns credential decryption
 * (VendorCredentialService) and hands this factory only the plaintext key
 * for the duration of building the context -- this class never touches the
 * database or knows anything about encryption.
 */
public class SharedExecutionContextFactory {

    private final LlmEngineFactory llmEngineFactory;

    public SharedExecutionContextFactory(LlmEngineFactory llmEngineFactory) {
        this.llmEngineFactory = llmEngineFactory;
    }

    /** Tunes nothing and talks to the provider's default endpoint -- see ChatEngineOptions.DEFAULTS. */
    public SharedExecutionContext create(String tenantId, String executionId, LlmProvider provider, String apiKey,
                                          String modelName, List<AgentTool> tools) {
        return create(tenantId, executionId, provider, apiKey, modelName, tools, null, ChatEngineOptions.DEFAULTS);
    }

    /**
     * baseUrl: only meaningful for LlmProvider.LOCAL -- see LlmEngineFactory.
     * Null for every other provider. options carries the engine's tunable
     * knobs; see ChatEngineOptions.
     *
     * Note that this deliberately IGNORES options.cacheConversationHistory()
     * and decides that itself from the tenant's provider, the same way
     * cacheSystemMessages/cacheTools work in LlmEngineFactory -- so nothing
     * above this factory needs to know or care that it's Anthropic-specific.
     */
    public SharedExecutionContext create(String tenantId, String executionId, LlmProvider provider, String apiKey,
                                          String modelName, List<AgentTool> tools, String baseUrl,
                                          ChatEngineOptions options) {
        ChatModel chatModel = llmEngineFactory.create(provider, apiKey, modelName, baseUrl);
        ChatEngineOptions resolvedOptions = new ChatEngineOptions(
                options.systemPrompt(), options.maxTokensBudget(), options.maxToolRounds(),
                provider == LlmProvider.ANTHROPIC, options.compactionWindowRounds());
        return new SharedExecutionContext(tenantId, executionId, chatModel, tools, resolvedOptions);
    }
}
