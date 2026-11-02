#!/usr/bin/env bash
# Deployment verification against a running stack, using REAL tokens issued by Keycloak.
# Checks authentication, the protected API, tenant isolation, authorization and audit integrity.
#   API_URL      (default http://localhost:8080)   — or http://localhost:8081 to go through the web container
#   KEYCLOAK_URL (default http://localhost:8180)
# Requires the dev realm (ivorovi-dev-cli password-grant client exists only there) and deploy/compose/.env.
set -euo pipefail
cd "$(dirname "$0")/.."
set -a; . deploy/compose/.env; set +a
API_URL="${API_URL:-http://localhost:8080}"
KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8180}"
TENANT_A_INST=0192a000-0000-7000-8000-00000000a101
TENANT_B_INST=0192b000-0000-7000-8000-00000000b101
pass=0; fail=0

token() {
  curl -sf -X POST "$KEYCLOAK_URL/realms/ivorovi/protocol/openid-connect/token" \
    -d grant_type=password -d client_id=ivorovi-dev-cli -d scope=openid \
    --data-urlencode "username=$1" --data-urlencode "password=$DEV_USER_PASSWORD" |
    python3 -c 'import sys,json; print(json.load(sys.stdin)["access_token"])'
}

# check <description> <expected-status> <token-or-empty> <method> <path> [json-body]
check() {
  local desc=$1 expected=$2 tok=$3 method=$4 path=$5 body=${6:-}
  local args=(-s -o /tmp/smoke-body -w '%{http_code}' -X "$method" "$API_URL$path")
  [[ -n $tok ]] && args+=(-H "Authorization: Bearer $tok")
  [[ -n $body ]] && args+=(-H 'Content-Type: application/json' -d "$body")
  local got; got=$(curl "${args[@]}")
  if [[ $got == "$expected" ]]; then pass=$((pass+1)); echo "  PASS  $desc ($got)";
  else fail=$((fail+1)); echo "  FAIL  $desc: expected $expected, got $got: $(head -c 300 /tmp/smoke-body)"; fi
}

echo "Obtaining tokens from Keycloak at $KEYCLOAK_URL ..."
ALICE=$(token alice.admin@aurora.test)
INES=$(token ines.instadmin@aurora.test)
ARUN=$(token arun.faculty@aurora.test)
BELA=$(token bela.admin@borealis.test)
GHOST=$(token ghost.user@aurora.test)
echo "Running checks against $API_URL"

check "health endpoint is public"                       200 ""      GET /actuator/health/readiness
check "API rejects anonymous requests"                  401 ""      GET /api/v1/me
check "org admin authenticated via Keycloak"            200 "$ALICE" GET /api/v1/me
grep -q '"code":"aurora"' /tmp/smoke-body && { pass=$((pass+1)); echo "  PASS  tenant context resolved to 'aurora'"; } || { fail=$((fail+1)); echo "  FAIL  tenant context"; }
check "IdP user without Ivorovi account is rejected"   403 "$GHOST" GET /api/v1/me
check "institutions list (own tenant)"                  200 "$ALICE" GET /api/v1/institutions
check "cross-tenant institution read → 404"            404 "$ALICE" GET "/api/v1/institutions/$TENANT_B_INST"
check "cross-tenant institution read (B→A) → 404"      404 "$BELA"  GET "/api/v1/institutions/$TENANT_A_INST"
check "faculty cannot read audit trail"                 403 "$ARUN"  GET /api/v1/audit-events
check "faculty cannot change features"                  403 "$ARUN"  PUT /api/v1/features/ai.evaluation/override '{"enabled":true}'
check "institution admin: own institution OK"           200 "$INES"  PUT /api/v1/features/module.placement/override "{\"institutionId\":\"$TENANT_A_INST\",\"enabled\":false}"
check "institution admin: org-level escalation denied"  403 "$INES"  PUT /api/v1/features/module.placement/override '{"enabled":false}'
check "institution admin: reset own override"           200 "$INES"  DELETE "/api/v1/features/module.placement/override?institutionId=$TENANT_A_INST"
check "AI cannot be enabled per institution first"      409 "$ALICE" PUT /api/v1/features/ai.evaluation/override "{\"institutionId\":\"$TENANT_A_INST\",\"enabled\":true}"
check "audit trail readable by org admin"               200 "$ALICE" GET "/api/v1/audit-events?size=5"
check "audit chain verification"                        200 "$ALICE" GET /api/v1/audit-events/verification
grep -q '"valid":true' /tmp/smoke-body && { pass=$((pass+1)); echo "  PASS  audit hash chain is valid"; } || { fail=$((fail+1)); echo "  FAIL  audit chain invalid: $(cat /tmp/smoke-body)"; }

echo "Result: $pass passed, $fail failed"
[[ $fail -eq 0 ]]
