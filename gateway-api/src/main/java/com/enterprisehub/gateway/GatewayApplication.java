package com.enterprisehub.gateway;

import com.enterprisehub.gateway.config.CorsProperties;
import com.enterprisehub.gateway.config.CredentialsProperties;
import com.enterprisehub.gateway.config.ExecutionLimitProperties;
import com.enterprisehub.gateway.config.JobWorkerProperties;
import com.enterprisehub.gateway.config.LlmProperties;
import com.enterprisehub.gateway.config.MailProperties;
import com.enterprisehub.gateway.config.SandboxProperties;
import com.enterprisehub.gateway.config.SecurityProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
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
 * @EnableAsync predates that decision and nothing uses it yet -- kept for
 * now since removing it isn't part of this change, but it is not what
 * AgentJobWorker is built on. app.execution's core-pool-size/max-pool-size/
 * queue-capacity keys are that same unused leftover; max-concurrent-per-tenant
 * under the same prefix (see ExecutionLimitProperties) is the one key in
 * that namespace actually bound to anything today.
 *
 * @EntityScan / @EnableJpaRepositories are explicit, covering both
 * com.enterprisehub.gateway and com.enterprisehub.rag, because
 * @SpringBootApplication's scanBasePackages ONLY widens plain @Component
 * scanning -- Spring Data JPA's repository-interface scanning and
 * Hibernate's entity scanning both default to AutoConfigurationPackages
 * (this class's own package alone) when neither annotation is present,
 * regardless of scanBasePackages. Without this, rag-service's entities and
 * repositories compile fine but are invisible at runtime -- confirmed live:
 * the app failed to start with "No qualifying bean of type
 * AgentKnowledgeSourceBindingRepository" until these were added.
 */
@SpringBootApplication(scanBasePackages = "com.enterprisehub")
@EntityScan(basePackages = {"com.enterprisehub.gateway.entity", "com.enterprisehub.rag.entity"})
@EnableJpaRepositories(basePackages = {"com.enterprisehub.gateway.repository", "com.enterprisehub.rag.repository"})
@EnableAsync
@EnableScheduling
@EnableConfigurationProperties({SecurityProperties.class, CredentialsProperties.class, LlmProperties.class,
        SandboxProperties.class, ExecutionLimitProperties.class, CorsProperties.class, MailProperties.class,
        JobWorkerProperties.class})
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
