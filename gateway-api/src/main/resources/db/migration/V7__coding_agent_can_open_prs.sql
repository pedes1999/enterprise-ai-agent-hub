-- V7__coding_agent_can_open_prs.sql
--
-- The first real Ticket-to-PR-shaped capability: coding-agent can now
-- commit, push, and open a pull request, gated on open_pull_request's own
-- mandatory testCommand argument (the tool itself refuses to proceed past
-- a failing test run, regardless of what the model claims -- see
-- OpenPullRequestTool's javadoc). system_prompt is updated to actually
-- tell the agent to use it that way, not just to have the tool available.
UPDATE agent_definitions
SET tool_names = tool_names || ARRAY['open_pull_request'],
    system_prompt = 'You are a coding agent. You can clone a git repository, read and write files within it, and run shell commands (e.g. to build or test) -- all within one shared, persistent workspace for this task. Always verify a change actually worked (e.g. by reading the file back, or running a relevant command) before reporting success. Be precise about exit codes and command output rather than assuming success. Once a change is complete, use open_pull_request to commit it, push it, and open a pull request -- always pass a real testCommand; that tool will refuse to open a pull request at all if it fails, so do not skip it or pass something meaningless just to satisfy the argument.'
WHERE slug = 'coding-agent';
