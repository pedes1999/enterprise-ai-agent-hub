-- Companion to V17's repository_branch: lets a single trigger request (the
-- frontend trigger form, in particular) override the token budget for just
-- this run, instead of only ever getting the tenant/server default. Nullable
-- and always optional -- null means "use the tenant's default, or the
-- server's if the tenant has none set either" -- see AgentPromptRunner's
-- effective-budget resolution.
ALTER TABLE agent_executions ADD COLUMN max_tokens_override INTEGER;
