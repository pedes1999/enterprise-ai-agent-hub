-- V34__webhook_endpoints.sql
--
-- First trigger that isn't a human clicking something. Until now the only
-- way an agent_executions row came into existence was POST /agents/execute
-- behind an authenticated PlatformPrincipal (see AgentExecutionController),
-- which means every piece of machinery built around unattended runs -- the
-- DB-backed queue (V5), the per-tenant concurrency cap, the heartbeat/reaper
-- (V32), cancellation (V33) -- has never actually had an unattended run to
-- handle. This is that run: GitHub posts a pull_request event, the app
-- verifies it really came from GitHub, and queues an execution with nobody
-- watching.
--
-- SecurityConfig has carried `.requestMatchers("/webhooks/**").permitAll()`
-- with the comment "signature validation happens in the webhook controller
-- itself" since before any such controller existed. These two tables are
-- what finally makes that comment true.

-- ============================================================
-- WEBHOOK ENDPOINTS  (one per repository-or-org -> agent wiring)
-- ============================================================
CREATE TABLE webhook_endpoints (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    agent_slug        VARCHAR(100) NOT NULL,

    -- Which app_user a delivery on this endpoint runs as. NOT NULL, and that
    -- is a deliberate design constraint rather than defensiveness: vendor
    -- credentials are per-user with no tenant-level fallback (see V22 and
    -- VendorCredentialService's javadoc), and AgentPromptRunner.resolveApiKey()
    -- throws outright on a null userId. A webhook has no human behind it, so
    -- without an explicitly recorded run-as user EVERY webhook-triggered run
    -- would fail at credential resolution. Recording it here also keeps the
    -- audit trail honest about whose API key paid for an unattended run.
    -- ON DELETE CASCADE: removing a user disables the endpoints that ran as
    -- them, rather than leaving rows that can only ever fail.
    run_as_user_id    UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,

    -- The shared secret GitHub signs each delivery with. ENCRYPTED, not
    -- hashed -- unlike platform_api_keys.key_hash, this value has to be
    -- recovered in plaintext to recompute the HMAC over the request body, so
    -- ApiKeyHasher's one-way digest is the wrong primitive here. Same
    -- envelope-encryption shape as vendor_credentials: ciphertext plus the
    -- key id that encrypted it, so a future key rotation can tell old rows
    -- from new ones (see EncryptedCredential).
    secret_ciphertext TEXT NOT NULL,
    secret_key_id     VARCHAR(255) NOT NULL,

    -- Which GitHub event this endpoint acts on. Single-valued and defaulted
    -- rather than an array: this slice handles pull_request only, and a
    -- column that can hold one value honestly beats an array that pretends
    -- to support a fan-out the mapper can't do yet.
    event_type        VARCHAR(50) NOT NULL DEFAULT 'pull_request',

    label             VARCHAR(255),
    is_active         BOOLEAN NOT NULL DEFAULT true,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE webhook_endpoints ENABLE ROW LEVEL SECURITY;
-- ENABLE alone would leave these policies unenforced for the app's own
-- connection, since the app connects as the role that owns the table -- see
-- V2__force_row_level_security.sql for the full explanation. Every new
-- tenant-scoped table needs this line, not just the four V2 retrofitted.
ALTER TABLE webhook_endpoints FORCE ROW LEVEL SECURITY;

-- SELECT is intentionally NOT tenant-scoped, for exactly the reason
-- platform_api_keys' lookup policy isn't (see V1__init_schema.sql): finding
-- this row IS the step that discovers which tenant the request belongs to.
-- A webhook carries no JWT and no API key, so app.current_tenant_id cannot
-- possibly be set yet -- the id in the URL path is the only discriminator
-- there is. Safe on the same grounds: the id is an unguessable random UUID,
-- and matching it discloses nothing beyond "an endpoint with this id
-- exists". Note this policy exposes secret_ciphertext to an unscoped read,
-- which is only acceptable because it is ciphertext -- the decryption key
-- lives in the application (CredentialEncryptor), never in the database.
-- INSERT/UPDATE/DELETE stay tenant-scoped below.
CREATE POLICY webhook_endpoints_lookup_by_id ON webhook_endpoints
    FOR SELECT USING (true);

CREATE POLICY tenant_isolation_webhook_endpoints_insert ON webhook_endpoints
    FOR INSERT WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true));

CREATE POLICY tenant_isolation_webhook_endpoints_update ON webhook_endpoints
    FOR UPDATE USING (tenant_id::text = current_setting('app.current_tenant_id', true));

CREATE POLICY tenant_isolation_webhook_endpoints_delete ON webhook_endpoints
    FOR DELETE USING (tenant_id::text = current_setting('app.current_tenant_id', true));

-- ============================================================
-- WEBHOOK DELIVERIES  (one row per accepted delivery -- the idempotency key)
-- ============================================================
CREATE TABLE webhook_deliveries (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    endpoint_id  UUID NOT NULL REFERENCES webhook_endpoints(id) ON DELETE CASCADE,

    -- GitHub's X-GitHub-Delivery header: a UUID that stays the SAME across
    -- automatic retries and manual "Redeliver" clicks, which is precisely
    -- what makes it usable as an idempotency key.
    delivery_id  VARCHAR(255) NOT NULL,

    execution_id UUID REFERENCES agent_executions(id) ON DELETE SET NULL,
    received_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- The whole point of this table. GitHub redelivers on its own schedule
    -- and on demand from its UI; without this constraint one pull request
    -- could queue (and bill) an agent run more than once. Note that GitHub,
    -- unlike Stripe, sends NO timestamp header on a webhook -- there is no
    -- signed timestamp to enforce a replay window against, so delivery-id
    -- uniqueness is the replay defence, not merely a nicety.
    -- Scoped to endpoint_id rather than globally: two different endpoints
    -- are two independent subscriptions, and GitHub delivery ids are only
    -- promised unique per delivery, not across installations.
    UNIQUE (endpoint_id, delivery_id)
);

ALTER TABLE webhook_deliveries ENABLE ROW LEVEL SECURITY;
ALTER TABLE webhook_deliveries FORCE ROW LEVEL SECURITY;

-- Ordinary tenant scoping here, unlike webhook_endpoints above: every read
-- and write of this table happens AFTER WebhookIngestService has resolved
-- the endpoint and set TenantContext, so the normal policy applies.
CREATE POLICY tenant_isolation_webhook_deliveries ON webhook_deliveries
    USING (tenant_id::text = current_setting('app.current_tenant_id', true));

CREATE INDEX idx_webhook_endpoints_tenant  ON webhook_endpoints(tenant_id);
CREATE INDEX idx_webhook_deliveries_tenant ON webhook_deliveries(tenant_id);
