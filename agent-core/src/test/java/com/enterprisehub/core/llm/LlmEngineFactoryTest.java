package com.enterprisehub.core.llm;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmEngineFactoryTest {

    private final LlmEngineFactory factory = new LlmEngineFactory();

    @Test
    void create_anthropic_returnsAnthropicChatModel() {
        // Building the client is a pure local object construction -- no
        // network call happens until .generate() is invoked, so this is
        // safe to run without a real API key.
        ChatLanguageModel model = factory.create(LlmProvider.ANTHROPIC, "fake-test-key", "claude-3-5-sonnet-20240620");

        assertThat(model).isInstanceOf(AnthropicChatModel.class);
    }

    @Test
    void create_openAi_throwsUnsupportedOperation() {
        assertThatThrownBy(() -> factory.create(LlmProvider.OPENAI, "fake-key", "gpt-4"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("OPENAI");
    }

    @Test
    void create_gemini_throwsUnsupportedOperation() {
        assertThatThrownBy(() -> factory.create(LlmProvider.GEMINI, "fake-key", "gemini-pro"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("GEMINI");
    }
}
