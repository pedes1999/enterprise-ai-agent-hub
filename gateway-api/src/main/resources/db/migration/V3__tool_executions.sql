-- V3__tool_executions.sql
--
-- Audit log: one row per AgentTool.execute() call, success or failure
-- (see agent-runtime's AbstractSandboxedTool / ToolExecutionListener).
--
-- execution_id is NOT a foreign key to agent_executions(id) yet.
-- AgentPingService's spike endpoints (POST /agents/ping-with-tools)
-- generate a synthetic execution id with no corresponding agent_executions
-- row, since those endpoints are explicitly not the real agent execution
-- model. Once real agent orchestration (Weeks 9-10) creates a genuine
-- agent_executions row per invocation, this can become a real FK.
CREATE TABLE tool_executions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    execution_id    VARCHAR(100) NOT NULL,
    tool_name       VARCHAR(255) NOT NULL,
    duration_ms     BIGINT NOT NULL,
    outcome         VARCHAR(20) NOT NULL,  -- SUCCESS, FAILURE
    error_message   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- FORCE (not just ENABLE) from day one this time -- see
-- V2__force_row_level_security.sql for why ENABLE alone silently doesn't
-- apply to the table owner, which is the role the app itself connects as.
ALTER TABLE tool_executions ENABLE ROW LEVEL SECURITY;
ALTER TABLE tool_executions FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_tool_executions ON tool_executions
    USING (tenant_id::text = current_setting('app.current_tenant_id', true));

CREATE INDEX idx_tool_executions_tenant ON tool_executions(tenant_id);
CREATE INDEX idx_tool_executions_execution_id ON tool_executions(execution_id);
