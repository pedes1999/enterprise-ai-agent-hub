-- V12__app_users_name.sql
--
-- Backs the Angular team-management page ("email, name, role" per user
-- row). Existing rows (created via AuthController.register or an earlier
-- POST /users with no name field) backfill to '' -- there's no real name
-- to derive one from, and no self-service "update my name" endpoint exists
-- yet to let a user fix it themselves. Same add-with-default-then-drop
-- pattern as V5's prompt column: new rows must supply a real value from
-- here on.
ALTER TABLE app_users ADD COLUMN name VARCHAR(255) NOT NULL DEFAULT '';
ALTER TABLE app_users ALTER COLUMN name DROP DEFAULT;
