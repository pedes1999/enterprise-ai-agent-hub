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
 * A bounded, multi-round tool-calling loop: send the user's message plus
 * every available tool's specification; each round, if the model asks to
 * call one or more tools, execute them and send the results back, then let
 * the model decide again -- clone, then read a file, then edit it, then
 * run tests is a real shape this needs to support (agent-runtime's
 * sandboxed tools now share one persistent workspace across a whole
 * execution, via SandboxSession -- see its javadoc -- specifically so a
 * multi-round sequence like that can actually see its own earlier steps).
 * Capped at MAX_TOOL_ROUNDS so a model that never settles on a final
 * answer can't loop (and rack up API cost) forever; if the cap is hit, one
 * last call is made with no tool specifications at all, forcing a text
 * answer instead of yet another tool request.
 */
public class ToolCallingChatEngine {

    /**
     * How many rounds of "model requests tools -> we run them -> feed
     * results back" this allows before forcing a final text-only answer.
     * A simple single-step tool use (e.g. "what time is it") finishes in
     * round 1; a real coding task (clone, read, edit, verify) needs
     * several. Chosen as a cost/safety bound, not a measured requirement --
     * revisit if real tasks start hitting it.
     */
    static final int MAX_TOOL_ROUNDS = 6;

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

    /** Returns the final text answer, and whether a tool was actually invoked along the way (in any round). */
    public ToolChatResult chat(String userMessage) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new UserMessage(userMessage));
        boolean toolWasUsed = false;

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            Response<AiMessage> response = chatModel.generate(messages, toolSpecifications);
            AiMessage aiMessage = response.content();

            if (!aiMessage.hasToolExecutionRequests()) {
                return new ToolChatResult(aiMessage.text(), toolWasUsed);
            }

            toolWasUsed = true;
            messages.add(aiMessage);
            for (ToolExecutionRequest request : aiMessage.toolExecutionRequests()) {
                String result = executeTool(request);
                messages.add(ToolExecutionResultMessage.from(request, result));
            }
        }

        // Round cap hit -- force a text answer instead of letting the model
        // request yet another round it won't get to use.
        Response<AiMessage> finalResponse = chatModel.generate(messages, List.of());
        return new ToolChatResult(finalResponse.content().text(), toolWasUsed);
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
