#!/bin/sh
# Creates the Ivorovi and Keycloak databases and the least-privilege roles.
# Runs once, on first start of an empty Postgres data volume (docker-entrypoint-initdb.d).
# The same script is used by Testcontainers-based integration tests.
#
#   ivorovi_owner : owns the schema, runs Flyway migrations. Never used by the running app for DML.
#   ivorovi_app   : used by the application. NOBYPASSRLS, DML-only. Row Level Security applies.
set -eu
: "${IVOROVI_OWNER_PASSWORD:?required}" "${IVOROVI_APP_PASSWORD:?required}" "${KEYCLOAK_DB_PASSWORD:=}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
  -v owner_pw="$IVOROVI_OWNER_PASSWORD" -v app_pw="$IVOROVI_APP_PASSWORD" <<'SQL'
CREATE ROLE ivorovi_owner LOGIN PASSWORD :'owner_pw' NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE ROLE ivorovi_app   LOGIN PASSWORD :'app_pw'   NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
CREATE DATABASE ivorovi OWNER ivorovi_owner;
\connect ivorovi
REVOKE ALL ON SCHEMA public FROM PUBLIC;
CREATE SCHEMA ivorovi AUTHORIZATION ivorovi_owner;
GRANT USAGE ON SCHEMA ivorovi TO ivorovi_app;
ALTER DEFAULT PRIVILEGES FOR ROLE ivorovi_owner IN SCHEMA ivorovi
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ivorovi_app;
ALTER DEFAULT PRIVILEGES FOR ROLE ivorovi_owner IN SCHEMA ivorovi
  GRANT USAGE, SELECT ON SEQUENCES TO ivorovi_app;
ALTER ROLE ivorovi_owner IN DATABASE ivorovi SET search_path = ivorovi;
ALTER ROLE ivorovi_app   IN DATABASE ivorovi SET search_path = ivorovi;
SQL

if [ -n "$KEYCLOAK_DB_PASSWORD" ]; then
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres -v kc_pw="$KEYCLOAK_DB_PASSWORD" <<'SQL'
CREATE ROLE keycloak LOGIN PASSWORD :'kc_pw';
CREATE DATABASE keycloak OWNER keycloak;
SQL
fi
