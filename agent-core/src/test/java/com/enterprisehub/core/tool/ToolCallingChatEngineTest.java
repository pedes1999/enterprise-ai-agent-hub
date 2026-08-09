package com.enterprisehub.core.tool;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class ToolCallingChatEngineTest {

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
        public String execute(Map<String, String> arguments) {
            return "echo: " + arguments.get("message");
        }
    };

    @Test
    void chat_modelAnswersDirectly_noToolCallNeeded() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyList(), anyList())).thenReturn(Response.from(AiMessage.from("Just an answer")));

        ToolCallingChatEngine engine = new ToolCallingChatEngine(model, List.of(echoTool));
        ToolCallingChatEngine.ToolChatResult result = engine.chat("Hi");

        assertThat(result.reply()).isEqualTo("Just an answer");
        assertThat(result.toolWasUsed()).isFalse();
        verify(model, times(1)).generate(anyList(), anyList());
    }

    @Test
    void chat_modelRequestsToolCall_toolExecutedAndResultFedBack() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("1")
                .name("echo")
                .arguments("{\"message\":\"hello\"}")
                .build();

        when(model.generate(anyList(), anyList()))
                .thenReturn(Response.from(AiMessage.from(List.of(request))))
                .thenReturn(Response.from(AiMessage.from("Final answer using tool result")));

        ToolCallingChatEngine engine = new ToolCallingChatEngine(model, List.of(echoTool));
        ToolCallingChatEngine.ToolChatResult result = engine.chat("Echo 'hello'");

        assertThat(result.toolWasUsed()).isTrue();
        assertThat(result.reply()).isEqualTo("Final answer using tool result");
        verify(model, times(2)).generate(anyList(), anyList());
    }

    @Test
    void chat_toolResultIsFedBackAsToolExecutionResultMessage_withCorrectText() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("1").name("echo").arguments("{\"message\":\"hello\"}").build();

        when(model.generate(anyList(), anyList()))
                .thenReturn(Response.from(AiMessage.from(List.of(request))))
                .thenReturn(Response.from(AiMessage.from("done")));

        new ToolCallingChatEngine(model, List.of(echoTool)).chat("Echo 'hello'");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(model, times(2)).generate(captor.capture(), anyList());

        List<ChatMessage> secondCallMessages = captor.getAllValues().get(1);
        ToolExecutionResultMessage resultMessage = secondCallMessages.stream()
                .filter(ToolExecutionResultMessage.class::isInstance)
                .map(ToolExecutionResultMessage.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(resultMessage.text()).isEqualTo("echo: hello");
    }

    @Test
    void chat_unknownToolRequested_feedsBackErrorInsteadOfThrowing() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("1").name("does_not_exist").arguments("{}").build();

        when(model.generate(anyList(), anyList()))
                .thenReturn(Response.from(AiMessage.from(List.of(request))))
                .thenReturn(Response.from(AiMessage.from("handled gracefully")));

        ToolCallingChatEngine.ToolChatResult result = new ToolCallingChatEngine(model, List.of(echoTool)).chat("call ghost tool");

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
            public String execute(Map<String, String> arguments) {
                throw new RuntimeException("kaboom");
            }
        };

        ChatLanguageModel model = mock(ChatLanguageModel.class);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("1").name("boom").arguments("{}").build();

        when(model.generate(anyList(), anyList()))
                .thenReturn(Response.from(AiMessage.from(List.of(request))))
                .thenReturn(Response.from(AiMessage.from("recovered")));

        ToolCallingChatEngine.ToolChatResult result = new ToolCallingChatEngine(model, List.of(failingTool)).chat("trigger boom");

        assertThat(result.reply()).isEqualTo("recovered");
    }

    @Test
    void constructor_buildsToolSpecificationFromAgentToolMetadata() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyList(), anyList())).thenReturn(Response.from(AiMessage.from("ok")));

        new ToolCallingChatEngine(model, List.of(echoTool)).chat("hi");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ToolSpecification>> specsCaptor = ArgumentCaptor.forClass(List.class);
        verify(model).generate(anyList(), specsCaptor.capture());

        ToolSpecification spec = specsCaptor.getValue().get(0);
        assertThat(spec.name()).isEqualTo("echo");
        assertThat(spec.description()).isEqualTo("Echoes back the given message");
        assertThat(spec.parameters().properties()).containsKey("message");
    }

    @Test
    void chat_firstCall_sendsOnlyTheUserMessage() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyList(), anyList())).thenReturn(Response.from(AiMessage.from("ok")));

        new ToolCallingChatEngine(model, List.of(echoTool)).chat("hi there");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(model).generate(captor.capture(), anyList());

        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0)).isInstanceOf(UserMessage.class);
    }
}
