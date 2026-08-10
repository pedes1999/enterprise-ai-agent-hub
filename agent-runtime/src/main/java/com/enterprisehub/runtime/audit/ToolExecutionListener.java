package com.enterprisehub.runtime.audit;

/**
 * Called after every sandboxed tool execution (success or failure).
 * agent-runtime depends on this interface only; gateway-api provides the
 * real implementation, persisting to a new tool_executions table (FK to
 * agent_executions.id -- one agent run can invoke many tools, so this
 * doesn't fit as columns on the existing AgentExecution row). Keeping
 * agent-runtime itself free of any DB/Spring dependency, same discipline
 * agent-core already applies to LangChain4j.
 */
public interface ToolExecutionListener {

    void onToolExecuted(ToolExecutionAuditRecord record);
}
