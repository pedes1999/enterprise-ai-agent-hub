package com.enterprisehub.gateway.config;

import com.enterprisehub.core.llm.LlmEngineFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * agent-core is deliberately framework-light (see LlmEngineFactory's own
 * javadoc) so it stays a plain, reusable library -- this is the one place
 * gateway-api turns it into a Spring-managed bean.
 */
@Configuration
public class LlmConfig {

    @Bean
    public LlmEngineFactory llmEngineFactory() {
        return new LlmEngineFactory();
    }
}
