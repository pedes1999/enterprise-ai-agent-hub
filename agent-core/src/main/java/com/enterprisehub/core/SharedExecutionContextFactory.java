package com.enterprisehub.core;

import com.enterprisehub.core.llm.LlmEngineFactory;
import com.enterprisehub.core.llm.LlmProvider;
import com.enterprisehub.core.tool.AgentTool;
import dev.langchain4j.model.chat.ChatLanguageModel;

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
        return create(tenantId, executionId, provider, apiKey, modelName, tools, null);
    }

    /** systemPrompt: see SharedExecutionContext's javadoc -- an AgentDefinition's persona/instructions, nullable. */
    public SharedExecutionContext create(String tenantId, String executionId, LlmProvider provider, String apiKey,
                                          String modelName, List<AgentTool> tools, String systemPrompt) {
        ChatLanguageModel chatModel = llmEngineFactory.create(provider, apiKey, modelName);
        return new SharedExecutionContext(tenantId, executionId, chatModel, tools, systemPrompt);
    }
}
