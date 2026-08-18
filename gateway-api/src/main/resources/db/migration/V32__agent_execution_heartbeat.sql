-- V32__agent_execution_heartbeat.sql
--
-- Fixes a real lockout: an execution claimed by AgentJobWorker is flipped
-- to RUNNING (see V5's claimNextQueued, SELECT ... FOR UPDATE SKIP LOCKED)
-- and only ever leaves that state when the SAME in-process run finishes and
-- calls complete()/fail(). If the app dies, is redeployed, or is killed
-- mid-run, that row stays RUNNING forever -- nothing anywhere reaps it.
--
-- That is not merely untidy: AgentExecutionService.ACTIVE_STATUSES counts
-- QUEUED **and RUNNING** against the per-tenant concurrency cap, so every
-- abandoned row permanently burns one of that tenant's slots. Restart
-- during a run often enough (app.execution.max-concurrent-per-tenant times)
-- and that tenant can never trigger another execution again -- a permanent
-- 429 with no way to clear it from the API or the UI.
--
-- The fix is a liveness signal rather than a timeout, specifically to keep
-- V5's "safe under multiple concurrent workers/app instances out of the
-- box" property: a plain "RUNNING for more than N minutes" rule, or reaping
-- everything RUNNING at startup, would let one instance kill a job another
-- instance is legitimately still running. Instead the instance that owns a
-- job stamps this column on a timer for as long as it holds it (see
-- ExecutionHeartbeatMonitor), and only rows whose stamp has gone stale --
-- meaning no instance anywhere still claims them -- get reaped.
ALTER TABLE agent_executions ADD COLUMN last_heartbeat_at TIMESTAMPTZ;

-- Deliberately left NULL for existing rows rather than backfilled: the
-- reaper reads COALESCE(last_heartbeat_at, started_at, created_at), so any
-- row already orphaned before this migration is picked up on the first
-- sweep using its start time, with no data rewrite here.

-- Partial index -- the reaper only ever scans RUNNING rows, which are a
-- tiny fraction of the table (everything else is terminal SUCCEEDED/FAILED
-- and stays forever), so indexing just those keeps the sweep cheap without
-- carrying an index entry for every historical execution.
CREATE INDEX idx_agent_executions_running_heartbeat
    ON agent_executions (last_heartbeat_at)
    WHERE status = 'RUNNING';
