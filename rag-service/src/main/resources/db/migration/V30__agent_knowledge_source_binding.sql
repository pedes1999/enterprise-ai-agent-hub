-- V30__agent_knowledge_source_binding.sql
--
-- How a tenant admin attaches a knowledge_source to an AgentDefinition
-- "via config, without writing new code": a row in this table, not a
-- column on agent_definitions itself. agent_definitions is the PLATFORM-
-- WIDE shared catalog (no tenant_id, no RLS -- see V6__agent_definitions.sql,
-- every tenant triggers from the same rows). A single
-- default_knowledge_source_id column directly on agent_definitions would
-- bind one specific tenant's knowledge source onto a row every OTHER
-- tenant also uses to run ticket-resolver -- exactly the kind of
-- cross-tenant leak RLS exists to prevent elsewhere in this schema. This
-- table is the tenant-scoped join instead: each tenant picks their own
-- knowledge source per agent, independently of every other tenant's choice.
--
-- One binding per (tenant, agent) -- a knowledge source can itself hold
-- many documents (see V29), so there's no need for an agent to reference
-- more than one; if that changes later, this table's uniqueness constraint
-- is what would need to relax, not its shape.
CREATE TABLE agent_knowledge_source_binding (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    agent_definition_id     UUID NOT NULL REFERENCES agent_definitions(id) ON DELETE CASCADE,
    knowledge_source_id     UUID NOT NULL REFERENCES knowledge_source(id) ON DELETE CASCADE,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, agent_definition_id)
);

ALTER TABLE agent_knowledge_source_binding ENABLE ROW LEVEL SECURITY;
ALTER TABLE agent_knowledge_source_binding FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_agent_knowledge_source_binding ON agent_knowledge_source_binding
    USING (tenant_id::text = current_setting('app.current_tenant_id', true));

CREATE INDEX idx_agent_knowledge_source_binding_tenant ON agent_knowledge_source_binding(tenant_id);
