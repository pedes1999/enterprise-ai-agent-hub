package com.enterprisehub.runtime.audit;

import com.enterprisehub.core.tool.AgentTool;
import com.enterprisehub.core.tool.ToolExecutionContext;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Wraps any AgentTool so that executing it always produces exactly one
 * audit record. This is the ONLY place tool auditing happens.
 *
 * It used to live in AbstractSandboxedTool, which audited correctly but
 * only for tools that happened to extend it. Every non-sandboxed tool
 * (get_current_date_time, delegate_to_agent, retrieval) implemented
 * AgentTool directly, so its factory received a ToolExecutionListener and
 * silently dropped it -- three of nine tools produced no audit trail at
 * all, and `get_current_date_time` had never written a single row to
 * tool_executions. delegate_to_agent is the one that mattered: it queues
 * other agent executions, and that fan-out was invisible to the audit
 * table it was supposed to be recorded in.
 *
 * The fix is deliberately positional rather than a convention tools are
 * asked to follow: ToolCatalog.instantiate() wraps everything it builds,
 * so a tool cannot reach ToolCallingChatEngine unaudited, and a new tool
 * author cannot forget. Tools no longer know that auditing exists --
 * which is why none of them take a ToolExecutionListener any more.
 */
public final class AuditingTool implements AgentTool {

    private final AgentTool delegate;
    private final ToolExecutionListener listener;

    public AuditingTool(AgentTool delegate, ToolExecutionListener listener) {
        this.delegate = delegate;
        this.listener = listener;
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public String description() {
        return delegate.description();
    }

    @Override
    public Map<String, String> parameterDescriptions() {
        return delegate.parameterDescriptions();
    }

    @Override
    public Set<String> optionalParameterNames() {
        return delegate.optionalParameterNames();
    }

    /**
     * Delegated rather than defaulted: open_pull_request ends the
     * tool-calling loop through this, so a decorator that answered `false`
     * on the wrapper's behalf would quietly turn every terminal tool into a
     * non-terminal one and let runs continue past their natural end.
     */
    @Override
    public boolean isTerminalSuccess(String result) {
        return delegate.isTerminalSuccess(result);
    }

    @Override
    public String execute(ToolExecutionContext context, Map<String, String> arguments) {
        Instant start = Instant.now();
        try {
            String result = delegate.execute(context, arguments);
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
            // string the LLM can see -- auditing is this class's only job.
            throw e;
        }
    }
}
