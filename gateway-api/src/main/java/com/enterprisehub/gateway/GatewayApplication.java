package com.enterprisehub.gateway;

import com.enterprisehub.gateway.config.CredentialsProperties;
import com.enterprisehub.gateway.config.LlmProperties;
import com.enterprisehub.gateway.config.SandboxProperties;
import com.enterprisehub.gateway.config.SecurityProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the Enterprise AI Agent Hub gateway.
 *
 * This module is intentionally thin: auth, tenant/credential management,
 * and the /agents/execute entrypoint. All LLM logic lives in agent-core,
 * all filesystem/terminal/git tool execution lives in agent-runtime.
 *
 * @EnableScheduling drives AgentJobWorker's poll loop (Weeks 9-10 job
 * orchestration -- DB-backed, see V5__agent_execution_queue.sql).
 * @EnableAsync predates that decision and nothing uses it yet (app.execution's
 * thread-pool properties are still just a placeholder) -- kept for now
 * since removing it isn't part of this change, but it is not what
 * AgentJobWorker is built on.
 */
@SpringBootApplication(scanBasePackages = "com.enterprisehub")
@EnableAsync
@EnableScheduling
@EnableConfigurationProperties({SecurityProperties.class, CredentialsProperties.class, LlmProperties.class, SandboxProperties.class})
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
