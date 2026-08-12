package com.enterprisehub.core.llm;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatLanguageModel;

/**
 * The provider-agnostic seam the rest of the platform is built around: a
 * caller supplies WHICH provider a tenant picked and the tenant's own
 * (already-decrypted) API key, and gets back a LangChain4j
 * ChatLanguageModel it can call without knowing which vendor it is.
 *
 * Deliberately NOT a Spring @Component -- agent-core stays framework-light
 * (see its pom: langchain4j + lombok/slf4j only, no spring-boot-starter).
 * gateway-api wires an instance of this as a bean.
 *
 * OPENAI/GEMINI intentionally throw rather than half-implementing a second
 * and third provider before the first one has proven the abstraction is
 * even shaped correctly end to end.
 */
public class LlmEngineFactory {

    public ChatLanguageModel create(LlmProvider provider, String apiKey, String modelName) {
        return switch (provider) {
            case ANTHROPIC -> AnthropicChatModel.builder()
                    .apiKey(apiKey)
                    .modelName(modelName)
                    // 1024 was enough for a quick ping but too tight for a real
                    // multi-round agentic turn: a model that narrates a sentence
                    // or two of reasoning before a tool call can get cut off
                    // before it ever emits the tool_use block, producing a
                    // text-only turn (no tool call) that ToolCallingChatEngine
                    // then reads as a final answer -- silently ending the task
                    // early rather than erroring. Live-verified failure mode
                    // against ticket-resolver: the reply came back truncated
                    // mid-sentence after read_file, never reaching write_file.
                    // This only caps a ceiling -- Anthropic bills actual output
                    // tokens generated, not this number, so a trivial ping still
                    // costs the same as before.
                    .maxTokens(4096)
                    .build();
            case OPENAI, GEMINI -> throw new UnsupportedOperationException(
                    provider + " is not wired up yet -- only ANTHROPIC is implemented so far");
        };
    }
}
