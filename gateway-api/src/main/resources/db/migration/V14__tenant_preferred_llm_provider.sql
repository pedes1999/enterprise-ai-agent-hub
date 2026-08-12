-- V14__tenant_preferred_llm_provider.sql
--
-- A tenant that has stored credentials for more than one vendor (e.g.
-- ANTHROPIC and LOCAL) previously had no way to pick which one agent
-- executions actually use -- app.llm.provider was a single server-wide
-- switch, so extra credentials just sat there unused. Nullable: null means
-- "no per-tenant override, fall back to the server-wide app.llm.provider
-- default" -- see TenantLlmProviderResolver.
ALTER TABLE tenants ADD COLUMN preferred_llm_provider VARCHAR(50);
