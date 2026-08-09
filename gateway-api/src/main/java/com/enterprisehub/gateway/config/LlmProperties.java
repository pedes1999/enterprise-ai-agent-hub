package com.enterprisehub.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * anthropicModelName is a plain string, deliberately NOT langchain4j's
 * bundled AnthropicChatModelName enum -- that enum ships frozen inside the
 * langchain4j-anthropic jar and, as of this dependency's version, only
 * knows models up to mid-2024. The Anthropic API itself accepts any valid
 * model id string, so pinning to the enum would silently cap which models
 * this platform could ever use until the library itself is upgraded.
 */
@ConfigurationProperties(prefix = "app.llm")
public record LlmProperties(String anthropicModelName) {
}
