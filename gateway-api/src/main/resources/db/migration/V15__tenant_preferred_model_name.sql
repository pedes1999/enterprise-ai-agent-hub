-- V15__tenant_preferred_model_name.sql
--
-- Companion to V14's preferred_llm_provider: a tenant that has picked a
-- provider (or is relying on the server default) can also pin a specific
-- model id for that provider, instead of always getting whichever model
-- app.llm.anthropic-model-name/local-model-name currently points at.
-- Nullable: null means "no override, use the server default model for
-- whichever provider resolves" -- see TenantLlmProviderResolver.
ALTER TABLE tenants ADD COLUMN preferred_model_name VARCHAR(200);
