#!/bin/sh
# Runs once, automatically, the first time the postgres container starts
# with an empty data volume (see docker-entrypoint-initdb.d in the
# official postgres image). Executed as the image's default superuser
# (POSTGRES_USER/POSTGRES_PASSWORD in docker-compose.yml), which is exactly
# why this exists as a separate step: the app's own role must NOT be that
# superuser, or Postgres silently ignores every RLS policy in the schema
# (see V1__init_schema.sql's note on this). Mirrors README.md's local
# setup instructions exactly, just run non-interactively.
#
# A .sh script (rather than the plain .sql this replaced) is what lets this
# read DB_USERNAME/DB_PASSWORD from the container's environment --
# docker-entrypoint-initdb.d executes *.sh files with env intact, but pipes
# *.sql files straight into psql with no substitution. Same var names
# gateway-api uses to connect, so postgres and gateway-api always agree.
# Set both in .env if you don't want the dev-only defaults below.
set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" <<-EOSQL
    CREATE ROLE ${DB_USERNAME:-hub_user} LOGIN PASSWORD '${DB_PASSWORD:-password}';
    CREATE DATABASE agent_hub OWNER ${DB_USERNAME:-hub_user};
EOSQL

# pgvector must be installed by a superuser -- confirmed against the real
# pgvector/pgvector:pg16 image: hub_user, deliberately never a superuser
# (see the note above), gets "permission denied to create extension" even
# though it owns agent_hub, so V28__enable_pgvector.sql's own
# `CREATE EXTENSION IF NOT EXISTS vector` can only ever be a no-op confirming
# it's already there, never the thing that actually installs it. Same
# posture as role/database creation above: this is the one place a
# superuser touches anything, and it only needs to run once per database.
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" -d agent_hub -c "CREATE EXTENSION IF NOT EXISTS vector;"
