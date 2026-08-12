package com.enterprisehub.core.llm;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
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

    @Test
    void create_local_returnsOpenAiChatModel_reusingTheOpenAiCompatibleWireFormat() {
        // Ollama/LM Studio/vLLM all speak the same OpenAI-compatible chat-
        // completions format, so LOCAL deliberately reuses OpenAiChatModel
        // rather than a bespoke client -- just pointed at a local baseUrl.
        ChatLanguageModel model = factory.create(LlmProvider.LOCAL, "fake-key", "llama3.1", "http://localhost:11434/v1");

        assertThat(model).isInstanceOf(OpenAiChatModel.class);
    }

    @Test
    void create_local_noBaseUrlSupplied_stillBuildsWithoutThrowing() {
        // Defaults to Ollama's standard local address rather than requiring
        // every caller to know/pass it.
        ChatLanguageModel model = factory.create(LlmProvider.LOCAL, "fake-key", "llama3.1");

        assertThat(model).isInstanceOf(OpenAiChatModel.class);
    }

    @Test
    void create_local_blankApiKey_stillBuildsWithoutThrowing() {
        // Most local servers don't check the key at all -- a blank/null key
        // must not prevent the client from being built (langchain4j's own
        // OpenAiChatModel.Builder requires SOME non-blank string internally).
        ChatLanguageModel model = factory.create(LlmProvider.LOCAL, "", "llama3.1", "http://localhost:11434/v1");

        assertThat(model).isInstanceOf(OpenAiChatModel.class);
    }
}
