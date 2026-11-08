#!/bin/sh
# Writes the exam app runtime configuration from environment variables (validated, JSON-safe).
set -eu
: "${OIDC_AUTHORITY:?OIDC_AUTHORITY is required}"
: "${OIDC_CLIENT_ID:?OIDC_CLIENT_ID is required}"
for v in "$OIDC_AUTHORITY" "$OIDC_CLIENT_ID"; do
  case "$v" in *\"*|*\\*|*"
"*) echo "Invalid character in exam configuration value" >&2; exit 1;; esac
done
cat > /usr/share/nginx/html/config.json <<JSON
{"oidcAuthority":"${OIDC_AUTHORITY}","oidcClientId":"${OIDC_CLIENT_ID}"}
JSON
