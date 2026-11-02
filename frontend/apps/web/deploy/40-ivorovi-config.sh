#!/bin/sh
# Writes the SPA runtime configuration from environment variables (validated, JSON-safe).
set -eu
: "${OIDC_AUTHORITY:?OIDC_AUTHORITY is required}"
: "${OIDC_CLIENT_ID:?OIDC_CLIENT_ID is required}"
API_BASE_URL="${API_BASE_URL:-}"
for v in "$OIDC_AUTHORITY" "$OIDC_CLIENT_ID" "$API_BASE_URL"; do
  case "$v" in *\"*|*\\*|*"
"*) echo "Invalid character in web configuration value" >&2; exit 1;; esac
done
cat > /usr/share/nginx/html/config.json <<JSON
{"oidcAuthority":"${OIDC_AUTHORITY}","oidcClientId":"${OIDC_CLIENT_ID}","apiBaseUrl":"${API_BASE_URL}"}
JSON
