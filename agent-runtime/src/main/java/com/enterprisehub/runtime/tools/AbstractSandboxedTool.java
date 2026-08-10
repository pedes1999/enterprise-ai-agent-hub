package com.enterprisehub.runtime.tools;

import com.enterprisehub.core.tool.AgentTool;
import com.enterprisehub.core.tool.ToolExecutionContext;
import com.enterprisehub.runtime.audit.ToolExecutionAuditRecord;
import com.enterprisehub.runtime.audit.ToolExecutionListener;
import com.enterprisehub.runtime.audit.ToolExecutionOutcome;
import com.enterprisehub.runtime.sandbox.SandboxClient;
import com.enterprisehub.runtime.sandbox.SandboxHandle;
import com.enterprisehub.runtime.sandbox.SandboxRunner;
import com.enterprisehub.runtime.sandbox.SandboxSpec;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.Function;

/**
 * Every real (sandboxed) tool extends this instead of implementing
 * AgentTool directly, so audit logging happens exactly once, in exactly
 * one place, for every sandboxed tool -- rather than each tool
 * implementation remembering to call ToolExecutionListener itself
 * correctly (and consistently) on every path including exceptions.
 *
 * Subclasses are still plain AgentTools as far as everything else
 * (ToolCallingChatEngine, a future AgentDefinition-driven registry keyed
 * by tool name) is concerned -- this is an implementation detail of how
 * agent-runtime's tools are built, not a different kind of tool.
 */
public abstract class AbstractSandboxedTool implements AgentTool {

    protected final SandboxClient sandboxClient;
    private final SandboxRunner sandboxRunner;
    private final ToolExecutionListener listener;

    protected AbstractSandboxedTool(SandboxClient sandboxClient, ToolExecutionListener listener) {
        this.sandboxClient = sandboxClient;
        this.sandboxRunner = new SandboxRunner(sandboxClient);
        this.listener = listener;
    }

    protected <T> T withSandbox(SandboxSpec spec, Function<SandboxHandle, T> work) {
        return sandboxRunner.withSandbox(spec, work);
    }

    @Override
    public final String execute(ToolExecutionContext context, Map<String, String> arguments) {
        Instant start = Instant.now();
        try {
            String result = doExecute(context, arguments);
            listener.onToolExecuted(new ToolExecutionAuditRecord(
                    context.tenantId(), context.executionId(), name(),
                    Duration.between(start, Instant.now()), ToolExecutionOutcome.SUCCESS, null));
            return result;
        } catch (RuntimeException e) {
            listener.onToolExecuted(new ToolExecutionAuditRecord(
                    context.tenantId(), context.executionId(), name(),
                    Duration.between(start, Instant.now()), ToolExecutionOutcome.FAILURE, e.getMessage()));
            // Rethrown, not swallowed: ToolCallingChatEngine already catches
            // AgentTool.execute() failures and turns them into a tool-result
            // string the LLM can see -- no need to duplicate that translation
            // here, audit logging is this class's only job.
            throw e;
        }
    }

    protected abstract String doExecute(ToolExecutionContext context, Map<String, String> arguments);
}
