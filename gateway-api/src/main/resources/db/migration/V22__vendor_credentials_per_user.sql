-- Vendor credentials move from tenant-wide to per-user: real teams have
-- individual developer API keys, not one shared org key, and the old
-- UNIQUE(tenant_id, provider) forced everyone onto whichever single key the
-- admin happened to configure. No fallback going forward -- a user with no
-- personal key for a provider is blocked from triggering executions with
-- it (see AgentPromptRunner's resolveApiKey()) -- so every existing row
-- needs a real owner, not a null one.
--
-- Backfill: attribute each tenant's existing credential(s) to that tenant's
-- earliest-created ADMIN. Every tenant always has at least one (see
-- UserService's "last admin cannot be demoted" rule) -- picking the admin
-- (rather than, say, an arbitrary DEVELOPER) is the closest approximation
-- of "whoever actually set this up" available, since the column genuinely
-- didn't exist before now.
ALTER TABLE vendor_credentials ADD COLUMN user_id UUID REFERENCES app_users(id) ON DELETE CASCADE;

-- Both app_users and vendor_credentials have FORCE ROW LEVEL SECURITY (see
-- V2__force_row_level_security.sql), which applies RLS to the table owner
-- too -- including Flyway's own migration connection. With no
-- app.current_tenant_id set here, tenant_id::text = current_setting(...)
-- is never true, so without lifting FORCE first this backfill would see
-- zero rows in either table and leave user_id NULL everywhere.
ALTER TABLE app_users NO FORCE ROW LEVEL SECURITY;
ALTER TABLE vendor_credentials NO FORCE ROW LEVEL SECURITY;

UPDATE vendor_credentials vc
SET user_id = (
    SELECT au.id
    FROM app_users au
    WHERE au.tenant_id = vc.tenant_id
      AND au.role = 'ADMIN'
    ORDER BY au.created_at
    LIMIT 1
)
WHERE vc.user_id IS NULL;

ALTER TABLE app_users FORCE ROW LEVEL SECURITY;
ALTER TABLE vendor_credentials FORCE ROW LEVEL SECURITY;

ALTER TABLE vendor_credentials ALTER COLUMN user_id SET NOT NULL;

ALTER TABLE vendor_credentials DROP CONSTRAINT vendor_credentials_tenant_id_provider_key;
ALTER TABLE vendor_credentials ADD CONSTRAINT vendor_credentials_tenant_id_user_id_provider_key
    UNIQUE (tenant_id, user_id, provider);

-- RLS policy is unchanged -- tenant_isolation_vendor_credentials already
-- enforces tenant_id at the DB level; per-user scoping within a tenant is
-- an application-query concern (see VendorCredentialRepository), same
-- pattern as every other extra-column scope in this schema.
CREATE INDEX idx_vendor_credentials_tenant_user ON vendor_credentials(tenant_id, user_id);
