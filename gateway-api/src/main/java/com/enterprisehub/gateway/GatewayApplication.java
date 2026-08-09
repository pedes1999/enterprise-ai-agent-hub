package com.enterprisehub.gateway;

import com.enterprisehub.gateway.config.SecurityProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Entry point for the Enterprise AI Agent Hub gateway.
 *
 * This module is intentionally thin: auth, tenant/credential management,
 * and the /agents/execute entrypoint. All LLM logic lives in agent-core,
 * all filesystem/terminal/git tool execution lives in agent-runtime.
 *
 * @EnableAsync is required from day one — agent executions must never
 * block the request thread that triggered them (CI/CD, webhook, CLI, etc).
 */
@SpringBootApplication(scanBasePackages = "com.enterprisehub")
@EnableAsync
@EnableConfigurationProperties(SecurityProperties.class)
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
