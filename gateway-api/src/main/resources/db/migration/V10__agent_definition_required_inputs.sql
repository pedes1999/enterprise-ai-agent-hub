-- V10__agent_definition_required_inputs.sql
--
-- Generalizes the old hardcoded "prompt is required" check in
-- AgentExecutionController into a per-AgentDefinition mechanism -- a
-- future agent needing repositoryUrl, a specific inputParameters key, or
-- some combination, declares that here instead of the controller growing
-- more special-cased ad hoc field checks.
--
-- Fixed vocabulary (enforced in application code, not a DB constraint,
-- same as tool_names referencing ToolCatalog entries):
--   "prompt"                        -- TriggerAgentExecutionRequest.prompt must be non-blank
--   "repositoryUrl"                 -- TriggerAgentExecutionRequest.repositoryUrl must be non-blank
--   "inputParameters:{key}"         -- inputParameters must contain a non-blank value for {key}
--
-- Same array-column pattern as tool_names (see V6__agent_definitions.sql).
ALTER TABLE agent_definitions ADD COLUMN required_inputs TEXT[] NOT NULL DEFAULT '{}';

UPDATE agent_definitions SET required_inputs = ARRAY['prompt'] WHERE slug = 'general-assistant';
UPDATE agent_definitions SET required_inputs = ARRAY['repositoryUrl'] WHERE slug = 'coding-agent';
