#!/usr/bin/env bash
# Generates deploy/compose/.env with random local-development secrets (never committed).
set -euo pipefail
cd "$(dirname "$0")/../deploy/compose"
rnd() { LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c "${1:-24}"; }
if [[ -f .env && "${1:-}" != "--force" ]]; then
  # Keep existing secrets (volumes depend on them); add only keys introduced since the file was created.
  added=0
  while IFS='=' read -r key value; do
    [[ "$key" =~ ^[A-Z_]+$ ]] || continue
    if ! grep -q "^${key}=" .env; then echo "${key}=$(rnd)" >> .env; added=$((added + 1)); echo "Added ${key}"; fi
  done < .env.example
  echo ".env already exists; ${added} new key(s) added (use --force to regenerate everything; this invalidates existing volumes)"
  exit 0
fi
sed -e "s/__POSTGRES_PASSWORD__/$(rnd)/" \
    -e "s/__IVOROVI_OWNER_PASSWORD__/$(rnd)/" \
    -e "s/__IVOROVI_APP_PASSWORD__/$(rnd)/" \
    -e "s/__KEYCLOAK_DB_PASSWORD__/$(rnd)/" \
    -e "s/__KEYCLOAK_ADMIN_PASSWORD__/$(rnd)/" \
    -e "s/__KEYCLOAK_PROVISIONER_SECRET__/$(rnd 32)/" \
    -e "s/__DEV_USER_PASSWORD__/Dev-$(rnd 16)/" \
    -e "s/__REDIS_PASSWORD__/$(rnd)/" \
    -e "s/__RABBITMQ_PASSWORD__/$(rnd)/" \
    -e "s/__S3_ACCESS_KEY__/$(rnd 20)/" \
    -e "s/__S3_SECRET_KEY__/$(rnd 40)/" \
    .env.example > .env
chmod 600 .env
echo "Created deploy/compose/.env. Dev login password for seeded users: DEV_USER_PASSWORD in that file."
