package com.enterprisehub.core.tool;

/**
 * Carries WHO a tool execution is running for, explicitly, as a method
 * parameter -- not a ThreadLocal. This matters specifically because tool
 * execution is on a path that will become sandboxed (an HTTP call to a
 * sidecar, agent-runtime) and eventually queued (Weeks 9-10): both are
 * places a ThreadLocal's implicit propagation silently breaks the moment
 * the actual work happens on a different thread than the one that set the
 * context. Explicit parameters also make "which tenant can this tool touch"
 * checkable by reading the code, which matters once a future
 * AgentDefinition-driven orchestrator is dynamically assembling tools per
 * request rather than tools being wired one-to-one into specific agents.
 */
public record ToolExecutionContext(String tenantId, String executionId) {
}
