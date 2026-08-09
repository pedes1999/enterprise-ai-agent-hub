-- V1__init_schema.sql
-- Core schema for the Enterprise AI Agent Hub gateway.
--
-- Isolation strategy: shared schema + tenant_id column + Postgres Row-Level
-- Security. Every tenant-scoped table gets a policy that only allows access
-- to rows matching the session variable 'app.current_tenant_id', which the
-- application sets at the start of every transaction (see TenantSessionAspect).
--
-- This means isolation is enforced by the database itself, not by app code
-- remembering to add "WHERE tenant_id = ?" everywhere.

-- ============================================================
-- TENANTS  (not RLS-scoped to itself — a tenant needs to read its own row
-- to resolve who it is in the first place)
-- ============================================================
CREATE TABLE tenants (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(255) NOT NULL,
    slug            VARCHAR(100) NOT NULL UNIQUE,
    plan            VARCHAR(50)  NOT NULL DEFAULT 'FREE',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ============================================================
-- APP USERS  (developers who log into the platform / dashboard)
-- ============================================================
CREATE TABLE app_users (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    email           VARCHAR(320) NOT NULL,
    password_hash   VARCHAR(255) NOT NULL,
    role            VARCHAR(50)  NOT NULL DEFAULT 'DEVELOPER', -- ADMIN, DEVELOPER, READONLY
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, email)
);

ALTER TABLE app_users ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_app_users ON app_users
    USING (tenant_id::text = current_setting('app.current_tenant_id', true));

-- ============================================================
-- VENDOR CREDENTIALS  (encrypted Anthropic/OpenAI/Gemini API tokens)
-- Highest-sensitivity table in the schema.
-- ============================================================
CREATE TABLE vendor_credentials (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    provider            VARCHAR(50) NOT NULL,      -- ANTHROPIC, OPENAI, GEMINI
    encrypted_token      TEXT NOT NULL,             -- ciphertext only, never plaintext
    encryption_key_id    VARCHAR(255) NOT NULL,     -- KMS/Vault key reference used for envelope encryption
    is_active            BOOLEAN NOT NULL DEFAULT true,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, provider)
);

ALTER TABLE vendor_credentials ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_vendor_credentials ON vendor_credentials
    USING (tenant_id::text = current_setting('app.current_tenant_id', true));

-- ============================================================
-- PLATFORM API KEYS  (issued to tenants for CI/CD, webhooks, CLI auth)
-- ============================================================
CREATE TABLE platform_api_keys (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    key_hash        VARCHAR(255) NOT NULL UNIQUE,  -- store a hash, never the raw key
    label           VARCHAR(255),
    last_used_at    TIMESTAMPTZ,
    revoked_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE platform_api_keys ENABLE ROW LEVEL SECURITY;

-- SELECT is intentionally NOT tenant-scoped: key lookup by hash is the
-- pre-authentication bootstrap step (CI/CD pipeline presents a raw key,
-- app hashes it, looks up the row to find out WHICH tenant it belongs to).
-- At that point app.current_tenant_id cannot be set yet -- the whole point
-- of the query is to discover the tenant. This is safe because key_hash is
-- an unguessable, unique credential: matching it discloses nothing beyond
-- "a key with this hash exists," identical in spirit to how a password
-- reset token or session ID lookup is scoped by the token itself, not by
-- tenant. INSERT/UPDATE/DELETE remain tenant-scoped below.
CREATE POLICY platform_api_keys_lookup_by_hash ON platform_api_keys
    FOR SELECT USING (true);

CREATE POLICY tenant_isolation_platform_api_keys_write ON platform_api_keys
    FOR INSERT WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true));

CREATE POLICY tenant_isolation_platform_api_keys_update ON platform_api_keys
    FOR UPDATE USING (tenant_id::text = current_setting('app.current_tenant_id', true));

CREATE POLICY tenant_isolation_platform_api_keys_delete ON platform_api_keys
    FOR DELETE USING (tenant_id::text = current_setting('app.current_tenant_id', true));

-- ============================================================
-- AGENT EXECUTIONS  (one row per triggered agent run)
-- ============================================================
CREATE TABLE agent_executions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    agent_type      VARCHAR(100) NOT NULL,   -- e.g. SECURITY_PATCH, CROSS_STACK_ALIGNMENT
    trigger_source  VARCHAR(50)  NOT NULL,   -- CI_CD, WEBHOOK, CLI, DASHBOARD
    repository_url  VARCHAR(500) NOT NULL,
    status          VARCHAR(50)  NOT NULL DEFAULT 'QUEUED', -- QUEUED, RUNNING, SUCCEEDED, FAILED
    llm_provider    VARCHAR(50),
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    error_message   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE agent_executions ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_agent_executions ON agent_executions
    USING (tenant_id::text = current_setting('app.current_tenant_id', true));

-- ============================================================
-- Indexes
-- ============================================================
CREATE INDEX idx_app_users_tenant       ON app_users(tenant_id);
CREATE INDEX idx_vendor_credentials_tenant ON vendor_credentials(tenant_id);
CREATE INDEX idx_platform_api_keys_tenant  ON platform_api_keys(tenant_id);
CREATE INDEX idx_agent_executions_tenant   ON agent_executions(tenant_id);
CREATE INDEX idx_agent_executions_status   ON agent_executions(status);

-- ============================================================
-- IMPORTANT: connection pool ownership
-- ============================================================
-- The database user the app connects as (e.g. 'agent_hub') must NOT have
-- the BYPASSRLS attribute and must NOT be a superuser. RLS policies are
-- silently ignored for superusers/BYPASSRLS roles, which would defeat the
-- entire point of this migration. Verify with:
--   SELECT rolname, rolsuper, rolbypassrls FROM pg_roles WHERE rolname = 'agent_hub';
