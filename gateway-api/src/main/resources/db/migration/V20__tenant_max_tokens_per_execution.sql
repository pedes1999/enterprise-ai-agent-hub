-- Companion to V15's preferred_model_name: a tenant can pin its own token
-- budget per agent execution instead of always getting the server-wide
-- app.llm.max-tokens-per-execution default. Nullable: null means "no
-- override, use the server default" -- see TenantLlmProviderResolver.
ALTER TABLE tenants ADD COLUMN max_tokens_per_execution INTEGER;
