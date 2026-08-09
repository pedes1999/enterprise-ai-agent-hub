-- V2__force_row_level_security.sql
--
-- Postgres exempts a table's OWNER from that table's own RLS policies by
-- default -- ENABLE ROW LEVEL SECURITY alone does not apply to the owner,
-- only to other roles. Since Flyway (and therefore the app) always connects
-- as the same role (hub_user in dev), and that role owns every table it
-- creates via migration, RLS has been silently NOT enforced for the app's
-- own connection since V1: every isolation guarantee observed in testing
-- was coming entirely from application-level "WHERE tenant_id = ?"
-- filtering, not the database.
--
-- FORCE ROW LEVEL SECURITY makes the policies apply to the table owner too
-- (superusers and roles with BYPASSRLS still bypass regardless -- that's
-- the intended escape hatch for admin/migration tooling, never for the
-- app's own runtime connection).
ALTER TABLE app_users            FORCE ROW LEVEL SECURITY;
ALTER TABLE vendor_credentials   FORCE ROW LEVEL SECURITY;
ALTER TABLE platform_api_keys    FORCE ROW LEVEL SECURITY;
ALTER TABLE agent_executions     FORCE ROW LEVEL SECURITY;
