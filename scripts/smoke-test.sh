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

# check <description> <expected-status> <token-or-empty> <method> <path> [json-body] [extra-header]
check() {
  local desc=$1 expected=$2 tok=$3 method=$4 path=$5 body=${6:-} header=${7:-}
  local args=(-s -o /tmp/smoke-body -w '%{http_code}' -X "$method" "$API_URL$path")
  [[ -n $tok ]] && args+=(-H "Authorization: Bearer $tok")
  [[ -n $header ]] && args+=(-H "$header")
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
ASHA=$(token asha.student@aurora.test)
echo "Running checks against $API_URL"

check "health endpoint is public"                       200 ""      GET /actuator/health/readiness
check "API rejects anonymous requests"                  401 ""      GET /api/v1/me
check "org admin authenticated via Keycloak"            200 "$ALICE" GET /api/v1/me
grep -q '"code":"aurora"' /tmp/smoke-body && { pass=$((pass+1)); echo "  PASS  tenant context resolved to 'aurora'"; } || { fail=$((fail+1)); echo "  FAIL  tenant context"; }
check "IdP user without Ivorovi account is rejected"   403 "$GHOST" GET /api/v1/me
check "organization units list (own tenant)"         200 "$ALICE" GET /api/v1/org-units
check "organization tree"                               200 "$ALICE" GET /api/v1/org-units/tree
check "unknown request fields are rejected"            400 "$ALICE" PUT /api/v1/features/ai.evaluation/override '{"institutionId":"x","enabled":true}'
check "cross-tenant unit read → 404"                  404 "$ALICE" GET "/api/v1/org-units/$TENANT_B_INST"
check "cross-tenant unit read (B→A) → 404"            404 "$BELA"  GET "/api/v1/org-units/$TENANT_A_INST"
check "faculty cannot read audit trail"                 403 "$ARUN"  GET /api/v1/audit-events
check "faculty cannot change features"                  403 "$ARUN"  PUT /api/v1/features/ai.evaluation/override '{"enabled":true}'
check "institution admin: own institution OK"           200 "$INES"  PUT /api/v1/features/module.placement/override "{\"orgUnitId\":\"$TENANT_A_INST\",\"enabled\":false}"
check "institution admin: org-level escalation denied"  403 "$INES"  PUT /api/v1/features/module.placement/override '{"enabled":false}'
check "institution admin: reset own override"           200 "$INES"  DELETE "/api/v1/features/module.placement/override?orgUnitId=$TENANT_A_INST"
check "AI cannot be enabled per institution first"      409 "$ALICE" PUT /api/v1/features/ai.evaluation/override "{\"orgUnitId\":\"$TENANT_A_INST\",\"enabled\":true}"
echo "Invitation flow: real Keycloak provisioning, email through Mailpit ..."
MAILPIT_URL="${MAILPIT_URL:-http://localhost:8025}"
NEW_EMAIL="smoke.$(date +%s).$RANDOM@aurora.test"
NEW_PASSWORD="Smoke-$(python3 -c 'import secrets; print(secrets.token_urlsafe(18))')"
json() { python3 -c "import sys,json; d=json.load(open('/tmp/smoke-body')); print($1)"; }
check "users list (org admin)"                          200 "$ALICE" GET "/api/v1/users?size=5"
check "users list denied to faculty"                    403 "$ARUN"  GET /api/v1/users
check "staff list (institution admin, scoped)"          200 "$INES"  GET /api/v1/staff
check "create and invite a user"                        201 "$ALICE" POST /api/v1/users \
  "{\"email\":\"$NEW_EMAIL\",\"givenName\":\"Smoke\",\"familyName\":\"Test\",\"orgUnitId\":\"$TENANT_A_INST\"}" "Idempotency-Key: smoke-$RANDOM-$RANDOM"
NEW_ID=$(json 'd["user"]["user"]["id"]')
INVITE_TOKEN=""
for _ in $(seq 1 20); do
  MSG_ID=$(curl -s "$MAILPIT_URL/api/v1/search?query=to:$NEW_EMAIL" | python3 -c 'import sys,json; m=json.load(sys.stdin).get("messages") or []; print(m[0]["ID"] if m else "")')
  if [[ -n $MSG_ID ]]; then
    INVITE_TOKEN=$(curl -s "$MAILPIT_URL/api/v1/message/$MSG_ID" | python3 -c 'import sys,json,re; m=re.search(r"#token=([A-Za-z0-9_-]{43})", json.load(sys.stdin)["Text"]); print(m.group(1) if m else "")')
    break
  fi
  sleep 1
done
[[ -n $INVITE_TOKEN ]] && { pass=$((pass+1)); echo "  PASS  invitation email delivered with a link"; } || { fail=$((fail+1)); echo "  FAIL  no invitation email in Mailpit"; }
check "public invitation lookup"                         200 ""      POST /api/v1/public/invitations/lookup "{\"token\":\"$INVITE_TOKEN\"}"
check "accept invitation (creates the Keycloak login)"   204 ""      POST /api/v1/public/invitations/accept "{\"token\":\"$INVITE_TOKEN\",\"password\":\"$NEW_PASSWORD\"}"
check "invitation link is single-use"                    404 ""      POST /api/v1/public/invitations/accept "{\"token\":\"$INVITE_TOKEN\",\"password\":\"$NEW_PASSWORD\"}"
NEW_TOKEN=$(curl -sf -X POST "$KEYCLOAK_URL/realms/ivorovi/protocol/openid-connect/token" -d grant_type=password -d client_id=ivorovi-dev-cli \
  -d scope=openid --data-urlencode "username=$NEW_EMAIL" --data-urlencode "password=$NEW_PASSWORD" | python3 -c 'import sys,json; print(json.load(sys.stdin)["access_token"])' || true)
check "invited user signs in with a real token"         200 "$NEW_TOKEN" GET /api/v1/me
check "new user without a role cannot list users"       403 "$NEW_TOKEN" GET /api/v1/users
check "read the new user"                               200 "$ALICE" GET "/api/v1/users/$NEW_ID"
NEW_VERSION=$(json 'd["user"]["version"]')
check "deactivate the smoke user"                       200 "$ALICE" POST "/api/v1/users/$NEW_ID/status" "{\"action\":\"DEACTIVATE\",\"version\":$NEW_VERSION,\"reason\":\"smoke test cleanup\"}"
check "deactivated user's token is rejected at once"    403 "$NEW_TOKEN" GET /api/v1/me
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$KEYCLOAK_URL/realms/ivorovi/protocol/openid-connect/token" -d grant_type=password \
  -d client_id=ivorovi-dev-cli --data-urlencode "username=$NEW_EMAIL" --data-urlencode "password=$NEW_PASSWORD")
[[ $code == 400 || $code == 401 ]] && { pass=$((pass+1)); echo "  PASS  deactivated user can no longer sign in to Keycloak ($code)"; } || { fail=$((fail+1)); echo "  FAIL  Keycloak still issues tokens ($code)"; }

echo "Custom roles (increment 1.3) ..."
check "permission catalogue"                            200 "$ALICE" GET /api/v1/permissions
check "create a custom role"                            201 "$ALICE" POST /api/v1/roles \
  "{\"name\":\"Smoke role $RANDOM$RANDOM\",\"permissions\":[\"org_unit.read\",\"user.read\"]}" "Idempotency-Key: smoke-role-$RANDOM-$RANDOM"
SMOKE_ROLE=$(json 'd["role"]["id"]')
check "faculty cannot create roles"                     403 "$ARUN"  POST /api/v1/roles '{"name":"x","permissions":["user.read"]}' "Idempotency-Key: smoke-deny-$RANDOM-$RANDOM"
check "college admin cannot create an organization-wide role" 403 "$INES" POST /api/v1/roles '{"name":"x","permissions":["user.read"]}' "Idempotency-Key: smoke-deny-$RANDOM-$RANDOM"
check "system roles cannot be edited"                   403 "$ALICE" PATCH /api/v1/roles/01920000-0000-7000-8000-000000000002 '{"version":0,"name":"x"}'
check "archive the smoke role"                          204 "$ALICE" DELETE "/api/v1/roles/$SMOKE_ROLE?version=0"

echo "Academic structure (increment 1.4) ..."
AC=$RANDOM$RANDOM
check "academic overview (org admin)"                   200 "$ALICE" GET /api/v1/academics/overview
check "academic overview readable by faculty"           200 "$ARUN"  GET /api/v1/academics/overview
check "student has no academic access"                   403 "$ASHA"  GET /api/v1/academics/programs
check "college admin creates a program in own college"  201 "$INES"  POST /api/v1/academics/programs \
  "{\"code\":\"SMK$AC\",\"name\":\"Smoke program\",\"qualificationLevel\":\"DIPLOMA\",\"ownerOrgUnitId\":\"$TENANT_A_INST\",\"termSystem\":\"SEMESTER\",\"durationYears\":1}" "Idempotency-Key: smoke-prog-$AC"
SMOKE_PROGRAM=$(json 'd["id"]')
check "college admin cannot create an organization-wide year" 403 "$INES" POST /api/v1/academics/academic-years \
  '{"code":"SMK","name":"x","startDate":"2090-07-01","endDate":"2091-06-30"}' "Idempotency-Key: smoke-year-$AC"
check "faculty cannot create programs"                  403 "$ARUN"  POST /api/v1/academics/programs '{"code":"X","name":"x"}' "Idempotency-Key: smoke-deny-$AC"
check "cross-tenant program read → 404"                 404 "$BELA"  GET "/api/v1/academics/programs/$SMOKE_PROGRAM"
check "deactivate the smoke program"                    200 "$INES"  POST "/api/v1/academics/programs/$SMOKE_PROGRAM/status" '{"status":"INACTIVE","version":0,"reason":"smoke test"}'

echo "Students (increment 1.5) ..."
SN=$RANDOM$RANDOM
check "student list (org admin)"                         200 "$ALICE" GET "/api/v1/students?size=5"
check "student dashboard"                                200 "$ALICE" GET /api/v1/students/overview
check "students hidden from the student role"            403 "$ASHA"  GET /api/v1/students
check "faculty cannot create students"                   403 "$ARUN"  POST /api/v1/students "{\"institutionId\":\"$TENANT_A_INST\",\"studentNo\":\"SMK-$SN\",\"firstName\":\"Smoke\"}" "Idempotency-Key: smoke-stu-deny-$SN"
check "college admin creates a student"                  201 "$INES"  POST /api/v1/students \
  "{\"institutionId\":\"$TENANT_A_INST\",\"studentNo\":\"SMK-$SN\",\"firstName\":\"Smoke\",\"lastName\":\"Student\",\"personal\":{\"mobile\":\"+91 90000 00000\"}}" "Idempotency-Key: smoke-stu-$SN"
SMOKE_STUDENT=$(json 'd["student"]["id"]')
check "faculty sees no personal details"                 200 "$ARUN"  GET "/api/v1/students/$SMOKE_STUDENT"
grep -q '"personalVisible":false' /tmp/smoke-body && { pass=$((pass+1)); echo "  PASS  personal details hidden from faculty"; } || { fail=$((fail+1)); echo "  FAIL  personal details visible to faculty"; }
check "cross-tenant student read → 404"                  404 "$BELA"  GET "/api/v1/students/$SMOKE_STUDENT"
printf '%%PDF-1.4\n%%%%EOF\n' > /tmp/smoke-doc.pdf
got=$(curl -s -o /tmp/smoke-body -w '%{http_code}' -X POST -H "Authorization: Bearer $INES" -F "file=@/tmp/smoke-doc.pdf;type=application/pdf" \
  "$API_URL/api/v1/students/$SMOKE_STUDENT/documents?category=IDENTITY_PROOF")
if [[ $got == 201 ]] && grep -q '"scanStatus":"PENDING"' /tmp/smoke-body; then pass=$((pass+1)); echo "  PASS  document upload lands in quarantine (201, PENDING)"; else fail=$((fail+1)); echo "  FAIL  document upload: $got $(head -c 200 /tmp/smoke-body)"; fi
SMOKE_DOC=$(json 'd[0]["id"]')
check "quarantined document cannot be downloaded"        409 "$INES"  GET "/api/v1/students/$SMOKE_STUDENT/documents/$SMOKE_DOC/content"
check "cancel the smoke student"                         200 "$INES"  POST "/api/v1/students/$SMOKE_STUDENT/status" '{"status":"CANCELLED","reason":"smoke test","version":0}'

echo "Student bulk import (1.6): upload, background validation and processing through RabbitMQ ..."
check "import template download"                         200 "$ALICE" GET "/api/v1/student-imports/template?format=XLSX&institutionId=$TENANT_A_INST"
check "faculty cannot use student imports"               403 "$ARUN"  GET /api/v1/student-imports
SMK_ID="SMK-$(date +%s)"
printf 'Student ID,First name,Last name,Personal email\n%s,Smoke,Import,%s@example.test\n%s-2,Broken,Row,not-an-email\n' "$SMK_ID" "$(echo "$SMK_ID" | tr A-Z a-z)" "$SMK_ID" > /tmp/smoke-import.csv
got=$(curl -s -o /tmp/smoke-body -w '%{http_code}' -X POST -H "Authorization: Bearer $INES" -F "file=@/tmp/smoke-import.csv;type=text/csv" \
  "$API_URL/api/v1/student-imports?institutionId=$TENANT_A_INST&importKey=smoke-$SMK_ID")
if [[ $got == 201 ]] && grep -q '"status":"UPLOADED"' /tmp/smoke-body; then pass=$((pass+1)); echo "  PASS  CSV upload creates an import job (201)"; else fail=$((fail+1)); echo "  FAIL  import upload: $got $(head -c 200 /tmp/smoke-body)"; fi
IMP=$(json 'd["id"]'); IMP_V=$(json 'd["version"]')
again=$(curl -s -X POST -H "Authorization: Bearer $INES" -F "file=@/tmp/smoke-import.csv;type=text/csv" \
  "$API_URL/api/v1/student-imports?institutionId=$TENANT_A_INST&importKey=smoke-$SMK_ID" | python3 -c 'import sys,json; print(json.load(sys.stdin).get("id",""))')
[[ $again == "$IMP" ]] && { pass=$((pass+1)); echo "  PASS  repeated upload with the same import key returns the same job"; } || { fail=$((fail+1)); echo "  FAIL  import key idempotency"; }
check "other tenant cannot see the import"               404 "$BELA"  GET "/api/v1/student-imports/$IMP"
check "save mapping and queue validation"                200 "$INES"  PUT "/api/v1/student-imports/$IMP/mapping" "{\"columns\":[{\"column\":0,\"field\":\"student_no\"},{\"column\":1,\"field\":\"first_name\"},{\"column\":2,\"field\":\"last_name\"},{\"column\":3,\"field\":\"personal_email\"}],\"version\":$IMP_V}"
wait_status() { for _ in $(seq 1 90); do curl -s -o /tmp/smoke-body -H "Authorization: Bearer $INES" "$API_URL/api/v1/student-imports/$IMP"; grep -qE "\"status\":\"($1)\"" /tmp/smoke-body && return 0; sleep 1; done; return 1; }
if wait_status READY && [[ $(json 'd["counts"]["valid"]') == 1 && $(json 'd["counts"]["invalid"]') == 1 ]]; then pass=$((pass+1)); echo "  PASS  background validation: 1 valid, 1 invalid"; else fail=$((fail+1)); echo "  FAIL  validation: $(head -c 300 /tmp/smoke-body)"; fi
check "error report download"                            200 "$INES"  GET "/api/v1/student-imports/$IMP/reports/errors?format=CSV"
grep -q "Not a valid email address" /tmp/smoke-body && { pass=$((pass+1)); echo "  PASS  error report names the row, column and correction"; } || { fail=$((fail+1)); echo "  FAIL  error report content"; }
IMP_V=$(curl -s -H "Authorization: Bearer $INES" "$API_URL/api/v1/student-imports/$IMP" | python3 -c 'import sys,json; print(json.load(sys.stdin)["version"])')
check "all-or-nothing refused with invalid rows"         409 "$INES"  POST "/api/v1/student-imports/$IMP/confirm" "{\"mode\":\"ALL_OR_NOTHING\",\"version\":$IMP_V,\"expectedRows\":1,\"acknowledged\":true}"
check "confirm valid rows only"                          200 "$INES"  POST "/api/v1/student-imports/$IMP/confirm" "{\"mode\":\"VALID_ROWS_ONLY\",\"version\":$IMP_V,\"expectedRows\":1,\"acknowledged\":true}"
if wait_status "COMPLETED|PARTIALLY_COMPLETED|FAILED" && [[ $(json 'd["progress"]["imported"]') == 1 ]]; then pass=$((pass+1)); echo "  PASS  background import created 1 student"; else fail=$((fail+1)); echo "  FAIL  import processing: $(head -c 300 /tmp/smoke-body)"; fi
check "imported student is searchable"                   200 "$INES"  GET "/api/v1/students?q=$SMK_ID"
IMP_STUDENT=$(json 'd["items"][0]["id"]')
check "cancel the imported smoke student"                200 "$INES"  POST "/api/v1/students/$IMP_STUDENT/status" '{"status":"CANCELLED","reason":"smoke test","version":0}'


echo "Increment 1.7: dashboards, reports, notifications, integrations, API clients, webhooks ..."
check "executive dashboard (org admin)"                  200 "$ALICE" GET /api/v1/insights/executive
check "academic dashboard"                               200 "$ALICE" GET /api/v1/insights/academic
check "student dashboard (institution admin)"            200 "$INES"  GET /api/v1/insights/students
check "import dashboard denied to faculty"               403 "$ARUN"  GET /api/v1/insights/imports
check "report catalogue denied to faculty"               403 "$ARUN"  GET /api/v1/reports
check "students report (paged)"                          200 "$INES"  GET "/api/v1/reports/students?size=5"
check "students report CSV export"                       200 "$INES"  GET "/api/v1/reports/students/export?format=CSV"
check "notifications with category filter"               200 "$ALICE" GET "/api/v1/notifications?category=imports"
check "integrations denied to institution admin"         403 "$INES"  GET /api/v1/integrations
check "integration adapters (org admin)"                 200 "$ALICE" GET /api/v1/integrations/adapters
check "webhook event catalogue"                          200 "$ALICE" GET /api/v1/webhooks/event-types
check "webhook to a cloud metadata address is refused"   400 "$ALICE" POST /api/v1/webhooks '{"name":"smoke ssrf","url":"http://169.254.169.254/latest","eventTypes":["student.created"]}'
check "API client registration (read scope)"             201 "$ALICE" POST /api/v1/api-clients "{\"name\":\"Smoke $SMK_ID\",\"scopes\":[\"org_unit.read\"]}"
SMK_KEY=$(json 'd["apiKey"]'); SMK_CLIENT=$(json 'd["client"]["id"]')
key_check() {
  local desc=$1 expected=$2 method=$3 path=$4 got
  got=$(curl -s -o /dev/null -w '%{http_code}' -X "$method" -H "X-Api-Key: $SMK_KEY" -H 'Content-Type: application/json' "$API_URL$path")
  if [[ $got == "$expected" ]]; then pass=$((pass+1)); echo "  PASS  $desc ($got)"; else fail=$((fail+1)); echo "  FAIL  $desc: expected $expected, got $got"; fi
}
key_check "API key reads within its scope"                200 GET  /api/v1/org-units
check "API client shows its effective scopes (KI-59)"    200 "$ALICE" GET "/api/v1/api-clients/$SMK_CLIENT"
grep -q '"effectiveScopes":\["org_unit.read"\]' /tmp/smoke-body && { pass=$((pass+1)); echo "  PASS  effective scopes computed from the owner's current permissions"; } || { fail=$((fail+1)); echo "  FAIL  effective scopes: $(head -c 300 /tmp/smoke-body)"; }
check "service identities (org admin)"                    200 "$ALICE" GET /api/v1/service-identities
check "service identities denied to institution admin"    403 "$INES"  GET /api/v1/service-identities
check "service identity envelope must be read-only"       400 "$ALICE" POST /api/v1/service-identities "{\"name\":\"Smoke $SMK_ID\",\"purpose\":\"smoke\",\"businessCustodianId\":\"$(curl -s -H "Authorization: Bearer $ALICE" "$API_URL/api/v1/me" | python3 -c 'import sys,json; print(json.load(sys.stdin)["userId"])')\",\"technicalCustodianId\":\"$(curl -s -H "Authorization: Bearer $ALICE" "$API_URL/api/v1/me" | python3 -c 'import sys,json; print(json.load(sys.stdin)["userId"])')\",\"envelope\":[\"student.manage\"]}"
key_check "API key outside its scope"                     403 GET  /api/v1/users
key_check "API key cannot write"                          403 POST /api/v1/org-units
check "revoke the smoke API client"                      200 "$ALICE" POST "/api/v1/api-clients/$SMK_CLIENT/revoke" '{"reason":"smoke test"}'
key_check "revoked API key is rejected"                   401 GET  /api/v1/org-units

echo "Provisioning service account is least-privilege (real Keycloak) ..."
PROV=$(curl -s -X POST "$KEYCLOAK_URL/realms/ivorovi/protocol/openid-connect/token" -d grant_type=client_credentials \
  -d client_id=ivorovi-provisioner --data-urlencode "client_secret=$KEYCLOAK_PROVISIONER_SECRET" | python3 -c 'import sys,json; print(json.load(sys.stdin).get("access_token",""))')
probe() {
  local desc=$1 expected=$2 path=$3 got
  got=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $PROV" "$KEYCLOAK_URL$path")
  if [[ $got == "$expected" ]]; then pass=$((pass+1)); echo "  PASS  $desc ($got)"; else fail=$((fail+1)); echo "  FAIL  $desc: expected $expected, got $got"; fi
}
probe "provisioner can query users"                    200 "/admin/realms/ivorovi/users?max=1"
probe "provisioner cannot read clients"                 403 "/admin/realms/ivorovi/clients"
probe "provisioner cannot read realm roles"             403 "/admin/realms/ivorovi/roles"
probe "provisioner cannot read the master realm"        403 "/admin/realms/master/users"

check "audit trail readable by org admin"               200 "$ALICE" GET "/api/v1/audit-events?size=5"
check "audit chain verification"                        200 "$ALICE" GET /api/v1/audit-events/verification
grep -q '"valid":true' /tmp/smoke-body && { pass=$((pass+1)); echo "  PASS  audit hash chain is valid"; } || { fail=$((fail+1)); echo "  FAIL  audit chain invalid: $(cat /tmp/smoke-body)"; }

echo "Result: $pass passed, $fail failed"
[[ $fail -eq 0 ]]
