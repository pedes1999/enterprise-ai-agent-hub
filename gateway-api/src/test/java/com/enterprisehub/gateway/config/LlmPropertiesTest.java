package com.enterprisehub.gateway.config;

import com.enterprisehub.core.llm.LlmProvider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LlmPropertiesTest {

    private final LlmProperties properties = new LlmProperties("ANTHROPIC", "claude-3-5-sonnet-20240620", "llama3.1", "http://localhost:11434/v1");

    @Test
    void resolvedProvider_parsesConfiguredValue() {
        assertThat(properties.resolvedProvider()).isEqualTo(LlmProvider.ANTHROPIC);
    }

    @Test
    void resolvedProvider_unparseableValue_fallsBackToAnthropic() {
        LlmProperties broken = new LlmProperties("not-a-provider", "claude-3-5-sonnet-20240620", null, null);
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
    void baseUrl_explicitLocalProvider_returnsLocalBaseUrl_evenWhenServerDefaultIsAnthropic() {
        assertThat(properties.baseUrl(LlmProvider.LOCAL)).isEqualTo("http://localhost:11434/v1");
    }
}
