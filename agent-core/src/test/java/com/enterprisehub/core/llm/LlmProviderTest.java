package com.enterprisehub.core.llm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LlmProviderTest {

    @Test
    void parse_validCaseInsensitive_returnsProvider() {
        assertThat(LlmProvider.parse("anthropic")).contains(LlmProvider.ANTHROPIC);
        assertThat(LlmProvider.parse("OPENAI")).contains(LlmProvider.OPENAI);
        assertThat(LlmProvider.parse("Gemini")).contains(LlmProvider.GEMINI);
        assertThat(LlmProvider.parse("local")).contains(LlmProvider.LOCAL);
    }

    @Test
    void parse_unknown_returnsEmpty() {
        assertThat(LlmProvider.parse("COHERE")).isEmpty();
    }

    @Test
    void parse_null_returnsEmpty() {
        assertThat(LlmProvider.parse(null)).isEmpty();
    }
}
