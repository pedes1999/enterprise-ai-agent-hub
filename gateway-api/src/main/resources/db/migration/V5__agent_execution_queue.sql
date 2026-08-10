-- V5__agent_execution_queue.sql
--
-- Turns the previously-unused agent_executions table into a real,
-- DB-backed job queue for async agent runs (Weeks 9-10). No new
-- infrastructure (message broker) needed at this scale -- workers claim
-- rows with SELECT ... FOR UPDATE SKIP LOCKED, which is durable (survives
-- a restart, unlike in-process @Async) and safe under multiple concurrent
-- workers/app instances out of the box.
--
-- repository_url was NOT NULL from Week 1, written for a future
-- repo-driven agent (SECURITY_PATCH, etc). The prompt-plus-tools flow
-- built so far (/agents/ping-with-tools) has no repository concept yet,
-- so it's relaxed to nullable rather than forcing a fake value. prompt/
-- reply/tool_was_used are the columns that flow actually needs.
ALTER TABLE agent_executions ALTER COLUMN repository_url DROP NOT NULL;
ALTER TABLE agent_executions ADD COLUMN prompt TEXT NOT NULL DEFAULT '';
ALTER TABLE agent_executions ALTER COLUMN prompt DROP DEFAULT;
ALTER TABLE agent_executions ADD COLUMN reply TEXT;
ALTER TABLE agent_executions ADD COLUMN tool_was_used BOOLEAN;

-- The worker polls for QUEUED jobs across ALL tenants -- it's a system
-- component, not acting on behalf of any one tenant, so the normal
-- tenant_id = current_setting(...) RLS policy would make it see nothing
-- (current_tenant_id is unset/empty on the worker's own thread, which
-- matches no tenant_id). Rather than granting a second Postgres role
-- BYPASSRLS (a much bigger, harder-to-audit escape hatch that would also
-- apply to every other RLS-protected table), the policy itself gets a
-- narrow OR clause recognizing one reserved, non-UUID sentinel value that
-- ONLY TenantAwareDataSource ever sets, and only ever from
-- AgentJobWorker's own code (see TenantContext.SYSTEM_WORKER_TENANT_ID) --
-- never from anything derived from a JWT claim or other user-controlled
-- input. This is the same "one deliberate, documented exception" pattern
-- already used for platform_api_keys' open SELECT policy (see
-- V1__init_schema.sql).
--
-- Once the worker has claimed a job, it switches TenantContext to that
-- job's REAL tenant id before doing anything else (running the prompt,
-- saving tool_executions audit rows) -- the sentinel is used for nothing
-- except the claim step itself.
DROP POLICY tenant_isolation_agent_executions ON agent_executions;
CREATE POLICY tenant_isolation_agent_executions ON agent_executions
    USING (
        tenant_id::text = current_setting('app.current_tenant_id', true)
        OR current_setting('app.current_tenant_id', true) = '__agent_job_worker__'
    );
