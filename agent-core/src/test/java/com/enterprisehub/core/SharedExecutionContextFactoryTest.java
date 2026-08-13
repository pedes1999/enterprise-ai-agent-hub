package com.enterprisehub.core;

import com.enterprisehub.core.llm.LlmEngineFactory;
import com.enterprisehub.core.llm.LlmProvider;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * cacheConversationHistory (see ToolCallingChatEngine's javadoc) is decided
 * here from the tenant's own provider, not exposed as a caller-supplied
 * parameter -- these tests prove that decision actually reaches the engine
 * end to end (via the real message sent to a mocked ChatModel), the same
 * way LlmEngineFactoryTest proves cacheSystemMessages/cacheTools reach the
 * real Anthropic builder.
 */
class SharedExecutionContextFactoryTest {

    private static ChatResponse okResponse() {
        return ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
    }

    private static Object cacheControlOfFirstMessage(ChatModel model) {
        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model).chat(captor.capture());
        List<ChatMessage> messages = captor.getValue().messages();
        return ((UserMessage) messages.get(0)).attributes().get("cache_control");
    }

    @Test
    void create_anthropicProvider_enablesIncrementalConversationCaching() {
        LlmEngineFactory llmEngineFactory = mock(LlmEngineFactory.class);
        ChatModel model = mock(ChatModel.class);
        when(llmEngineFactory.create(eq(LlmProvider.ANTHROPIC), any(), any(), any())).thenReturn(model);
        when(model.chat(any(ChatRequest.class))).thenReturn(okResponse());

        SharedExecutionContext context = new SharedExecutionContextFactory(llmEngineFactory)
                .create("tenant-1", "exec-1", LlmProvider.ANTHROPIC, "key", "claude-3-5-sonnet-20240620", List.of());

        context.chat("hi");

        assertThat(cacheControlOfFirstMessage(model)).isEqualTo("ephemeral");
    }

    @Test
    void create_openAiProvider_doesNotEnableConversationCaching() {
        // Marking a message this way is a harmless no-op for OpenAI/Gemini/Local
        // (their langchain4j mappers don't look for the attribute at all), but
        // there's no reason to pay the extra allocation on a provider that will
        // never read it -- see ToolCallingChatEngine's constructor javadoc.
        LlmEngineFactory llmEngineFactory = mock(LlmEngineFactory.class);
        ChatModel model = mock(ChatModel.class);
        when(llmEngineFactory.create(eq(LlmProvider.OPENAI), any(), any(), any())).thenReturn(model);
        when(model.chat(any(ChatRequest.class))).thenReturn(okResponse());

        SharedExecutionContext context = new SharedExecutionContextFactory(llmEngineFactory)
                .create("tenant-1", "exec-1", LlmProvider.OPENAI, "key", "gpt-4o-mini", List.of());

        context.chat("hi");

        assertThat(cacheControlOfFirstMessage(model)).isNull();
    }
}
