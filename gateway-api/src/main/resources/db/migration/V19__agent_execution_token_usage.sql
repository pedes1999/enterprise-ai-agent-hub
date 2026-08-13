-- Nothing in the codebase previously captured LLM token usage anywhere --
-- Response.tokenUsage() (langchain4j) was read on every call and discarded.
-- Nullable: a row completed before this migration, or one whose provider
-- response carried no usage data, has no token counts, not zero ones --
-- zero would falsely claim "this cost nothing" instead of "unknown".
ALTER TABLE agent_executions
    ADD COLUMN input_tokens INTEGER,
    ADD COLUMN output_tokens INTEGER,
    ADD COLUMN total_tokens INTEGER;
