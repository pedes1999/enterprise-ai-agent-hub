package com.enterprisehub.core.llm;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

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
 * All four VendorProvider values are implemented: ANTHROPIC and OPENAI call
 * their real APIs directly; GEMINI uses langchain4j-google-ai-gemini
 * (already a dependency); LOCAL reuses langchain4j-open-ai (same client as
 * OPENAI) pointed at a caller-supplied baseUrl instead of OpenAI's real API,
 * since Ollama/LM Studio/vLLM all speak the same OpenAI-compatible
 * chat-completions wire format -- no new SDK needed to let a tenant run
 * entirely against their own machine.
 */
public class LlmEngineFactory {

    private static final String LOCAL_DEFAULT_BASE_URL = "http://localhost:11434/v1";
    private static final String LOCAL_PLACEHOLDER_API_KEY = "not-needed";

    public ChatModel create(LlmProvider provider, String apiKey, String modelName) {
        return create(provider, apiKey, modelName, null);
    }

    /** baseUrl is only meaningful for LOCAL (defaults to Ollama's standard address if not supplied) -- ignored for every other provider. */
    public ChatModel create(LlmProvider provider, String apiKey, String modelName, String baseUrl) {
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
                    // Marks the system message (an AgentDefinition's persona/
                    // instructions -- see ToolCallingChatEngine's javadoc) as an
                    // Anthropic prompt-cache breakpoint. It's identical on every
                    // round of a single execution AND across every execution of
                    // the same agent, so once cached, every subsequent call (this
                    // round or a future one) is billed the much cheaper cached-read
                    // rate for it instead of the full input-token rate. No effect
                    // on OPENAI/GEMINI/LOCAL -- this flag is Anthropic-specific and
                    // simply doesn't exist on those builders.
                    .cacheSystemMessages(true)
                    // Every agent's tool specifications (name/description/JSON
                    // schema for each of git_clone, read_file, run_shell_command,
                    // etc.) are resent on every single round too, and are just as
                    // static per-execution as the system message -- same cheap
                    // cached-read win, same "no effect on other providers" story.
                    .cacheTools(true)
                    .build();
            case OPENAI -> OpenAiChatModel.builder()
                    .apiKey(apiKey)
                    .modelName(modelName)
                    .maxTokens(4096)
                    .build();
            case GEMINI -> GoogleAiGeminiChatModel.builder()
                    .apiKey(apiKey)
                    .modelName(modelName)
                    .maxOutputTokens(4096)
                    .build();
            case LOCAL -> OpenAiChatModel.builder()
                    .baseUrl(baseUrl != null && !baseUrl.isBlank() ? baseUrl : LOCAL_DEFAULT_BASE_URL)
                    // Most local servers (Ollama included) don't check this at
                    // all, but the langchain4j client requires a non-blank
                    // value to build -- never treated as a real secret.
                    .apiKey(apiKey != null && !apiKey.isBlank() ? apiKey : LOCAL_PLACEHOLDER_API_KEY)
                    .modelName(modelName)
                    .maxTokens(4096)
                    .build();
        };
    }
}
