package com.enterprisehub.core;

import com.enterprisehub.core.llm.LlmEngineFactory;
import com.enterprisehub.core.llm.LlmProvider;
import com.enterprisehub.core.tool.AgentTool;
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

    public SharedExecutionContext create(String tenantId, String executionId, LlmProvider provider, String apiKey,
                                          String modelName, List<AgentTool> tools) {
        return create(tenantId, executionId, provider, apiKey, modelName, tools, null, null);
    }

    /** systemPrompt: see SharedExecutionContext's javadoc -- an AgentDefinition's persona/instructions, nullable. */
    public SharedExecutionContext create(String tenantId, String executionId, LlmProvider provider, String apiKey,
                                          String modelName, List<AgentTool> tools, String systemPrompt) {
        return create(tenantId, executionId, provider, apiKey, modelName, tools, systemPrompt, null);
    }

    /** baseUrl: only meaningful for LlmProvider.LOCAL -- see LlmEngineFactory. Null for every other provider. */
    public SharedExecutionContext create(String tenantId, String executionId, LlmProvider provider, String apiKey,
                                          String modelName, List<AgentTool> tools, String systemPrompt, String baseUrl) {
        return create(tenantId, executionId, provider, apiKey, modelName, tools, systemPrompt, baseUrl, null);
    }

    /** maxTokensBudget: see ToolCallingChatEngine's javadoc -- null means "no budget, rely on maxToolRounds alone". */
    public SharedExecutionContext create(String tenantId, String executionId, LlmProvider provider, String apiKey,
                                          String modelName, List<AgentTool> tools, String systemPrompt, String baseUrl,
                                          Integer maxTokensBudget) {
        return create(tenantId, executionId, provider, apiKey, modelName, tools, systemPrompt, baseUrl, maxTokensBudget, null);
    }

    /** maxToolRounds: see ToolCallingChatEngine's javadoc -- null means "use its own DEFAULT_MAX_TOOL_ROUNDS". */
    public SharedExecutionContext create(String tenantId, String executionId, LlmProvider provider, String apiKey,
                                          String modelName, List<AgentTool> tools, String systemPrompt, String baseUrl,
                                          Integer maxTokensBudget, Integer maxToolRounds) {
        ChatModel chatModel = llmEngineFactory.create(provider, apiKey, modelName, baseUrl);
        // Anthropic-only, same story as cacheSystemMessages/cacheTools in
        // LlmEngineFactory -- decided here from the tenant's own provider
        // rather than exposed as a caller-supplied flag, so nothing above
        // this factory needs to know or care that it's Anthropic-specific.
        boolean cacheConversationHistory = provider == LlmProvider.ANTHROPIC;
        return new SharedExecutionContext(tenantId, executionId, chatModel, tools, systemPrompt, maxTokensBudget,
                maxToolRounds, cacheConversationHistory);
    }
}
