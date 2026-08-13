-- Which app_user triggered this execution -- needed now that vendor
-- credentials are per-user (see V22): AgentJobWorker runs this
-- asynchronously with no HTTP principal available, so it has to read who
-- queued the job from the row itself to know whose credential to resolve
-- (see AgentPromptRunner.resolveApiKey()). Nullable and ON DELETE SET NULL,
-- not CASCADE: a user being removed from the tenant shouldn't delete their
-- execution history, just leave it unattributed -- same posture as every
-- other "who did this" audit trail column would want.
ALTER TABLE agent_executions ADD COLUMN triggered_by UUID REFERENCES app_users(id) ON DELETE SET NULL;

CREATE INDEX idx_agent_executions_triggered_by ON agent_executions(triggered_by);
