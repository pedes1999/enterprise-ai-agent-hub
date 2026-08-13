package com.enterprisehub.gateway.config;

import com.enterprisehub.core.llm.LlmProvider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LlmPropertiesTest {

    private final LlmProperties properties = new LlmProperties(
            "ANTHROPIC", "claude-3-5-sonnet-20240620", "gpt-4o-mini", "gemini-1.5-flash", "llama3.1", "http://localhost:11434/v1", 500_000, 100);

    @Test
    void resolvedProvider_parsesConfiguredValue() {
        assertThat(properties.resolvedProvider()).isEqualTo(LlmProvider.ANTHROPIC);
    }

    @Test
    void resolvedProvider_unparseableValue_fallsBackToAnthropic() {
        LlmProperties broken = new LlmProperties("not-a-provider", "claude-3-5-sonnet-20240620", null, null, null, null, null, null);
        assertThat(broken.resolvedProvider()).isEqualTo(LlmProvider.ANTHROPIC);
    }

    @Test
    void modelName_noArg_usesResolvedProvider() {
        assertThat(properties.modelName()).isEqualTo("claude-3-5-sonnet-20240620");
    }

    @Test
    void modelName_explicitAnthropicProvider_returnsAnthropicModelName() {
        assertThat(properties.modelName(LlmProvider.ANTHROPIC)).isEqualTo("claude-3-5-sonnet-20240620");
    }

    @Test
    void modelName_explicitOpenAiProvider_returnsOpenAiModelName_evenWhenServerDefaultIsAnthropic() {
        assertThat(properties.modelName(LlmProvider.OPENAI)).isEqualTo("gpt-4o-mini");
    }

    @Test
    void modelName_explicitGeminiProvider_returnsGeminiModelName_evenWhenServerDefaultIsAnthropic() {
        assertThat(properties.modelName(LlmProvider.GEMINI)).isEqualTo("gemini-1.5-flash");
    }

    @Test
    void modelName_explicitLocalProvider_returnsLocalModelName_evenWhenServerDefaultIsAnthropic() {
        assertThat(properties.modelName(LlmProvider.LOCAL)).isEqualTo("llama3.1");
    }

    @Test
    void baseUrl_noArg_nullForServerDefaultAnthropic() {
        assertThat(properties.baseUrl()).isNull();
    }

    @Test
    void baseUrl_explicitAnthropicProvider_isNull() {
        assertThat(properties.baseUrl(LlmProvider.ANTHROPIC)).isNull();
    }

    @Test
    void baseUrl_explicitOpenAiProvider_isNull() {
        assertThat(properties.baseUrl(LlmProvider.OPENAI)).isNull();
    }

    @Test
    void baseUrl_explicitGeminiProvider_isNull() {
        assertThat(properties.baseUrl(LlmProvider.GEMINI)).isNull();
    }

    @Test
    void baseUrl_explicitLocalProvider_returnsLocalBaseUrl_evenWhenServerDefaultIsAnthropic() {
        assertThat(properties.baseUrl(LlmProvider.LOCAL)).isEqualTo("http://localhost:11434/v1");
    }
}
