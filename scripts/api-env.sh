#!/usr/bin/env bash
# Source this file to run the API on the host against the local compose stack:
#   . scripts/api-env.sh
# Maps deploy/compose/.env onto the IVOROVI_* variables the API reads (see docs/runbooks/configuration.md).
_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
set -a
. "$_root/deploy/compose/.env"
set +a
export IVOROVI_DB_APP_PASSWORD="$IVOROVI_APP_PASSWORD"
export IVOROVI_DB_OWNER_PASSWORD="$IVOROVI_OWNER_PASSWORD"
export IVOROVI_REDIS_PASSWORD="$REDIS_PASSWORD"
export IVOROVI_RABBITMQ_PASSWORD="$RABBITMQ_PASSWORD"
export IVOROVI_S3_ACCESS_KEY="$S3_ACCESS_KEY"
export IVOROVI_S3_SECRET_KEY="$S3_SECRET_KEY"
export IVOROVI_IDP_PROVISIONER_SECRET="$KEYCLOAK_PROVISIONER_SECRET"
export IVOROVI_DATA_KEY="$DATA_ENCRYPTION_KEY"
unset _root
