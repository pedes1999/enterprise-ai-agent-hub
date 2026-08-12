package com.enterprisehub.core.llm;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
    void create_openAi_returnsOpenAiChatModel() {
        ChatLanguageModel model = factory.create(LlmProvider.OPENAI, "fake-test-key", "gpt-4o-mini");

        assertThat(model).isInstanceOf(OpenAiChatModel.class);
    }

    @Test
    void create_gemini_returnsGoogleAiGeminiChatModel() {
        ChatLanguageModel model = factory.create(LlmProvider.GEMINI, "fake-test-key", "gemini-1.5-flash");

        assertThat(model).isInstanceOf(GoogleAiGeminiChatModel.class);
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
