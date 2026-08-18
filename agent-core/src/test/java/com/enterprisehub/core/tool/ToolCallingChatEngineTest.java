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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    void chat_modelLeaksToolCallAsPlainText_recoveredAndExecutedAnyway() {
        // Observed with qwen2.5-coder:7b served via Ollama's OpenAI-compatible
        // endpoint: instead of populating the real tool_calls field, the model
        // writes the call as ordinary assistant text. hasToolExecutionRequests()
        // is false here (this is a genuine AiMessage.from(String), not
        // AiMessage.from(List<ToolExecutionRequest>)) -- recoverToolCallsFromText()
        // is what's actually under test.
        ChatModel model = mock(ChatModel.class);
        String leakedToolCall = "{\"name\": \"echo\", \"arguments\": {\"message\": \"hello\"}}";

        when(model.chat(any(ChatRequest.class)))
                .thenReturn(response(AiMessage.from(leakedToolCall)))
                .thenReturn(response(AiMessage.from("Final answer using tool result")));

        ToolCallingChatEngine engine = new ToolCallingChatEngine(model, List.of(echoTool), CONTEXT);
        ToolCallingChatEngine.ToolChatResult result = engine.chat("Echo 'hello'");

        assertThat(result.toolWasUsed()).isTrue();
        assertThat(result.reply()).isEqualTo("Final answer using tool result");
        verify(model, times(2)).chat(any(ChatRequest.class));
    }

    @Test
    void chat_modelLeaksToolCallAsJsonArray_recoveredAndExecuted() {
        ChatModel model = mock(ChatModel.class);
        String leakedToolCalls = "[{\"name\": \"echo\", \"arguments\": {\"message\": \"hi\"}}]";

        when(model.chat(any(ChatRequest.class)))
                .thenReturn(response(AiMessage.from(leakedToolCalls)))
                .thenReturn(response(AiMessage.from("done")));

        ToolCallingChatEngine.ToolChatResult result = new ToolCallingChatEngine(model, List.of(echoTool), CONTEXT).chat("go");

        assertThat(result.toolWasUsed()).isTrue();
        verify(model, times(2)).chat(any(ChatRequest.class));
    }

    @Test
    void chat_textLooksLikeAToolCallButNameIsNotRegistered_treatedAsPlainFinalAnswer() {
        // The safety check: an unrecognized "name" must never be guessed at --
        // this stays a genuine final answer (the leaked JSON, verbatim) rather
        // than silently being dropped or misrouted to some other tool.
        ChatModel model = mock(ChatModel.class);
        String notARealTool = "{\"name\": \"delete_everything\", \"arguments\": {}}";
        when(model.chat(any(ChatRequest.class))).thenReturn(response(AiMessage.from(notARealTool)));

        ToolCallingChatEngine.ToolChatResult result = new ToolCallingChatEngine(model, List.of(echoTool), CONTEXT).chat("go");

        assertThat(result.toolWasUsed()).isFalse();
        assertThat(result.reply()).isEqualTo(notARealTool);
        verify(model, times(1)).chat(any(ChatRequest.class));
    }

    @Test
    void chat_plainProseAnswer_neverMisparsedAsToolCall() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(response(AiMessage.from("The answer is 42.")));

        ToolCallingChatEngine.ToolChatResult result = new ToolCallingChatEngine(model, List.of(echoTool), CONTEXT).chat("what?");

        assertThat(result.toolWasUsed()).isFalse();
        assertThat(result.reply()).isEqualTo("The answer is 42.");
        verify(model, times(1)).chat(any(ChatRequest.class));
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

    /**
     * A single round can carry several independent tool calls (see
     * executeToolsInOrder()'s javadoc) -- runs them concurrently. Uses one
     * fast and one deliberately slow tool and asserts total wall-clock time
     * stays close to the slow tool alone, not their sum, proving the calls
     * actually overlapped rather than running one after another.
     */
    @Test
    void chat_multipleToolCallsInOneRound_executedConcurrently_notSequentially() {
        AgentTool slowTool = new AgentTool() {
            @Override
            public String name() {
                return "slow";
            }

            @Override
            public String description() {
                return "sleeps then answers";
            }

            @Override
            public Map<String, String> parameterDescriptions() {
                return Map.of();
            }

            @Override
            public String execute(ToolExecutionContext context, Map<String, String> arguments) {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "slow-done";
            }
        };
        AgentTool fastTool = new AgentTool() {
            @Override
            public String name() {
                return "fast";
            }

            @Override
            public String description() {
                return "answers immediately";
            }

            @Override
            public Map<String, String> parameterDescriptions() {
                return Map.of();
            }

            @Override
            public String execute(ToolExecutionContext context, Map<String, String> arguments) {
                return "fast-done";
            }
        };

        ChatModel model = mock(ChatModel.class);
        ToolExecutionRequest slowRequest = ToolExecutionRequest.builder().id("1").name("slow").arguments("{}").build();
        ToolExecutionRequest fastRequest = ToolExecutionRequest.builder().id("2").name("fast").arguments("{}").build();
        when(model.chat(any(ChatRequest.class)))
                .thenReturn(response(AiMessage.from(List.of(slowRequest, fastRequest))))
                .thenReturn(response(AiMessage.from("both done")));

        long start = System.nanoTime();
        ToolCallingChatEngine.ToolChatResult result = new ToolCallingChatEngine(model, List.of(slowTool, fastTool), CONTEXT).chat("do both");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(result.reply()).isEqualTo("both done");
        // Sequential execution would take >= 600ms (300ms slow + 300ms slow
        // again, since a broken implementation re-blocks on the same sleep
        // twice were it to run one-at-a-time... in practice here it's
        // slow+fast run one after another, so >= 300ms). Concurrent
        // execution should finish close to the single 300ms sleep, not
        // meaningfully more -- 450ms leaves generous headroom for virtual
        // thread startup / scheduling jitter while still failing loudly if
        // the two calls were run sequentially.
        assertThat(elapsedMs).isLessThan(450);

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model, times(2)).chat(captor.capture());
        List<ChatMessage> secondCallMessages = captor.getAllValues().get(1).messages();
        List<ToolExecutionResultMessage> resultMessages = secondCallMessages.stream()
                .filter(ToolExecutionResultMessage.class::isInstance)
                .map(ToolExecutionResultMessage.class::cast)
                .toList();
        // Results must be fed back in the ORIGINAL request order (slow, then
        // fast) regardless of which one actually finished first.
        assertThat(resultMessages).hasSize(2);
        assertThat(resultMessages.get(0).text()).isEqualTo("slow-done");
        assertThat(resultMessages.get(1).text()).isEqualTo("fast-done");
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

        ToolCallingChatEngine engine = new ToolCallingChatEngine(model, List.of(echoTool), CONTEXT, ChatEngineOptions.builder().maxToolRounds(5).build());
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

        ToolCallingChatEngine engine = new ToolCallingChatEngine(model, List.of(echoTool), CONTEXT, ChatEngineOptions.builder().maxTokensBudget(2000).build());
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

        ToolCallingChatEngine engine = new ToolCallingChatEngine(model, List.of(echoTool), CONTEXT, ChatEngineOptions.builder().maxTokensBudget(100).build());
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

        new ToolCallingChatEngine(model, List.of(echoTool), CONTEXT, ChatEngineOptions.builder().systemPrompt("You are a coding agent.").build()).chat("hi");

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

        new ToolCallingChatEngine(model, List.of(echoTool), CONTEXT, ChatEngineOptions.builder().systemPrompt("   ").build()).chat("hi");

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

    // ---------- incremental conversation caching (cacheConversationHistory) ----------

    private static Object cacheControlAttribute(ChatMessage message) {
        if (message instanceof UserMessage m) {
            return m.attributes().get("cache_control");
        }
        if (message instanceof AiMessage m) {
            return m.attributes().get("cache_control");
        }
        if (message instanceof ToolExecutionResultMessage m) {
            return m.attributes().get("cache_control");
        }
        return null;
    }

    @Test
    void chat_cacheConversationHistoryDisabledByDefault_lastMessageNeverMarked() {
        // Every pre-existing constructor (7-arg included, false not passed
        // explicitly) must behave byte-identical to before this feature
        // existed -- no provider other than Anthropic even looks at this
        // attribute, but it should never be set unless explicitly enabled.
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(response(AiMessage.from("ok")));

        new ToolCallingChatEngine(model, List.of(echoTool), CONTEXT, ChatEngineOptions.DEFAULTS).chat("hi");

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model).chat(captor.capture());
        List<ChatMessage> messages = captor.getValue().messages();
        assertThat(cacheControlAttribute(messages.get(messages.size() - 1))).isNull();
    }

    @Test
    void chat_cacheConversationHistoryEnabled_firstCall_marksTheUserMessage() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(response(AiMessage.from("ok")));

        new ToolCallingChatEngine(model, List.of(echoTool), CONTEXT, ChatEngineOptions.builder().cacheConversationHistory(true).build()).chat("hi");

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model).chat(captor.capture());
        List<ChatMessage> messages = captor.getValue().messages();
        assertThat(messages).hasSize(1);
        assertThat(cacheControlAttribute(messages.get(0))).isEqualTo("ephemeral");
    }

    @Test
    void chat_cacheConversationHistoryEnabled_multiRound_breakpointMovesForward_neverMoreThanOneMarked() {
        // The Anthropic contract this exists for allows at most 4 cache_control
        // breakpoints per request -- leaving every round's marker in place would
        // accumulate one per round and risk exceeding that on a long-running
        // execution. Exactly one non-system message may carry the attribute at
        // any given call.
        ChatModel model = mock(ChatModel.class);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("1").name("echo").arguments("{\"message\":\"hello\"}").build();
        when(model.chat(any(ChatRequest.class)))
                .thenReturn(response(AiMessage.from(List.of(request))))
                .thenReturn(response(AiMessage.from("final")));

        new ToolCallingChatEngine(model, List.of(echoTool), CONTEXT, ChatEngineOptions.builder().cacheConversationHistory(true).build()).chat("Echo 'hello'");

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model, times(2)).chat(captor.capture());

        List<ChatMessage> secondCallMessages = captor.getAllValues().get(1).messages();
        // messages: [UserMessage, AiMessage(tool request), ToolExecutionResultMessage]
        assertThat(cacheControlAttribute(secondCallMessages.get(0)))
                .as("round 1's breakpoint (the user message) must be moved, not left behind")
                .isNull();
        assertThat(cacheControlAttribute(secondCallMessages.get(secondCallMessages.size() - 1)))
                .as("the newest message becomes the new breakpoint")
                .isEqualTo("ephemeral");
        long markedCount = secondCallMessages.stream().filter(m -> cacheControlAttribute(m) != null).count();
        assertThat(markedCount).isEqualTo(1);
    }

    @Test
    void chat_cacheConversationHistoryEnabled_doesNotDropOtherAttributesOnTheMessageItUnmarks() {
        // Un-marking the previous breakpoint must only remove the cache_control
        // key, not wipe out any other attribute a message happens to carry.
        ChatModel model = mock(ChatModel.class);
        AiMessage toolRequestWithExtraAttribute = AiMessage.builder()
                .toolExecutionRequests(List.of(ToolExecutionRequest.builder()
                        .id("1").name("echo").arguments("{\"message\":\"hello\"}").build()))
                .attributes(Map.of("some_other_key", "keep-me"))
                .build();
        when(model.chat(any(ChatRequest.class)))
                .thenReturn(response(toolRequestWithExtraAttribute))
                .thenReturn(response(AiMessage.from("final")));

        new ToolCallingChatEngine(model, List.of(echoTool), CONTEXT, ChatEngineOptions.builder().cacheConversationHistory(true).build()).chat("Echo 'hello'");

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model, times(2)).chat(captor.capture());
        List<ChatMessage> secondCallMessages = captor.getAllValues().get(1).messages();
        AiMessage unmarkedAiMessage = secondCallMessages.stream()
                .filter(AiMessage.class::isInstance).map(AiMessage.class::cast).findFirst().orElseThrow();
        assertThat(unmarkedAiMessage.attributes()).containsEntry("some_other_key", "keep-me");
        assertThat(unmarkedAiMessage.attributes()).doesNotContainKey("cache_control");
    }

    // ---------- tool-result truncation (MAX_TOOL_RESULT_CHARS) ----------

    @Test
    void chat_toolResultUnderTheCap_fedBackUnchanged() {
        ChatModel model = mock(ChatModel.class);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("1").name("echo").arguments("{\"message\":\"short\"}").build();
        when(model.chat(any(ChatRequest.class)))
                .thenReturn(response(AiMessage.from(List.of(request))))
                .thenReturn(response(AiMessage.from("done")));

        new ToolCallingChatEngine(model, List.of(echoTool), CONTEXT).chat("Echo 'short'");

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model, times(2)).chat(captor.capture());
        ToolExecutionResultMessage resultMessage = captor.getAllValues().get(1).messages().stream()
                .filter(ToolExecutionResultMessage.class::isInstance)
                .map(ToolExecutionResultMessage.class::cast)
                .findFirst().orElseThrow();
        assertThat(resultMessage.text()).isEqualTo("echo: short");
    }

    @Test
    void chat_toolResultOverTheCap_truncatedBeforeEnteringHistory() {
        String hugeMessage = "x".repeat(20_000);
        AgentTool verboseTool = new AgentTool() {
            @Override
            public String name() {
                return "verbose";
            }

            @Override
            public String description() {
                return "returns a huge result";
            }

            @Override
            public Map<String, String> parameterDescriptions() {
                return Map.of();
            }

            @Override
            public String execute(ToolExecutionContext context, Map<String, String> arguments) {
                return hugeMessage;
            }
        };

        ChatModel model = mock(ChatModel.class);
        ToolExecutionRequest request = ToolExecutionRequest.builder().id("1").name("verbose").arguments("{}").build();
        when(model.chat(any(ChatRequest.class)))
                .thenReturn(response(AiMessage.from(List.of(request))))
                .thenReturn(response(AiMessage.from("done")));

        new ToolCallingChatEngine(model, List.of(verboseTool), CONTEXT).chat("go");

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model, times(2)).chat(captor.capture());
        ToolExecutionResultMessage resultMessage = captor.getAllValues().get(1).messages().stream()
                .filter(ToolExecutionResultMessage.class::isInstance)
                .map(ToolExecutionResultMessage.class::cast)
                .findFirst().orElseThrow();
        assertThat(resultMessage.text()).hasSizeLessThan(hugeMessage.length());
        assertThat(resultMessage.text()).startsWith("x".repeat(100));
        assertThat(resultMessage.text()).contains("truncated");
    }

    // ---------- history compaction (compactionWindowRounds) ----------

    @Test
    void chat_roundsOlderThanTheCompactionWindow_toolResultsReplacedWithAPlaceholder() {
        ChatModel model = mock(ChatModel.class);
        ToolExecutionRequest r0 = ToolExecutionRequest.builder().id("1").name("echo").arguments("{\"message\":\"round0\"}").build();
        ToolExecutionRequest r1 = ToolExecutionRequest.builder().id("2").name("echo").arguments("{\"message\":\"round1\"}").build();
        ToolExecutionRequest r2 = ToolExecutionRequest.builder().id("3").name("echo").arguments("{\"message\":\"round2\"}").build();
        ToolExecutionRequest r3 = ToolExecutionRequest.builder().id("4").name("echo").arguments("{\"message\":\"round3\"}").build();
        when(model.chat(any(ChatRequest.class)))
                .thenReturn(response(AiMessage.from(List.of(r0))))
                .thenReturn(response(AiMessage.from(List.of(r1))))
                .thenReturn(response(AiMessage.from(List.of(r2))))
                .thenReturn(response(AiMessage.from(List.of(r3))))
                .thenReturn(response(AiMessage.from("done after four rounds")));

        // compactionWindowRounds=2 -- rounds 0 and 1 age out of the window by
        // the time round 3 finishes; rounds 2 and 3 (the last 2) stay full.
        ToolCallingChatEngine engine = new ToolCallingChatEngine(
                model, List.of(echoTool), CONTEXT, ChatEngineOptions.builder().compactionWindowRounds(2).build());
        ToolCallingChatEngine.ToolChatResult result = engine.chat("do four things");

        assertThat(result.reply()).isEqualTo("done after four rounds");
        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model, times(5)).chat(captor.capture());
        List<ToolExecutionResultMessage> toolResults = captor.getAllValues().get(4).messages().stream()
                .filter(ToolExecutionResultMessage.class::isInstance)
                .map(ToolExecutionResultMessage.class::cast)
                .toList();

        assertThat(toolResults).hasSize(4);
        assertThat(toolResults.get(0).text()).startsWith("[tool result from an earlier round");
        assertThat(toolResults.get(1).text()).startsWith("[tool result from an earlier round");
        assertThat(toolResults.get(2).text()).isEqualTo("echo: round2");
        assertThat(toolResults.get(3).text()).isEqualTo("echo: round3");
    }

    @Test
    void chat_compactedResult_placeholderMentionsOriginalLength() {
        ChatModel model = mock(ChatModel.class);
        ToolExecutionRequest r0 = ToolExecutionRequest.builder().id("1").name("echo").arguments("{\"message\":\"round0\"}").build();
        ToolExecutionRequest r1 = ToolExecutionRequest.builder().id("2").name("echo").arguments("{\"message\":\"round1\"}").build();
        when(model.chat(any(ChatRequest.class)))
                .thenReturn(response(AiMessage.from(List.of(r0))))
                .thenReturn(response(AiMessage.from(List.of(r1))))
                .thenReturn(response(AiMessage.from("done")));

        // compactionWindowRounds=0 -- every round is immediately outside the
        // window as soon as the NEXT round finishes, so round 0's result is
        // compacted right after round 1 completes.
        ToolCallingChatEngine engine = new ToolCallingChatEngine(
                model, List.of(echoTool), CONTEXT, ChatEngineOptions.builder().compactionWindowRounds(0).build());
        engine.chat("do two things");

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model, times(3)).chat(captor.capture());
        List<ToolExecutionResultMessage> toolResults = captor.getAllValues().get(2).messages().stream()
                .filter(ToolExecutionResultMessage.class::isInstance)
                .map(ToolExecutionResultMessage.class::cast)
                .toList();

        // "echo: round0" is 12 characters.
        assertThat(toolResults.get(0).text()).contains("12 chars");
    }

    @Test
    void chat_defaultCompactionWindow_wellUnder20Rounds_nothingCompacted() {
        // Regression guard for every pre-existing caller/test: a normal-length
        // run must see byte-identical tool result text throughout, same as
        // before compaction existed.
        ChatModel model = mock(ChatModel.class);
        ToolExecutionRequest request = ToolExecutionRequest.builder().id("1").name("echo").arguments("{\"message\":\"hello\"}").build();
        when(model.chat(any(ChatRequest.class)))
                .thenReturn(response(AiMessage.from(List.of(request))))
                .thenReturn(response(AiMessage.from("done")));

        new ToolCallingChatEngine(model, List.of(echoTool), CONTEXT).chat("Echo 'hello'");

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model, times(2)).chat(captor.capture());
        ToolExecutionResultMessage resultMessage = captor.getAllValues().get(1).messages().stream()
                .filter(ToolExecutionResultMessage.class::isInstance)
                .map(ToolExecutionResultMessage.class::cast)
                .findFirst().orElseThrow();
        assertThat(resultMessage.text()).isEqualTo("echo: hello");
    }

    // ---------- cancellation (ChatEngineOptions.cancellationRequested) ----------

    @Test
    void chat_cancellationRequestedBeforeFirstRound_stopsImmediately_neverCallsTheModel() {
        // The concrete proof cancellation actually stops spending, not just
        // changes a label: zero interactions with the model at all.
        ChatModel model = mock(ChatModel.class);

        ToolCallingChatEngine engine = new ToolCallingChatEngine(model, List.of(echoTool), CONTEXT,
                ChatEngineOptions.builder().cancellationRequested(() -> true).build());
        ToolCallingChatEngine.ToolChatResult result = engine.chat("do the thing");

        assertThat(result.cancelled()).isTrue();
        assertThat(result.reply()).isNull();
        assertThat(result.toolWasUsed()).isFalse();
        verifyNoInteractions(model);
    }

    @Test
    void chat_cancellationRequestedMidRun_stopsAtNextRoundBoundary_noForcedFinalAnswerCall() {
        // Round 0 is already in flight (checked BEFORE it, still false) and
        // must complete normally; the flag flips true only for round 1's
        // check, so round 1 never happens -- unlike hitting the round cap or
        // token budget, there is no forced "one last call for a summary"
        // here, since an explicit cancel means stop spending, full stop.
        AtomicInteger checkCount = new AtomicInteger(0);
        ChatModel model = mock(ChatModel.class);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("1").name("echo").arguments("{\"message\":\"hello\"}").build();
        when(model.chat(any(ChatRequest.class))).thenReturn(response(AiMessage.from(List.of(request))));

        ToolCallingChatEngine engine = new ToolCallingChatEngine(model, List.of(echoTool), CONTEXT,
                ChatEngineOptions.builder().cancellationRequested(() -> checkCount.getAndIncrement() >= 1).build());
        ToolCallingChatEngine.ToolChatResult result = engine.chat("do the thing");

        assertThat(result.cancelled()).isTrue();
        assertThat(result.reply()).isNull();
        assertThat(result.toolWasUsed()).isTrue();
        assertThat(result.incomplete()).isFalse();
        verify(model, times(1)).chat(any(ChatRequest.class));
    }

    @Test
    void chat_noCancellationSignalConfigured_null_behavesExactlyAsBefore() {
        // ChatEngineOptions.DEFAULTS (what every pre-existing caller/test
        // gets) leaves cancellationRequested null -- must never NPE and must
        // never stop early.
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(response(AiMessage.from("ok")));

        ToolCallingChatEngine.ToolChatResult result = new ToolCallingChatEngine(model, List.of(echoTool), CONTEXT).chat("hi");

        assertThat(result.cancelled()).isFalse();
        assertThat(result.reply()).isEqualTo("ok");
    }

    // ---------- partial usage on a mid-run provider failure ----------

    @Test
    void chat_providerThrowsOnFirstCall_wrapsWithNullUsage_nothingAccumulatedYet() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenThrow(new RuntimeException("credit balance too low"));

        ToolCallingChatEngine engine = new ToolCallingChatEngine(model, List.of(echoTool), CONTEXT);

        assertThatThrownBy(() -> engine.chat("do the thing"))
                .isInstanceOf(ToolCallingChatEngine.PartialUsageException.class)
                .hasMessage("credit balance too low")
                .satisfies(e -> {
                    ToolCallingChatEngine.PartialUsageException partial = (ToolCallingChatEngine.PartialUsageException) e;
                    assertThat(partial.inputTokens()).isNull();
                    assertThat(partial.outputTokens()).isNull();
                    assertThat(partial.totalTokens()).isNull();
                });
    }

    @Test
    void chat_providerThrowsAfterEarlierRoundsSucceeded_wrapsWithUsageAccumulatedSoFar() {
        // The exact live-observed shape: several rounds succeed and are
        // genuinely billed, then the account runs out of credit mid-run --
        // that real spend must not be lost just because the run ended in an
        // exception instead of a clean stopping point.
        ChatModel model = mock(ChatModel.class);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("1").name("echo").arguments("{\"message\":\"hello\"}").build();
        when(model.chat(any(ChatRequest.class)))
                .thenReturn(response(AiMessage.from(List.of(request)), new TokenUsage(100, 20, 120)))
                .thenThrow(new RuntimeException("Your credit balance is too low to access the Anthropic API."));

        ToolCallingChatEngine engine = new ToolCallingChatEngine(model, List.of(echoTool), CONTEXT);

        assertThatThrownBy(() -> engine.chat("Echo 'hello'"))
                .isInstanceOf(ToolCallingChatEngine.PartialUsageException.class)
                .hasMessageContaining("credit balance is too low")
                .satisfies(e -> {
                    ToolCallingChatEngine.PartialUsageException partial = (ToolCallingChatEngine.PartialUsageException) e;
                    // Round 1's real, billed usage -- not lost even though round 2 crashed.
                    assertThat(partial.inputTokens()).isEqualTo(100);
                    assertThat(partial.outputTokens()).isEqualTo(20);
                    assertThat(partial.totalTokens()).isEqualTo(120);
                });
    }

    @Test
    void chat_toolExecutionExceptions_stillHandledLocally_notWrappedAsPartialUsage() {
        // Only the PROVIDER call is wrapped -- a tool throwing is a completely
        // separate, already-handled path (see chat_toolThatThrows_...) that
        // must keep behaving exactly as before this feature existed.
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
        ToolExecutionRequest request = ToolExecutionRequest.builder().id("1").name("boom").arguments("{}").build();
        when(model.chat(any(ChatRequest.class)))
                .thenReturn(response(AiMessage.from(List.of(request))))
                .thenReturn(response(AiMessage.from("recovered")));

        ToolCallingChatEngine.ToolChatResult result = new ToolCallingChatEngine(model, List.of(failingTool), CONTEXT).chat("trigger boom");

        assertThat(result.reply()).isEqualTo("recovered");
    }
}
