-- V26__search_code_tool.sql
--
-- Adds search_code (grep-based, see SearchCodeTool) to ticket-resolver and
-- test-fixer's tool_names, same ARRAY-append pattern V7 used for
-- open_pull_request. Deliberately a small, targeted REPLACE() into each
-- prompt's existing PROCESS text -- not a rewrite -- pointing the model at
-- search_code where it was already told to explore/locate code, rather
-- than restating carefully-tuned prompts (round-budget behavior in
-- particular, see V18's own history) for a token-savings change that
-- doesn't need it: search_code's own tool description already tells the
-- model to prefer it over read_file, so this is a small nudge, not the
-- only signal.
UPDATE agent_definitions
SET tool_names = tool_names || ARRAY['search_code'],
    system_prompt = REPLACE(
        system_prompt,
        'Explore the repository using run_shell_command (grep, find, ls)',
        'Explore the repository using search_code (cheaper than reading whole files) or run_shell_command (grep, find, ls)'
    )
WHERE slug = 'ticket-resolver';

UPDATE agent_definitions
SET tool_names = tool_names || ARRAY['search_code'],
    system_prompt = REPLACE(
        system_prompt,
        'a. Read the failing test and the source code it exercises.',
        'a. Read the failing test and the source code it exercises -- use search_code to locate it quickly rather than guessing paths.'
    )
WHERE slug = 'test-fixer';
