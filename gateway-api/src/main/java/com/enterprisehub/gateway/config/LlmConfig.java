package com.enterprisehub.gateway.config;

import com.enterprisehub.core.SharedExecutionContextFactory;
import com.enterprisehub.core.llm.LlmEngineFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * agent-core is deliberately framework-light (see LlmEngineFactory's own
 * javadoc) so it stays a plain, reusable library -- this is the one place
 * gateway-api turns it into Spring-managed beans.
 */
@Configuration
public class LlmConfig {

    @Bean
    public LlmEngineFactory llmEngineFactory() {
        return new LlmEngineFactory();
    }

    @Bean
    public SharedExecutionContextFactory sharedExecutionContextFactory(LlmEngineFactory llmEngineFactory) {
        return new SharedExecutionContextFactory(llmEngineFactory);
    }
}
