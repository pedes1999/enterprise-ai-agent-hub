-- V11__credential_health_timestamps.sql
--
-- Backs the Angular frontend's credentials page ("last used" / "last
-- validated" per credential, a "Test connection" button). Two distinct
-- timestamps, not one, because they mean different things:
--   last_used_at       -- stamped whenever this credential is actually
--                          decrypted and used for a real operation
--                          (VendorCredentialService.decryptToken(),
--                          ToolCredentialService.decryptActiveValue()) --
--                          i.e. a real agent execution used it.
--   last_validated_at   -- stamped only by an explicit, deliberate
--                          "Test connection" call (POST
--                          /vendor-credentials/test, /tool-credentials/test)
--                          that made a cheap live check against the real
--                          provider and got a positive result.
-- Both nullable: a freshly stored credential has used neither yet.
ALTER TABLE vendor_credentials ADD COLUMN last_used_at TIMESTAMPTZ;
ALTER TABLE vendor_credentials ADD COLUMN last_validated_at TIMESTAMPTZ;

ALTER TABLE tool_credentials ADD COLUMN last_used_at TIMESTAMPTZ;
ALTER TABLE tool_credentials ADD COLUMN last_validated_at TIMESTAMPTZ;
