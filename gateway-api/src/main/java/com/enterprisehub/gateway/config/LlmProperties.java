package com.enterprisehub.gateway.config;

import com.enterprisehub.core.llm.LlmProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * anthropicModelName is a plain string, deliberately NOT langchain4j's
 * bundled AnthropicChatModelName enum -- that enum ships frozen inside the
 * langchain4j-anthropic jar and, as of this dependency's version, only
 * knows models up to mid-2024. The Anthropic API itself accepts any valid
 * model id string, so pinning to the enum would silently cap which models
 * this platform could ever use until the library itself is upgraded.
 *
 * provider is a server-wide switch (env var, not a database row) between
 * ANTHROPIC (the real default) and LOCAL (any OpenAI-compatible server on
 * this machine -- Ollama, LM Studio, vLLM) -- e.g. for local testing without
 * spending real Anthropic credits. Deliberately NOT a per-tenant or
 * per-execution choice: it's a whole-instance dev/ops setting, same shape
 * as app.job-worker.enabled, not a product feature with its own UI. To use
 * it, set LLM_PROVIDER=LOCAL and PUT a LOCAL vendor credential (any
 * non-blank token -- most local servers don't check it).
 */
@ConfigurationProperties(prefix = "app.llm")
public record LlmProperties(String provider, String anthropicModelName, String localModelName, String localBaseUrl) {

    public LlmProvider resolvedProvider() {
        return LlmProvider.parse(provider).orElse(LlmProvider.ANTHROPIC);
    }

    public String modelName() {
        return modelName(resolvedProvider());
    }

    /** Same as modelName() but for an explicitly resolved provider -- see TenantLlmProviderResolver, which may resolve to something other than resolvedProvider() when a tenant has its own preference set. */
    public String modelName(LlmProvider provider) {
        return provider == LlmProvider.LOCAL ? localModelName : anthropicModelName;
    }

    /** Null for every provider except LOCAL -- LlmEngineFactory only reads this for LOCAL, and defaults it itself if blank. */
    public String baseUrl() {
        return baseUrl(resolvedProvider());
    }

    /** Same as baseUrl() but for an explicitly resolved provider -- see modelName(LlmProvider)'s javadoc. */
    public String baseUrl(LlmProvider provider) {
        return provider == LlmProvider.LOCAL ? localBaseUrl : null;
    }
}
