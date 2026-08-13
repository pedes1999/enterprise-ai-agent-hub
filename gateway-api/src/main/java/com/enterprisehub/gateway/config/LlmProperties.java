package com.enterprisehub.gateway.config;

import com.enterprisehub.core.llm.LlmProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * anthropicModelName/openaiModelName/geminiModelName are plain strings,
 * deliberately NOT langchain4j's bundled per-vendor model-name enums --
 * those ship frozen inside their jars and lag behind each vendor's real,
 * ever-changing model catalog (see GET /vendor-credentials/{provider}/models,
 * which lists the vendor's actual current models instead).
 *
 * provider is a server-wide switch (env var, not a database row) between
 * ANTHROPIC (the real default), OPENAI, GEMINI, and LOCAL (any
 * OpenAI-compatible server on this machine -- Ollama, LM Studio, vLLM) --
 * e.g. for local testing without spending real Anthropic credits.
 * Deliberately NOT a per-tenant or per-execution choice on its own: it's a
 * whole-instance dev/ops default, same shape as app.job-worker.enabled --
 * see TenantLlmProviderResolver/TenantSettingsService for the per-tenant
 * override that sits on top of this default.
 *
 * maxTokensPerExecution is the server-wide fallback ToolCallingChatEngine
 * budget-caps a run at once its round-by-round TokenUsage exceeds it --
 * same "whole-instance default, tenant can override, execution can
 * override that" layering as the model/provider fields above, see
 * TenantLlmProviderResolver.resolveMaxTokens().
 */
@ConfigurationProperties(prefix = "app.llm")
public record LlmProperties(String provider, String anthropicModelName, String openaiModelName, String geminiModelName,
                             String localModelName, String localBaseUrl, Integer maxTokensPerExecution) {

    public LlmProvider resolvedProvider() {
        return LlmProvider.parse(provider).orElse(LlmProvider.ANTHROPIC);
    }

    public String modelName() {
        return modelName(resolvedProvider());
    }

    /** Same as modelName() but for an explicitly resolved provider -- see TenantLlmProviderResolver, which may resolve to something other than resolvedProvider() when a tenant has its own preference set. */
    public String modelName(LlmProvider provider) {
        return switch (provider) {
            case ANTHROPIC -> anthropicModelName;
            case OPENAI -> openaiModelName;
            case GEMINI -> geminiModelName;
            case LOCAL -> localModelName;
        };
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
