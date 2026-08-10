package com.enterprisehub.runtime.sandbox;

import java.time.Duration;
import java.util.Map;

/**
 * What a sandbox is provisioned with. tenantId and executionId are for the
 * backing implementation to tag/log/scope the sandbox with -- they are NOT
 * a substitute for the implementation enforcing its own limits; a caller
 * claiming tenantId="X" must never be trusted blindly (see
 * ToolExecutionContext, which is where this actually comes from upstream).
 *
 * credentials are injected as environment variables inside the sandbox,
 * never written to a mounted file -- the whole point of scoping them per
 * execution is defeated if they end up sitting on disk somewhere a
 * subsequent (or concurrent) execution could read them.
 */
public record SandboxSpec(
        String tenantId,
        String executionId,
        Map<String, String> credentials,
        Duration maxLifetime,
        long maxOutputBytes) {

    public SandboxSpec {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (executionId == null || executionId.isBlank()) {
            throw new IllegalArgumentException("executionId is required");
        }
        credentials = credentials == null ? Map.of() : Map.copyOf(credentials);
        if (maxLifetime == null || maxLifetime.isNegative() || maxLifetime.isZero()) {
            throw new IllegalArgumentException("maxLifetime must be a positive duration");
        }
        if (maxOutputBytes <= 0) {
            throw new IllegalArgumentException("maxOutputBytes must be positive");
        }
    }
}
