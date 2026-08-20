package com.enterprisehub.runtime.tools;

import com.enterprisehub.core.tool.AgentTool;
import com.enterprisehub.runtime.sandbox.SandboxClient;
import com.enterprisehub.runtime.sandbox.SandboxHandle;
import com.enterprisehub.runtime.sandbox.SandboxRunner;
import com.enterprisehub.runtime.sandbox.SandboxSpec;

import java.util.function.Function;

/**
 * Shared sandbox plumbing for tools that need a microVM -- it owns the
 * SandboxClient and the withSandbox() lifecycle so each tool only writes
 * the part that differs.
 *
 * This class used to also do audit logging, which worked but only covered
 * the tools that happened to extend it; the three tools implementing
 * AgentTool directly were silently unaudited. Auditing now lives in
 * AuditingTool, applied by ToolCatalog to every tool it builds, so it is
 * no longer tied to whether a tool needs a sandbox. See AuditingTool's
 * javadoc.
 */
public abstract class AbstractSandboxedTool implements AgentTool {

    protected final SandboxClient sandboxClient;
    private final SandboxRunner sandboxRunner;

    protected AbstractSandboxedTool(SandboxClient sandboxClient) {
        this.sandboxClient = sandboxClient;
        this.sandboxRunner = new SandboxRunner(sandboxClient);
    }

    protected <T> T withSandbox(SandboxSpec spec, Function<SandboxHandle, T> work) {
        return sandboxRunner.withSandbox(spec, work);
    }
}
