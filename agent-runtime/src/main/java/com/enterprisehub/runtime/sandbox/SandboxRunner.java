package com.enterprisehub.runtime.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Function;

/**
 * Guarantees create -> use -> destroy, including on the failure path.
 * SandboxClient's raw create()/destroy() are still exposed directly for
 * multi-step sequences within one sandbox lifetime (write a script, run it,
 * read its output) -- this exists purely so individual AgentTool
 * implementations can't leak a billable, running sandbox by forgetting a
 * try/finally themselves.
 */
public final class SandboxRunner {

    private static final Logger log = LoggerFactory.getLogger(SandboxRunner.class);

    private final SandboxClient client;

    public SandboxRunner(SandboxClient client) {
        this.client = client;
    }

    public <T> T withSandbox(SandboxSpec spec, Function<SandboxHandle, T> work) {
        SandboxHandle handle = client.create(spec);
        try {
            return work.apply(handle);
        } finally {
            destroyQuietly(handle, spec);
        }
    }

    private void destroyQuietly(SandboxHandle handle, SandboxSpec spec) {
        try {
            client.destroy(handle);
        } catch (RuntimeException e) {
            // Never let a cleanup failure mask (or replace) whatever
            // exception `work` may have thrown -- this is best-effort;
            // the sidecar's own maxLifetime enforcement is the real
            // backstop against a leaked sandbox that fails to tear down.
            log.warn("Failed to destroy sandbox {} for tenant {} execution {} -- relying on maxLifetime enforcement",
                    handle.id(), spec.tenantId(), spec.executionId(), e);
        }
    }
}
