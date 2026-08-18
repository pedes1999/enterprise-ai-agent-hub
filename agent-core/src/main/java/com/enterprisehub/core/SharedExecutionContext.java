package com.enterprisehub.core;

import com.enterprisehub.core.tool.AgentTool;
import com.enterprisehub.core.tool.ChatEngineOptions;
import com.enterprisehub.core.tool.ToolCallingChatEngine;
import com.enterprisehub.core.tool.ToolExecutionContext;
import dev.langchain4j.model.chat.ChatModel;

import java.util.List;

/**
 * The object threaded through a single agent invocation: which tenant it's
 * running for, an execution id for audit/log correlation, an LLM client
 * already configured with that tenant's own credential, and the tools it's
 * allowed to use. gateway-api assembles one of these per request via
 * SharedExecutionContextFactory; nothing about it is persisted or reused
 * across requests.
 *
 * executionId exists specifically so every AgentTool.execute() call carries
 * a ToolExecutionContext -- see that class's javadoc for why this is a
 * constructor parameter here and not a ThreadLocal.
 */
public class SharedExecutionContext {

    private final String tenantId;
    private final String executionId;
    private final ChatModel chatModel;
    private final List<AgentTool> tools;
    private final ToolCallingChatEngine chatEngine;

    /** Tunes nothing -- see ChatEngineOptions.DEFAULTS. */
    public SharedExecutionContext(String tenantId, String executionId, ChatModel chatModel, List<AgentTool> tools) {
        this(tenantId, executionId, chatModel, tools, ChatEngineOptions.DEFAULTS);
    }

    /** options is passed straight through to the engine -- see ChatEngineOptions and ToolCallingChatEngine's own javadoc for what each knob does. */
    public SharedExecutionContext(String tenantId, String executionId, ChatModel chatModel, List<AgentTool> tools,
                                   ChatEngineOptions options) {
        this.tenantId = tenantId;
        this.executionId = executionId;
        this.chatModel = chatModel;
        this.tools = List.copyOf(tools);
        this.chatEngine = new ToolCallingChatEngine(chatModel, this.tools, new ToolExecutionContext(tenantId, executionId), options);
    }

    public String tenantId() {
        return tenantId;
    }

    public String executionId() {
        return executionId;
    }

    public ChatModel chatModel() {
        return chatModel;
    }

    public List<AgentTool> tools() {
        return tools;
    }

    public ToolCallingChatEngine.ToolChatResult chat(String userMessage) {
        return chatEngine.chat(userMessage);
    }
}
