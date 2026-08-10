package com.enterprisehub.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.sandbox")
public record SandboxProperties(String sidecarUrl) {
}
