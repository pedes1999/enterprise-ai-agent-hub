package com.enterprisehub.core;

import com.enterprisehub.core.tool.AgentTool;
import com.enterprisehub.core.tool.ToolCallingChatEngine;
import dev.langchain4j.model.chat.ChatLanguageModel;

import java.util.List;

/**
 * The object threaded through a single agent invocation: which tenant it's
 * running for, an LLM client already configured with that tenant's own
 * credential, and the tools it's allowed to use. gateway-api assembles one
 * of these per request via SharedExecutionContextFactory; nothing about it
 * is persisted or reused across requests.
 *
 * Deliberately thin today -- no working directory, no repository handle,
 * no execution id/logging correlation yet. Those get added as the pieces
 * that need them land (agent-runtime's sandboxed workspace in Weeks 6-8,
 * durable execution tracking in Weeks 9-10), rather than speculatively
 * now.
 */
public class SharedExecutionContext {

    private final String tenantId;
    private final ChatLanguageModel chatModel;
    private final List<AgentTool> tools;
    private final ToolCallingChatEngine chatEngine;

    public SharedExecutionContext(String tenantId, ChatLanguageModel chatModel, List<AgentTool> tools) {
        this.tenantId = tenantId;
        this.chatModel = chatModel;
        this.tools = List.copyOf(tools);
        this.chatEngine = new ToolCallingChatEngine(chatModel, this.tools);
    }

    public String tenantId() {
        return tenantId;
    }

    public ChatLanguageModel chatModel() {
        return chatModel;
    }

    public List<AgentTool> tools() {
        return tools;
    }

    public ToolCallingChatEngine.ToolChatResult chat(String userMessage) {
        return chatEngine.chat(userMessage);
    }
}
