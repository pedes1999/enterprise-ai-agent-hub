package com.enterprisehub.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * allowedOrigin is the Angular frontend's real origin (e.g.
 * https://app.yourplatform.com) -- deliberately a single configurable
 * value, never a wildcard, consistent with the tenant-isolation discipline
 * the rest of this platform is careful about. See SecurityConfig's
 * corsConfigurationSource() for where this is actually enforced.
 */
@ConfigurationProperties(prefix = "app.cors")
public record CorsProperties(String allowedOrigin) {
}
