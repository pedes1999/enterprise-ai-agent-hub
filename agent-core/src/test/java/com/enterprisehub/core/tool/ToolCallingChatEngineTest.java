package com.enterprisehub.core.tool;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ToolCallingChatEngineTest {

    private static final ToolExecutionContext CONTEXT = new ToolExecutionContext("tenant-1", "exec-1");

    private final AgentTool echoTool = new AgentTool() {
        @Override
        public String name() {
            return "echo";
        }

        @Override
        public String description() {
            return "Echoes back the given message";
        }

        @Override
        public Map<String, String> parameterDescriptions() {
            return Map.of("message", "the message to echo");
        }

        @Override
        public String execute(ToolExecutionContext context, Map<String, String> arguments) {
            return "echo: " + arguments.get("message");
        }
    };

    /** langchain4j 1.x's ChatModel.chat(ChatRequest) replaced the old generate(messages, tools) two-arg call -- these build the ChatResponse a stub returns. */
    private static ChatResponse response(AiMessage aiMessage) {
        return ChatResponse.builder().aiMessage(aiMessage).build();
    }

    private static ChatResponse response(AiMessage aiMessage, TokenUsage tokenUsage) {
        return ChatResponse.builder().aiMessage(aiMessage).tokenUsage(tokenUsage).build();
    }

    private static ChatResponse response(AiMessage aiMessage, TokenUsage tokenUsage, FinishReason finishReason) {
        return ChatResponse.builder().aiMessage(aiMessage).tokenUsage(tokenUsage).finishReason(finishReason).build();
    }

    /**
     * ToolCallingChatEngine's forced "final, no more tools" call always requests an empty tool
     * list -- ChatRequest normalizes "no tools given" to List.of(), never null (see
     * ChatRequestParameters/Utils.copy()). Null-safe because Mockito probes argThat matchers
     * with a null placeholder while recording a when(...) stub, before any real call happens.
     */
    private static boolean requestsNoTools(ChatRequest request) {
        return request != null && request.toolSpecifications().isEmpty();
    }

    private static boolean requestsTools(ChatRequest request) {
        return request != null && !request.toolSpecifications().isEmpty();
    }

    @Test
    void chat_modelAnswersDirectly_noToolCallNeeded() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(response(AiMessage.from("Just an answer")));

        ToolCallingChatEngine engine = new ToolCallingChatEngine(model, List.of(echoTool), CONTEXT);
        ToolCallingChatEngine.ToolChatResult result = engine.chat("Hi");

        assertThat(result.reply()).isEqualTo("Just an answer");
        assertThat(result.toolWasUsed()).isFalse();
        assertThat(result.incomplete()).isFalse();
        assertThat(result.incompleteReason()).isNull();
        verify(model, times(1)).chat(any(ChatRequest.class));
    }

    @Test
    void chat_modelAnswersDirectly_noUsageDataOnResponse_reportsNullNotZero() {
        // response() with no TokenUsage arg -- the shape a mock (or a provider
        // that just doesn't report usage) returns. Must stay null, not
        // silently become 0, which would read as "this was free".
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(response(AiMessage.from("Just an answer")));

        ToolCallingChatEngine.ToolChatResult result = new ToolCallingChatEngine(model, List.of(echoTool), CONTEXT).chat("Hi");

        assertThat(result.inputTokens()).isNull();
        assertThat(result.outputTokens()).isNull();
        assertThat(result.totalTokens()).isNull();
    }

    @Test
    void chat_multiRoundToolCall_sumsTokenUsageAcrossEveryModelCall() {
        ChatModel model = mock(ChatModel.class);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("1").name("echo").arguments("{\"message\":\"hello\"}").build();

        when(model.chat(any(ChatRequest.class)))
                .thenReturn(response(AiMessage.from(List.of(request)), new TokenUsage(100, 20, 120)))
                .thenReturn(response(AiMessage.from("Final answer using tool result"), new TokenUsage(150, 30, 180)));

        ToolCallingChatEngine.ToolChatResult result = new ToolCallingChatEngine(model, List.of(echoTool), CONTEXT).chat("Echo 'hello'");

        assertThat(result.inputTokens()).isEqualTo(250);
        assertThat(result.outputTokens()).isEqualTo(50);
        assertThat(result.totalTokens()).isEqualTo(300);
    }

    @Test
    void chat_finalResponseTruncatedByMaxTokens_markedIncompleteWithAReason() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class)))
                .thenReturn(response(AiMessage.from("Let me check if there's a README on"), null, FinishReason.LENGTH));

        ToolCallingChatEngine engine = new ToolCallingChatEngine(model, List.of(echoTool), CONTEXT);
        ToolCallingChatEngine.ToolChatResult result = engine.chat("do the thing");

        // A response cut off by the token limit is not a real final answer --
        // silently reporting it as one (the pre-fix behavior) means a task can
        // end early with no error anywhere the caller can see. This is exactly
        // the failure mode live-verified against ticket-resolver.
        assertThat(result.incomplete()).isTrue();
        assertThat(result.incompleteReason()).contains("max_tokens");
    }

    @Test
    void chat_modelRequestsToolCall_toolExecutedAndResultFedBack() {
        ChatModel model = mock(ChatModel.class);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("1")
                .name("echo")
                .arguments("{\"message\":\"hello\"}")
                .build();

        when(model.chat(any(ChatRequest.class)))
                .thenReturn(response(AiMessage.from(List.of(request))))
                .thenReturn(response(AiMessage.from("Final answer using tool result")));

        ToolCallingChatEngine engine = new ToolCallingChatEngine(model, List.of(echoTool), CONTEXT);
        ToolCallingChatEngine.ToolChatResult result = engine.chat("Echo 'hello'");

        assertThat(result.toolWasUsed()).isTrue();
        assertThat(result.reply()).isEqualTo("Final answer using tool result");
        verify(model, times(2)).chat(any(ChatRequest.class));
    }

    @Test
    void chat_toolReceivesTheExecutionContextPassedToTheEngine() {
        AtomicReference<ToolExecutionContext> observed = new AtomicReference<>();
        AgentTool observingTool = new AgentTool() {
            @Override
            public String name() {
                return "observe";
            }

            @Override
            public String description() {
                return "records the context it was called with";
            }

            @Override
            public Map<String, String> parameterDescriptions() {
                return Map.of();
            }

            @Override
            public String execute(ToolExecutionContext context, Map<String, String> arguments) {
                observed.set(context);
                return "ok";
            }
        };

        ChatModel model = mock(ChatModel.class);
        ToolExecutionRequest request = ToolExecutionRequest.builder().id("1").name("observe").arguments("{}").build();
        when(model.chat(any(ChatRequest.class)))
                .thenReturn(response(AiMessage.from(List.of(request))))
                .thenReturn(response(AiMessage.from("done")));

        new ToolCallingChatEngine(model, List.of(observingTool), CONTEXT).chat("go");

        assertThat(observed.get()).isEqualTo(CONTEXT);
        assertThat(observed.get().tenantId()).isEqualTo("tenant-1");
        assertThat(observed.get().executionId()).isEqualTo("exec-1");
    }

    @Test
    void chat_toolResultIsFedBackAsToolExecutionResultMessage_withCorrectText() {
        ChatModel model = mock(ChatModel.class);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("1").name("echo").arguments("{\"message\":\"hello\"}").build();

        when(model.chat(any(ChatRequest.class)))
                .thenReturn(response(AiMessage.from(List.of(request))))
                .thenReturn(response(AiMessage.from("done")));

        new ToolCallingChatEngine(model, List.of(echoTool), CONTEXT).chat("Echo 'hello'");

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model, times(2)).chat(captor.capture());

        List<ChatMessage> secondCallMessages = captor.getAllValues().get(1).messages();
        ToolExecutionResultMessage resultMessage = secondCallMessages.stream()
                .filter(ToolExecutionResultMessage.class::isInstance)
                .map(ToolExecutionResultMessage.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(resultMessage.text()).isEqualTo("echo: hello");
    }

    @Test
    void chat_unknownToolRequested_feedsBackErrorInsteadOfThrowing() {
        ChatModel model = mock(ChatModel.class);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("1").name("does_not_exist").arguments("{}").build();

        when(model.chat(any(ChatRequest.class)))
                .thenReturn(response(AiMessage.from(List.of(request))))
                .thenReturn(response(AiMessage.from("handled gracefully")));

        ToolCallingChatEngine.ToolChatResult result = new ToolCallingChatEngine(model, List.of(echoTool), CONTEXT).chat("call ghost tool");

        assertThat(result.reply()).isEqualTo("handled gracefully");
    }

    @Test
    void chat_toolThatThrows_feedsBackErrorInsteadOfPropagating() {
        AgentTool failingTool = new AgentTool() {
            @Override
            public String name() {
                return "boom";
            }

            @Override
            public String description() {
                return "always fails";
            }

            @Override
            public Map<String, String> parameterDescriptions() {
                return Map.of();
            }

            @Override
            public String execute(ToolExecutionContext context, Map<String, String> arguments) {
                throw new RuntimeException("kaboom");
            }
        };

        ChatModel model = mock(ChatModel.class);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("1").name("boom").arguments("{}").build();

        when(model.chat(any(ChatRequest.class)))
                .thenReturn(response(AiMessage.from(List.of(request))))
                .thenReturn(response(AiMessage.from("recovered")));

        ToolCallingChatEngine.ToolChatResult result = new ToolCallingChatEngine(model, List.of(failingTool), CONTEXT).chat("trigger boom");

        assertThat(result.reply()).isEqualTo("recovered");
    }

    @Test
    void constructor_buildsToolSpecificationFromAgentToolMetadata() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(response(AiMessage.from("ok")));

        new ToolCallingChatEngine(model, List.of(echoTool), CONTEXT).chat("hi");

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model).chat(captor.capture());

        ToolSpecification spec = captor.getValue().toolSpecifications().get(0);
        assertThat(spec.name()).isEqualTo("echo");
        assertThat(spec.description()).isEqualTo("Echoes back the given message");
        assertThat(spec.parameters().properties()).containsKey("message");
        // echoTool declares no optional parameters -- "message" stays required,
        // matching every tool's behavior before optionalParameterNames() existed.
        assertThat(spec.parameters().required()).contains("message");
    }

    @Test
    void constructor_toolWithOptionalParameter_excludedFromTheRequiredList() {
        AgentTool toolWithOptionalArg = new AgentTool() {
            @Override
            public String name() {
                return "maybe_greet";
            }

            @Override
            public String description() {
                return "Greets someone, optionally by name";
            }

            @Override
            public Map<String, String> parameterDescriptions() {
                return Map.of("greeting", "the greeting word", "name", "optional -- who to greet");
            }

            @Override
            public Set<String> optionalParameterNames() {
                return Set.of("name");
            }

            @Override
            public String execute(ToolExecutionContext context, Map<String, String> arguments) {
                return arguments.getOrDefault("greeting", "hi") + " " + arguments.getOrDefault("name", "there");
            }
        };

        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(response(AiMessage.from("ok")));

        new ToolCallingChatEngine(model, List.of(toolWithOptionalArg), CONTEXT).chat("hi");

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model).chat(captor.capture());

        ToolSpecification spec = captor.getValue().toolSpecifications().get(0);
        assertThat(spec.parameters().properties()).containsKeys("greeting", "name");
        assertThat(spec.parameters().required()).contains("greeting").doesNotContain("name");
    }

    @Test
    void chat_toolCallOmitsAnOptionalArgument_toolReceivesItAsAbsentNotAsTheStringNull() {
        AtomicReference<Map<String, String>> observedArguments = new AtomicReference<>();
        AgentTool toolWithOptionalArg = new AgentTool() {
            @Override
            public String name() {
                return "maybe_greet";
            }

            @Override
            public String description() {
                return "records the arguments it was called with";
            }

            @Override
            public Map<String, String> parameterDescriptions() {
                return Map.of("greeting", "the greeting word", "name", "optional -- who to greet");
            }

            @Override
            public Set<String> optionalParameterNames() {
                return Set.of("name");
            }

            @Override
            public String execute(ToolExecutionContext context, Map<String, String> arguments) {
                observedArguments.set(arguments);
                return "ok";
            }
        };

        ChatModel model = mock(ChatModel.class);
        // The model omits "name" entirely, exactly as expected when it's
        // declared optional and the model has nothing to supply for it.
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("1").name("maybe_greet").arguments("{\"greeting\":\"hi\"}").build();
        when(model.chat(any(ChatRequest.class)))
                .thenReturn(response(AiMessage.from(List.of(request))))
                .thenReturn(response(AiMessage.from("done")));

        new ToolCallingChatEngine(model, List.of(toolWithOptionalArg), CONTEXT).chat("greet");

        assertThat(observedArguments.get()).containsEntry("greeting", "hi");
        assertThat(observedArguments.get()).doesNotContainKey("name");
    }

    @Test
    void chat_toolCallSendsExplicitJsonNullForAnArgument_toolReceivesItAsAbsentNotAsTheStringNull() {
        AtomicReference<Map<String, String>> observedArguments = new AtomicReference<>();
        AgentTool toolWithOptionalArg = new AgentTool() {
            @Override
            public String name() {
                return "maybe_greet";
            }

            @Override
            public String description() {
                return "records the arguments it was called with";
            }

            @Override
            public Map<String, String> parameterDescriptions() {
                return Map.of("greeting", "the greeting word", "name", "optional -- who to greet");
            }

            @Override
            public Set<String> optionalParameterNames() {
                return Set.of("name");
            }

            @Override
            public String execute(ToolExecutionContext context, Map<String, String> arguments) {
                observedArguments.set(arguments);
                return "ok";
            }
        };

        ChatModel model = mock(ChatModel.class);
        // Some models send an explicit JSON null for a declined optional
        // argument instead of omitting the key -- this must be treated the
        // same as omission, not turned into the literal string "null".
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("1").name("maybe_greet").arguments("{\"greeting\":\"hi\",\"name\":null}").build();
        when(model.chat(any(ChatRequest.class)))
                .thenReturn(response(AiMessage.from(List.of(request))))
                .thenReturn(response(AiMessage.from("done")));

        new ToolCallingChatEngine(model, List.of(toolWithOptionalArg), CONTEXT).chat("greet");

        assertThat(observedArguments.get()).containsEntry("greeting", "hi");
        assertThat(observedArguments.get()).doesNotContainKey("name");
    }

    @Test
    void chat_threeRoundsOfToolCalls_allExecuted_finalAnswerAfterThirdRound() {
        ChatModel model = mock(ChatModel.class);
        ToolExecutionRequest request1 = ToolExecutionRequest.builder().id("1").name("echo").arguments("{\"message\":\"one\"}").build();
        ToolExecutionRequest request2 = ToolExecutionRequest.builder().id("2").name("echo").arguments("{\"message\":\"two\"}").build();
        ToolExecutionRequest request3 = ToolExecutionRequest.builder().id("3").name("echo").arguments("{\"message\":\"three\"}").build();

        when(model.chat(any(ChatRequest.class)))
                .thenReturn(response(AiMessage.from(List.of(request1))))
                .thenReturn(response(AiMessage.from(List.of(request2))))
                .thenReturn(response(AiMessage.from(List.of(request3))))
                .thenReturn(response(AiMessage.from("Done after three rounds")));

        ToolCallingChatEngine engine = new ToolCallingChatEngine(model, List.of(echoTool), CONTEXT);
        ToolCallingChatEngine.ToolChatResult result = engine.chat("do three things");

        assertThat(result.toolWasUsed()).isTrue();
        assertThat(result.reply()).isEqualTo("Done after three rounds");
        verify(model, times(4)).chat(any(ChatRequest.class));
    }

    @Test
    void chat_modelNeverStopsRequestingTools_stopsAtMaxRoundsAndForcesTextAnswer() {
        ChatModel model = mock(ChatModel.class);
        ToolExecutionRequest infiniteRequest = ToolExecutionRequest.builder().id("1").name("echo").arguments("{\"message\":\"again\"}").build();

        // Always offers another tool call -- never settles on a text answer by itself.
        when(model.chat(any(ChatRequest.class))).thenReturn(response(AiMessage.from(List.of(infiniteRequest))));

        ToolCallingChatEngine engine = new ToolCallingChatEngine(model, List.of(echoTool), CONTEXT);
        ToolCallingChatEngine.ToolChatResult result = engine.chat("never stop");

        // DEFAULT_MAX_TOOL_ROUNDS rounds of tool-offering calls, plus one final forced call.
        verify(model, times(ToolCallingChatEngine.DEFAULT_MAX_TOOL_ROUNDS + 1)).chat(any(ChatRequest.class));
        assertThat(result.toolWasUsed()).isTrue();
        // Hitting the round cap is not a real stopping point either -- the
        // model still wanted to keep calling tools -- so this must be flagged
        // the same way a truncated response is, not reported as a clean success.
        assertThat(result.incomplete()).isTrue();
        assertThat(result.incompleteReason()).contains(String.valueOf(ToolCallingChatEngine.DEFAULT_MAX_TOOL_ROUNDS));
    }

    @Test
    void chat_customMaxToolRounds_stopsAtConfiguredCapNotTheDefault_endsCleanlyNotAmbiguously() {
        // Defense-in-depth: even with a terminal tool never marked (isTerminalSuccess
        // stays false by default) and no token budget configured, a caller-supplied
        // maxToolRounds must still cap the loop on its own -- and end with a clear
        // incomplete status, not hang or surface an unclear error.
        ChatModel model = mock(ChatModel.class);
        ToolExecutionRequest infiniteRequest = ToolExecutionRequest.builder().id("1").name("echo").arguments("{\"message\":\"again\"}").build();
        when(model.chat(argThat((ChatRequest req) -> requestsTools(req))))
                .thenReturn(response(AiMessage.from(List.of(infiniteRequest))));
        when(model.chat(argThat((ChatRequest req) -> requestsNoTools(req))))
                .thenReturn(response(AiMessage.from("forced final answer")));

        ToolCallingChatEngine engine = new ToolCallingChatEngine(model, List.of(echoTool), CONTEXT, null, null, 5);
        ToolCallingChatEngine.ToolChatResult result = engine.chat("never stop");

        // 5 tool-offering rounds (the configured cap, NOT DEFAULT_MAX_TOOL_ROUNDS'
        // 100) plus one final forced call -- proves the override actually took effect.
        verify(model, times(5)).chat(argThat((ChatRequest req) -> requestsTools(req)));
        verify(model, times(1)).chat(argThat((ChatRequest req) -> requestsNoTools(req)));
        assertThat(result.reply()).isEqualTo("forced final answer");
        assertThat(result.toolWasUsed()).isTrue();
        assertThat(result.incomplete()).isTrue();
        assertThat(result.incompleteReason()).isEqualTo("Agent used all 5 allowed tool-call rounds without finishing.");
    }

    @Test
    void chat_atMaxRounds_finalForcedCall_passesNoToolSpecifications() {
        ChatModel model = mock(ChatModel.class);
        ToolExecutionRequest infiniteRequest = ToolExecutionRequest.builder().id("1").name("echo").arguments("{\"message\":\"again\"}").build();
        when(model.chat(argThat((ChatRequest req) -> requestsTools(req))))
                .thenReturn(response(AiMessage.from(List.of(infiniteRequest))));
        when(model.chat(argThat((ChatRequest req) -> requestsNoTools(req))))
                .thenReturn(response(AiMessage.from("forced final answer")));

        ToolCallingChatEngine engine = new ToolCallingChatEngine(model, List.of(echoTool), CONTEXT);
        ToolCallingChatEngine.ToolChatResult result = engine.chat("never stop");

        assertThat(result.reply()).isEqualTo("forced final answer");
    }

    @Test
    void chat_tokenBudgetExceeded_stopsBeforeNextRoundAndForcesTextAnswer() {
        ChatModel model = mock(ChatModel.class);
        ToolExecutionRequest infiniteRequest = ToolExecutionRequest.builder().id("1").name("echo").arguments("{\"message\":\"again\"}").build();

        // Every round costs 1000 tokens -- with a 2000 budget, round 3 must
        // never happen: round 1 lands at 1000 (under), round 2 at 2000
        // (meets the budget), and the check ahead of round 3 is what stops
        // it, not maxToolRounds (100, nowhere close).
        when(model.chat(argThat((ChatRequest req) -> requestsTools(req))))
                .thenReturn(response(AiMessage.from(List.of(infiniteRequest)), new TokenUsage(900, 100, 1000)));
        when(model.chat(argThat((ChatRequest req) -> requestsNoTools(req))))
                .thenReturn(response(AiMessage.from("forced final answer"), new TokenUsage(50, 10, 60)));

        ToolCallingChatEngine engine = new ToolCallingChatEngine(model, List.of(echoTool), CONTEXT, null, 2000);
        ToolCallingChatEngine.ToolChatResult result = engine.chat("never stop");

        // Exactly 2 tool-offering rounds (2000 tokens) plus 1 forced final call.
        verify(model, times(2)).chat(argThat((ChatRequest req) -> requestsTools(req)));
        verify(model, times(1)).chat(argThat((ChatRequest req) -> requestsNoTools(req)));
        assertThat(result.incomplete()).isTrue();
        assertThat(result.incompleteReason()).contains("token budget").contains("2000");
        assertThat(result.totalTokens()).isEqualTo(2060);
    }

    @Test
    void chat_noBudgetConfigured_ignoresTokenUsageEntirely_onlyRoundCapApplies() {
        // maxTokensBudget null (the pre-budget constructor) must behave
        // byte-identical to before this feature existed -- huge per-round
        // usage should never trigger an early stop on its own.
        ChatModel model = mock(ChatModel.class);
        ToolExecutionRequest infiniteRequest = ToolExecutionRequest.builder().id("1").name("echo").arguments("{\"message\":\"again\"}").build();
        when(model.chat(any(ChatRequest.class)))
                .thenReturn(response(AiMessage.from(List.of(infiniteRequest)), new TokenUsage(900_000, 100_000, 1_000_000)));

        ToolCallingChatEngine engine = new ToolCallingChatEngine(model, List.of(echoTool), CONTEXT);
        ToolCallingChatEngine.ToolChatResult result = engine.chat("never stop");

        verify(model, times(ToolCallingChatEngine.DEFAULT_MAX_TOOL_ROUNDS + 1)).chat(any(ChatRequest.class));
        assertThat(result.incompleteReason()).contains(String.valueOf(ToolCallingChatEngine.DEFAULT_MAX_TOOL_ROUNDS));
    }

    @Test
    void chat_budgetSetButProviderNeverReportsUsage_neverTriggers_roundCapStillCatchesIt() {
        // totalUsage stays null forever when nothing reports usage -- budgetExceeded()
        // can't fire on a null total, so maxToolRounds is the only backstop left.
        ChatModel model = mock(ChatModel.class);
        ToolExecutionRequest infiniteRequest = ToolExecutionRequest.builder().id("1").name("echo").arguments("{\"message\":\"again\"}").build();
        when(model.chat(any(ChatRequest.class))).thenReturn(response(AiMessage.from(List.of(infiniteRequest))));

        ToolCallingChatEngine engine = new ToolCallingChatEngine(model, List.of(echoTool), CONTEXT, null, 100);
        ToolCallingChatEngine.ToolChatResult result = engine.chat("never stop");

        verify(model, times(ToolCallingChatEngine.DEFAULT_MAX_TOOL_ROUNDS + 1)).chat(any(ChatRequest.class));
        assertThat(result.incompleteReason()).contains(String.valueOf(ToolCallingChatEngine.DEFAULT_MAX_TOOL_ROUNDS));
    }

    @Test
    void chat_toolReportsTerminalSuccess_stopsImmediatelyInsteadOfOfferingAnotherRound() {
        AgentTool prLikeTool = new AgentTool() {
            @Override
            public String name() {
                return "open_pull_request";
            }

            @Override
            public String description() {
                return "opens a PR";
            }

            @Override
            public Map<String, String> parameterDescriptions() {
                return Map.of();
            }

            @Override
            public String execute(ToolExecutionContext context, Map<String, String> arguments) {
                return "Pull request opened successfully: https://github.com/acme/repo/pull/1";
            }

            @Override
            public boolean isTerminalSuccess(String result) {
                return result != null && result.startsWith("Pull request opened successfully:");
            }
        };

        ChatModel model = mock(ChatModel.class);
        ToolExecutionRequest request = ToolExecutionRequest.builder().id("1").name("open_pull_request").arguments("{}").build();
        // If the loop didn't force a stop, this stub would keep returning
        // another tool request forever -- exactly the live-observed failure
        // (the model kept calling run_shell_command after the PR was already
        // opened, burning the rest of the round budget).
        when(model.chat(argThat((ChatRequest req) -> requestsTools(req))))
                .thenReturn(response(AiMessage.from(List.of(request))))
                .thenReturn(response(AiMessage.from(List.of(request))))
                .thenReturn(response(AiMessage.from("should never be reached this way")));
        when(model.chat(argThat((ChatRequest req) -> requestsNoTools(req))))
                .thenReturn(response(AiMessage.from("PR opened, done.")));

        ToolCallingChatEngine engine = new ToolCallingChatEngine(model, List.of(prLikeTool), CONTEXT);
        ToolCallingChatEngine.ToolChatResult result = engine.chat("open a PR");

        // One round offering tools (which returns the PR-opening call), then
        // one forced text-only call -- not the infinite/near-cap loop.
        verify(model, times(2)).chat(any(ChatRequest.class));
        verify(model, times(1)).chat(argThat((ChatRequest req) -> requestsNoTools(req)));
        assertThat(result.reply()).isEqualTo("PR opened, done.");
        assertThat(result.toolWasUsed()).isTrue();
        // This IS a genuine stopping point -- the goal was achieved -- unlike
        // hitting the round cap, so it must not be flagged incomplete.
        assertThat(result.incomplete()).isFalse();
        assertThat(result.incompleteReason()).isNull();
    }

    @Test
    void chat_toolReportsNonTerminalResult_loopContinuesNormally() {
        AgentTool prLikeTool = new AgentTool() {
            @Override
            public String name() {
                return "open_pull_request";
            }

            @Override
            public String description() {
                return "opens a PR";
            }

            @Override
            public Map<String, String> parameterDescriptions() {
                return Map.of();
            }

            @Override
            public String execute(ToolExecutionContext context, Map<String, String> arguments) {
                return "Tests FAILED -- pull request was NOT opened.";
            }

            @Override
            public boolean isTerminalSuccess(String result) {
                return result != null && result.startsWith("Pull request opened successfully:");
            }
        };

        ChatModel model = mock(ChatModel.class);
        ToolExecutionRequest request = ToolExecutionRequest.builder().id("1").name("open_pull_request").arguments("{}").build();
        when(model.chat(any(ChatRequest.class)))
                .thenReturn(response(AiMessage.from(List.of(request))))
                .thenReturn(response(AiMessage.from("tests failed, giving up")));

        ToolCallingChatEngine engine = new ToolCallingChatEngine(model, List.of(prLikeTool), CONTEXT);
        ToolCallingChatEngine.ToolChatResult result = engine.chat("open a PR");

        // A failed PR attempt is not terminal success -- the model gets
        // offered tools again and decides for itself when to stop, same as
        // any other tool.
        verify(model, times(2)).chat(any(ChatRequest.class));
        assertThat(result.reply()).isEqualTo("tests failed, giving up");
        assertThat(result.incomplete()).isFalse();
    }

    @Test
    void chat_withSystemPrompt_prependsSystemMessage() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(response(AiMessage.from("ok")));

        new ToolCallingChatEngine(model, List.of(echoTool), CONTEXT, "You are a coding agent.").chat("hi");

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model).chat(captor.capture());
        List<ChatMessage> messages = captor.getValue().messages();
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0)).isInstanceOf(dev.langchain4j.data.message.SystemMessage.class);
        assertThat(((dev.langchain4j.data.message.SystemMessage) messages.get(0)).text()).isEqualTo("You are a coding agent.");
        assertThat(messages.get(1)).isInstanceOf(UserMessage.class);
    }

    @Test
    void chat_noSystemPrompt_3argConstructor_onlySendsUserMessage() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(response(AiMessage.from("ok")));

        new ToolCallingChatEngine(model, List.of(echoTool), CONTEXT).chat("hi");

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model).chat(captor.capture());
        assertThat(captor.getValue().messages()).hasSize(1);
    }

    @Test
    void chat_blankSystemPrompt_treatedAsNoSystemPrompt() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(response(AiMessage.from("ok")));

        new ToolCallingChatEngine(model, List.of(echoTool), CONTEXT, "   ").chat("hi");

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model).chat(captor.capture());
        assertThat(captor.getValue().messages()).hasSize(1);
    }

    @Test
    void chat_firstCall_sendsOnlyTheUserMessage() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(response(AiMessage.from("ok")));

        new ToolCallingChatEngine(model, List.of(echoTool), CONTEXT).chat("hi there");

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model).chat(captor.capture());

        assertThat(captor.getValue().messages()).hasSize(1);
        assertThat(captor.getValue().messages().get(0)).isInstanceOf(UserMessage.class);
    }
}
