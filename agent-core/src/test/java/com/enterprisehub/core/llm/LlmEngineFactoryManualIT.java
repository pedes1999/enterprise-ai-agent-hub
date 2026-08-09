package com.enterprisehub.core.llm;

import dev.langchain4j.model.chat.ChatLanguageModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Named *IT (not *Test) so Maven Surefire's default include pattern skips
 * it automatically in every normal `mvn test` run -- this makes a REAL
 * network call to Anthropic and costs real API credits, so it must never
 * run unattended in CI. @EnabledIfEnvironmentVariable is a second,
 * independent guard: it self-skips (not fails) if ANTHROPIC_API_KEY isn't
 * set, so cloning this repo elsewhere without the key is harmless.
 *
 * Run explicitly with the key set:
 *   ANTHROPIC_API_KEY=sk-ant-... mvn test -pl agent-core -Dtest=LlmEngineFactoryManualIT
 */
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
class LlmEngineFactoryManualIT {

    @Test
    void realAnthropicCall_returnsAResponse() {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        ChatLanguageModel model = new LlmEngineFactory()
                .create(LlmProvider.ANTHROPIC, apiKey, "claude-sonnet-4-5-20250929");

        String reply = model.generate("Reply with exactly one word: PONG");

        System.out.println("Anthropic replied: " + reply);
        assertThat(reply).isNotBlank();
    }
}
