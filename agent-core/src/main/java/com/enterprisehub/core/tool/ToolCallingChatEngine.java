package com.enterprisehub.core.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.JsonSchemaProperty;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal, single-round tool-calling loop: send the user's message plus
 * every available tool's specification, and if the model asks to call one
 * or more tools, execute them and send the results back for a final
 * answer. Deliberately not a multi-round agent loop (no repeated
 * tool-call-then-reconsider cycles) -- that's a Week 6+ concern once
 * agent-runtime's real tools (which can fail, need retries, etc.) exist.
 * This proves the wiring: AgentTool -> ToolSpecification -> model decides
 * to call it -> arguments parsed -> tool runs -> result fed back -> final
 * answer.
 */
public class ToolCallingChatEngine {

    private final ChatLanguageModel chatModel;
    private final Map<String, AgentTool> toolsByName;
    private final List<ToolSpecification> toolSpecifications;
    private final ToolExecutionContext executionContext;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ToolCallingChatEngine(ChatLanguageModel chatModel, List<AgentTool> tools, ToolExecutionContext executionContext) {
        this.chatModel = chatModel;
        this.toolsByName = new LinkedHashMap<>();
        tools.forEach(tool -> toolsByName.put(tool.name(), tool));
        this.toolSpecifications = tools.stream().map(ToolCallingChatEngine::toSpecification).toList();
        this.executionContext = executionContext;
    }

    /** Returns the final text answer, and whether a tool was actually invoked along the way. */
    public ToolChatResult chat(String userMessage) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new UserMessage(userMessage));

        Response<AiMessage> response = chatModel.generate(messages, toolSpecifications);
        AiMessage aiMessage = response.content();

        if (!aiMessage.hasToolExecutionRequests()) {
            return new ToolChatResult(aiMessage.text(), false);
        }

        messages.add(aiMessage);
        for (ToolExecutionRequest request : aiMessage.toolExecutionRequests()) {
            String result = executeTool(request);
            messages.add(ToolExecutionResultMessage.from(request, result));
        }

        Response<AiMessage> finalResponse = chatModel.generate(messages, toolSpecifications);
        return new ToolChatResult(finalResponse.content().text(), true);
    }

    private String executeTool(ToolExecutionRequest request) {
        AgentTool tool = toolsByName.get(request.name());
        if (tool == null) {
            return "Error: no tool registered with name '" + request.name() + "'";
        }
        try {
            return tool.execute(executionContext, parseArguments(request.arguments()));
        } catch (Exception e) {
            return "Error executing tool '" + request.name() + "': " + e.getMessage();
        }
    }

    private Map<String, String> parseArguments(String argumentsJson) throws Exception {
        Map<String, Object> raw = objectMapper.readValue(argumentsJson, new TypeReference<>() {
        });
        Map<String, String> arguments = new LinkedHashMap<>();
        raw.forEach((key, value) -> arguments.put(key, String.valueOf(value)));
        return arguments;
    }

    private static ToolSpecification toSpecification(AgentTool tool) {
        ToolSpecification.Builder builder = ToolSpecification.builder()
                .name(tool.name())
                .description(tool.description());

        tool.parameterDescriptions().forEach((paramName, paramDescription) ->
                builder.addParameter(paramName, JsonSchemaProperty.STRING, JsonSchemaProperty.description(paramDescription)));

        return builder.build();
    }

    public record ToolChatResult(String reply, boolean toolWasUsed) {
    }
}
