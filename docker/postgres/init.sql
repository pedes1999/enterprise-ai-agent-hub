-- Runs once, automatically, the first time the postgres container starts
-- with an empty data volume (see docker-entrypoint-initdb.d in the
-- official postgres image). Executed as the image's default superuser
-- (POSTGRES_USER=postgres in docker-compose.yml), which is exactly why
-- this exists as a separate step: the app's own role must NOT be that
-- superuser, or Postgres silently ignores every RLS policy in the schema
-- (see V1__init_schema.sql's note on this). Mirrors README.md's local
-- setup instructions exactly, just run non-interactively.
CREATE ROLE hub_user LOGIN PASSWORD 'password';
CREATE DATABASE agent_hub OWNER hub_user;
