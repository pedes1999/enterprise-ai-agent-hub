-- V4__tool_credentials.sql
--
-- Credentials a sandboxed tool needs at execution time (e.g. a git PAT for
-- GitCloneTool), separate from vendor_credentials (LLM provider API keys).
-- Deliberately a distinct table rather than reusing vendor_credentials with
-- a new "provider" value: a GitHub PAT has a different rotation/scoping
-- story than an LLM API key, and conflating the two would blur that
-- distinction as more credential kinds get added.
--
-- Same encryption approach as vendor_credentials (CredentialEncryptor /
-- LocalAesGcmCredentialEncryptor -- reused as-is, no new encryption code).
CREATE TABLE tool_credentials (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    credential_kind     VARCHAR(50) NOT NULL,      -- e.g. GIT
    encrypted_value     TEXT NOT NULL,             -- ciphertext only, never plaintext
    encryption_key_id   VARCHAR(255) NOT NULL,     -- KMS/Vault key reference used for envelope encryption
    is_active           BOOLEAN NOT NULL DEFAULT true,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, credential_kind)
);

ALTER TABLE tool_credentials ENABLE ROW LEVEL SECURITY;
ALTER TABLE tool_credentials FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_tool_credentials ON tool_credentials
    USING (tenant_id::text = current_setting('app.current_tenant_id', true));

CREATE INDEX idx_tool_credentials_tenant ON tool_credentials(tenant_id);
