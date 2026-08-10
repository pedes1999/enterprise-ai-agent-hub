-- V6__agent_definitions.sql
--
-- The catalog a "library of hundreds of agents and tools" is actually
-- built out of. An agent definition is a named, reusable combination of a
-- system prompt (its persona/instructions) and a curated subset of tool
-- names (see ToolCatalog in gateway-api) -- triggering an agent means
-- picking one of these by slug, not hand-assembling a tool list and
-- prompt every time the way /agents/ping-with-tools originally did.
--
-- Platform-wide, not tenant-scoped: every tenant picks from the same
-- shared catalog (a deliberate product decision -- per-tenant custom
-- agent authoring is a possible future addition, not built now). No RLS
-- here for the same reason `tenants` itself has none -- this table isn't
-- tenant data.
--
-- No admin CRUD API yet -- new agents are added via migration, the same
-- way new tools are added via code today. A management API is a natural
-- later addition once the shape of a definition has proven itself with a
-- few real ones.
CREATE TABLE agent_definitions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    slug            VARCHAR(100) NOT NULL UNIQUE,
    name            VARCHAR(200) NOT NULL,
    description     TEXT NOT NULL,
    system_prompt   TEXT NOT NULL,
    tool_names      TEXT[] NOT NULL,
    llm_provider    VARCHAR(50) NOT NULL DEFAULT 'ANTHROPIC',
    is_active       BOOLEAN NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_agent_definitions_slug ON agent_definitions(slug) WHERE is_active;

INSERT INTO agent_definitions (slug, name, description, system_prompt, tool_names) VALUES
(
    'general-assistant',
    'General Assistant',
    'A trivial, general-purpose agent with no repository access -- the default when no specific agent is requested. Mirrors the original /agents/ping-with-tools spike behavior.',
    'You are a helpful assistant. Answer the user''s request directly and concisely. Use the current-date-time tool only if the user actually needs to know the current date or time.',
    ARRAY['get_current_date_time']
),
(
    'coding-agent',
    'Coding Agent',
    'Can clone a repository, inspect and edit files, and run shell commands (build, test) against it within one persistent sandboxed workspace -- the building block Ticket-to-PR work is built from.',
    'You are a coding agent. You can clone a git repository, read and write files within it, and run shell commands (e.g. to build or test) -- all within one shared, persistent workspace for this task. Always verify a change actually worked (e.g. by reading the file back, or running a relevant command) before reporting success. Be precise about exit codes and command output rather than assuming success.',
    ARRAY['get_current_date_time', 'git_clone', 'read_file', 'write_file', 'run_shell_command']
);
