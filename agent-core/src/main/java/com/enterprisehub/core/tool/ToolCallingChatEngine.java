package com.enterprisehub.core.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.JsonSchemaProperty;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
     * round 1. A real ticket-to-PR task (clone, explore, read, write,
     * open_pull_request) alone needs ~5; ticket-resolver's system prompt
     * additionally allows one corrected attempt if open_pull_request
     * reports the test run failed, which costs a read/write/retry cycle
     * on top of that. 14 was already headroom over that shape, but
     * test-fixer's prompt asks for a full-suite re-run after EACH
     * individual fix (read failing test, read source, fix, re-run --
     * repeated per failure, potentially several times on a repo with
     * multiple genuine failures), which raised it to 30. 30 still wasn't
     * enough live-run against this repo itself (a multi-module Maven
     * reactor plus an npm frontend) -- both Haiku and Sonnet exhausted all
     * 30 rounds on stack discovery and full-suite runs without ever
     * reaching write_file, let alone open_pull_request, on a
     * single-assertion fix. Raised to 100 to give a genuinely large/slow
     * monorepo enough headroom to actually finish, alongside tightening
     * test-fixer's own prompt (see its system_prompt migration) to scope
     * re-verification to the affected project instead of the whole
     * reactor+frontend every time -- a model that still never converges
     * gets cut off and reported honestly either way (see the incomplete/
     * incompleteReason handling below), this just raises how much genuine
     * work fits before that happens.
     */
    static final int MAX_TOOL_ROUNDS = 100;

    private final ChatLanguageModel chatModel;
    private final Map<String, AgentTool> toolsByName;
    private final List<ToolSpecification> toolSpecifications;
    private final ToolExecutionContext executionContext;
    private final String systemPrompt;
    private final Integer maxTokensBudget;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ToolCallingChatEngine(ChatLanguageModel chatModel, List<AgentTool> tools, ToolExecutionContext executionContext) {
        this(chatModel, tools, executionContext, null);
    }

    /**
     * systemPrompt is an AgentDefinition's persona/instructions (see
     * gateway-api) -- nullable/blank for callers that don't have one (or
     * still use the 3-arg constructor above), in which case the model just
     * sees the user message with no system message at all, same as before
     * this existed.
     */
    public ToolCallingChatEngine(ChatLanguageModel chatModel, List<AgentTool> tools, ToolExecutionContext executionContext, String systemPrompt) {
        this(chatModel, tools, executionContext, systemPrompt, null);
    }

    /**
     * maxTokensBudget is a second, cost-priced stop condition alongside
     * MAX_TOOL_ROUNDS -- see budgetExceeded()'s javadoc for why both exist
     * rather than one replacing the other. Null means "no budget, rely on
     * MAX_TOOL_ROUNDS alone" -- e.g. every pre-budget caller/test, and any
     * caller whose tenant/server config genuinely has no limit configured.
     */
    public ToolCallingChatEngine(ChatLanguageModel chatModel, List<AgentTool> tools, ToolExecutionContext executionContext,
                                  String systemPrompt, Integer maxTokensBudget) {
        this.chatModel = chatModel;
        this.toolsByName = new LinkedHashMap<>();
        tools.forEach(tool -> toolsByName.put(tool.name(), tool));
        this.toolSpecifications = tools.stream().map(ToolCallingChatEngine::toSpecification).toList();
        this.executionContext = executionContext;
        this.systemPrompt = systemPrompt;
        this.maxTokensBudget = maxTokensBudget;
    }

    /** Returns the final text answer, and whether a tool was actually invoked along the way (in any round). */
    public ToolChatResult chat(String userMessage) {
        List<ChatMessage> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(new SystemMessage(systemPrompt));
        }
        messages.add(new UserMessage(userMessage));
        boolean toolWasUsed = false;
        // Null until the first response that actually carries usage data --
        // some providers/mocks return none at all, and that's "unknown", not
        // "zero tokens spent" (see ToolChatResult's fields).
        TokenUsage totalUsage = null;

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            if (budgetExceeded(totalUsage)) {
                // Exceeded during a PREVIOUS round -- stop before spending
                // more on another generate() call, same "force one final
                // text-only answer" handling as the round-cap path below.
                break;
            }
            Response<AiMessage> response = chatModel.generate(messages, toolSpecifications);
            totalUsage = accumulate(totalUsage, response.tokenUsage());
            AiMessage aiMessage = response.content();

            if (!aiMessage.hasToolExecutionRequests()) {
                // A "final" answer that was actually cut off mid-generation (the
                // model ran out of output tokens before it could finish, possibly
                // before it even got to request the next tool call) is not a real
                // stopping point -- treating it as one silently ends the task
                // early with no error anywhere. finishReason() carries exactly
                // that signal (LENGTH), already returned by every provider call,
                // previously discarded here.
                boolean truncated = response.finishReason() == FinishReason.LENGTH;
                String incompleteReason = truncated
                        ? "Model response was truncated (hit the max_tokens limit) before it finished."
                        : null;
                return new ToolChatResult(aiMessage.text(), toolWasUsed, truncated, incompleteReason,
                        inputTokens(totalUsage), outputTokens(totalUsage), totalTokens(totalUsage));
            }

            toolWasUsed = true;
            messages.add(aiMessage);
            boolean terminalSuccess = false;
            for (ToolExecutionRequest request : aiMessage.toolExecutionRequests()) {
                String result = executeTool(request);
                messages.add(ToolExecutionResultMessage.from(request, result));
                if (isTerminalSuccess(request.name(), result)) {
                    terminalSuccess = true;
                }
            }

            if (terminalSuccess) {
                // A tool like open_pull_request just reported that the actual
                // goal was achieved -- don't offer the model another round of
                // tools and trust it to notice and stop on its own (it often
                // won't, see AgentTool.isTerminalSuccess()'s javadoc); force
                // one final text-only answer instead, same mechanism as the
                // round-cap path below but a genuine stopping point, so this
                // is NOT incomplete.
                Response<AiMessage> finalResponse = chatModel.generate(messages, List.of());
                totalUsage = accumulate(totalUsage, finalResponse.tokenUsage());
                return new ToolChatResult(finalResponse.content().text(), toolWasUsed, false, null,
                        inputTokens(totalUsage), outputTokens(totalUsage), totalTokens(totalUsage));
            }
        }

        // Either the round cap was exhausted or the loop above broke early on
        // an exceeded budget -- both force a text answer instead of letting
        // the model request yet another round it won't get to use, and both
        // are "not a real stopping point" the same way a truncated response
        // is. budgetExceeded() here re-checks the SAME totalUsage the break
        // (if any) just fired on, so it reliably tells the two apart.
        Response<AiMessage> finalResponse = chatModel.generate(messages, List.of());
        totalUsage = accumulate(totalUsage, finalResponse.tokenUsage());
        String incompleteReason = budgetExceeded(totalUsage)
                ? "Agent execution stopped after exceeding its token budget (" + maxTokensBudget + " tokens)."
                : "Agent used all " + MAX_TOOL_ROUNDS + " allowed tool-call rounds without finishing.";
        return new ToolChatResult(finalResponse.content().text(), toolWasUsed, true, incompleteReason,
                inputTokens(totalUsage), outputTokens(totalUsage), totalTokens(totalUsage));
    }

    /** null-safe TokenUsage.add() -- a provider/mock response with no usage data leaves the running total unchanged. */
    private static TokenUsage accumulate(TokenUsage total, TokenUsage next) {
        if (next == null) {
            return total;
        }
        return total == null ? next : total.add(next);
    }

    /**
     * True once totalUsage's running total has reached maxTokensBudget.
     * Deliberately a SEPARATE stop condition from MAX_TOOL_ROUNDS, not a
     * replacement for it: this can only fire once a provider response has
     * actually reported usage, so a provider/mock that never does (totalUsage
     * stays null forever) would let a run loop unbounded on budget alone --
     * MAX_TOOL_ROUNDS is the backstop that still catches that case. A null
     * maxTokensBudget (no budget configured) always returns false, same
     * effect as today's round-cap-only behavior.
     */
    private boolean budgetExceeded(TokenUsage totalUsage) {
        return maxTokensBudget != null && totalUsage != null && totalUsage.totalTokenCount() != null
                && totalUsage.totalTokenCount() >= maxTokensBudget;
    }

    private static Integer inputTokens(TokenUsage usage) {
        return usage == null ? null : usage.inputTokenCount();
    }

    private static Integer outputTokens(TokenUsage usage) {
        return usage == null ? null : usage.outputTokenCount();
    }

    private static Integer totalTokens(TokenUsage usage) {
        return usage == null ? null : usage.totalTokenCount();
    }

    private boolean isTerminalSuccess(String toolName, String result) {
        AgentTool tool = toolsByName.get(toolName);
        return tool != null && tool.isTerminalSuccess(result);
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
        // A model declining an optional parameter (see AgentTool.optionalParameterNames())
        // may send an explicit JSON null for it instead of omitting the key
        // entirely -- treat both the same way (key absent from the result),
        // rather than String.valueOf(null) silently turning it into the
        // four-character string "null" that a tool would then treat as a
        // real (garbage) argument value.
        raw.forEach((key, value) -> {
            if (value != null) {
                arguments.put(key, String.valueOf(value));
            }
        });
        return arguments;
    }

    private static ToolSpecification toSpecification(AgentTool tool) {
        ToolSpecification.Builder builder = ToolSpecification.builder()
                .name(tool.name())
                .description(tool.description());

        Set<String> optionalParams = tool.optionalParameterNames();
        tool.parameterDescriptions().forEach((paramName, paramDescription) -> {
            if (optionalParams.contains(paramName)) {
                builder.addOptionalParameter(paramName, JsonSchemaProperty.STRING, JsonSchemaProperty.description(paramDescription));
            } else {
                builder.addParameter(paramName, JsonSchemaProperty.STRING, JsonSchemaProperty.description(paramDescription));
            }
        });

        return builder.build();
    }

    /**
     * incomplete is true when reply is NOT a genuine final answer -- either
     * the model's response was truncated by max_tokens before it finished,
     * or the round cap was hit while the model still wanted to keep calling
     * tools. In both cases the model never reached a real stopping point, so
     * a caller (AgentJobWorker) should treat this as a failed execution with
     * incompleteReason as the error, not a successful one with a
     * suspiciously short (or blank) reply and no explanation anywhere.
     *
     * inputTokens/outputTokens/totalTokens are summed across every
     * chatModel.generate() call made during chat() (every round, plus
     * whichever "final" call ended it) -- null, not zero, when not a single
     * one of those responses carried usage data, so a caller can tell
     * "genuinely free" apart from "we don't know".
     */
    public record ToolChatResult(String reply, boolean toolWasUsed, boolean incomplete, String incompleteReason,
                                  Integer inputTokens, Integer outputTokens, Integer totalTokens) {

        /** For callers that don't need token usage -- e.g. existing tests predating this field. */
        public ToolChatResult(String reply, boolean toolWasUsed, boolean incomplete, String incompleteReason) {
            this(reply, toolWasUsed, incomplete, incompleteReason, null, null, null);
        }
    }
}
