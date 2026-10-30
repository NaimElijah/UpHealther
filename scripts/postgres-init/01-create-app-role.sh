#!/bin/sh
# Creates the role the backend connects as, so the API never holds the cluster's superuser (S5.C7).
#
# docker-compose.yml mounts this directory at /docker-entrypoint-initdb.d, and the postgres image runs
# it once, as POSTGRES_USER, when it initialises an empty data directory. An existing volume skips it:
# the README says how to recreate one.
#
# The role owns the database and its `public` schema, because Flyway runs as it and creates every
# object the migrations name, V1's `CREATE EXTENSION pgcrypto` included, which a non-superuser may run
# since PostgreSQL 13 because the extension is trusted. It holds no other attribute: no SUPERUSER,
# CREATEDB, CREATEROLE, REPLICATION or BYPASSRLS.
#
# The values reach SQL only through psql's own quoting, :"…" for an identifier and :'…' for a literal,
# and the heredoc is quoted so the shell expands nothing in it.
#
# The image sources this file rather than running it, since it is not executable, so it sets no shell
# options of its own: they would outlive it in the image's entrypoint.

: "${POSTGRES_APP_USER:?POSTGRES_APP_USER must name the role the backend connects as}"
: "${POSTGRES_APP_PASSWORD:?POSTGRES_APP_PASSWORD must be set: a role without one cannot sign in}"

psql -v ON_ERROR_STOP=1 \
  --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" \
  --set app_user="$POSTGRES_APP_USER" \
  --set app_password="$POSTGRES_APP_PASSWORD" \
  --set app_database="$POSTGRES_DB" <<'SQL'
CREATE ROLE :"app_user" LOGIN PASSWORD :'app_password'
  NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
ALTER DATABASE :"app_database" OWNER TO :"app_user";
ALTER SCHEMA public OWNER TO :"app_user";
SQL
