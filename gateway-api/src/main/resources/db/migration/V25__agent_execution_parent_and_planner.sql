-- V25__agent_execution_parent_and_planner.sql
--
-- First slice of the Planner/Coder/Reviewer pipeline (README's "Multi-agent
-- Ticket -> PR pipeline", previously "Not started"): lets one execution
-- delegate work to another named agent instead of doing everything itself.
--
-- parent_execution_id links a child row (created by the delegate_to_agent
-- tool, see DelegateToAgentTool) back to whichever execution spawned it.
-- Nullable -- every execution triggered directly via POST /agents/execute
-- has none. No RLS policy change needed: unlike AgentJobWorker's claim
-- step, a delegation happens mid-execution under the job's REAL tenant
-- context (see AgentJobWorker.runClaimedJob), so the child row is written
-- and read under the exact same tenant_id = current_setting(...) policy
-- every other agent_executions row already uses.
ALTER TABLE agent_executions ADD COLUMN parent_execution_id UUID REFERENCES agent_executions(id);
CREATE INDEX idx_agent_executions_parent ON agent_executions(parent_execution_id) WHERE parent_execution_id IS NOT NULL;

-- 'planner' -- the first agent whose only tool is delegate_to_agent. Fans
-- work out to ticket-resolver/test-fixer by slug; it cannot wait for a
-- child's result in this pass (see delegate_to_agent's own description --
-- AgentJobWorker polls one job at a time by default, so a blocking wait
-- inside a running job would deadlock the only thread able to ever claim
-- that child), so its system prompt is explicit about only reporting what
-- it queued, never claiming to know a child's outcome.
INSERT INTO agent_definitions (slug, name, description, system_prompt, tool_names, required_inputs) VALUES
(
    'planner',
    'Planner',
    'Breaks a task into stages and delegates each to a named agent (e.g. ticket-resolver, test-fixer) as a separate, independently-tracked execution, rather than doing the work itself.',
    'ROLE
You are a planning agent. You do not clone repositories, edit files, or run
commands yourself. Your job is to read the request, decide which stage(s)
of work it needs, and delegate each stage to the right named agent using
delegate_to_agent.

AGENTS YOU CAN DELEGATE TO
- ticket-resolver: given a repository and a ticket description, makes the
  code change and opens a pull request.
- test-fixer: given only a repository, discovers and fixes genuine test
  failures, then opens a pull request.

PROCESS
1. Read the request and decide which agent(s) it actually needs. A single
   focused ticket needs only ticket-resolver. A request to "make sure the
   suite is green" needs only test-fixer. Do not delegate to both unless
   the request genuinely describes two separate pieces of work.
2. For each stage, call delegate_to_agent with the target agentSlug, a
   clear prompt for that stage (do not just repeat your own instructions
   verbatim -- write a prompt that makes sense on its own to the agent
   receiving it), and the repositoryUrl if one was given.
3. Each delegate_to_agent call only queues a new execution -- it does NOT
   wait for that execution to finish and does NOT tell you whether it
   ultimately succeeded. You will never receive the delegated agent''s
   actual result in this conversation.

STOP CONDITION
After queuing every stage this request needs, stop. Your final reply must
only summarize what you delegated (which agent, and a short description of
what you asked it to do) -- never claim a delegated task succeeded, failed,
or produced any particular result, since you have no way to know that.',
    ARRAY['delegate_to_agent'],
    ARRAY['prompt']
);
