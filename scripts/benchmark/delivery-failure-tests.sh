#!/usr/bin/env bash
# Increment 2.0-F failure tests (repeatable). Runs the delivery harness with N virtual candidates while it
#   1. restarts Valkey (cache and rate limiter: the limiter fails open, requests continue),
#   2. restarts RabbitMQ (event bus: delivery requests do not depend on it),
#   3. pauses PostgreSQL for 10 s (database stall: requests wait or fail, then recover),
#   4. kills the delivery process with SIGKILL (crash), keeps it down past the outage threshold, and restarts it.
# It then checks, through the real staff API, that the platform detected the outage automatically, recorded the
# affected candidates once delivery was back, and that the services are healthy again.
#
# Requirements: the compose stack (deploy/compose), the administrative API on :8080 with the outage detector enabled
# (default), backend/api/build/libs/ivorovi-api.jar, DEV_USER_PASSWORD (deploy/compose/.env). No other DELIVERY process may
# be running (the detector treats delivery as down only when no delivery instance answers); the script stops one on :8090.
#
#   scripts/benchmark/delivery-failure-tests.sh [candidates=200]
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
. scripts/api-env.sh
N="${1:-200}"
PORT=8091
OUT="docs/performance/delivery-failure-tests.json"
LOG_DIR="${TMPDIR:-/tmp}/ivorovi-failure-tests"
mkdir -p "$LOG_DIR"
STAFF=http://localhost:8080
KC=http://localhost:8180

say() { printf '[%s] %s\n' "$(date -u +%H:%M:%S)" "$*"; }
pid_on() { ps -eo pid,args | awk -v p="--server.port=$1" '$2 == "java" && index($0, p) {print $1}'; }
token() {
  curl -sf -X POST "$KC/realms/ivorovi/protocol/openid-connect/token" -d grant_type=password -d client_id=ivorovi-dev-cli -d scope=openid \
    --data-urlencode "username=alice.admin@aurora.test" --data-urlencode "password=$DEV_USER_PASSWORD" | python3 -c 'import sys,json; print(json.load(sys.stdin)["access_token"])'
}
start_delivery() {
  IVOROVI_PROCESS_ROLE=DELIVERY \
  IVOROVI_DELIVERY_OIDC_ISSUER=http://localhost:8099/realms/harness-candidates \
  IVOROVI_DELIVERY_OIDC_JWKS=http://localhost:8099/realms/harness-candidates/protocol/openid-connect/certs \
  IVOROVI_DELIVERY_CODE_ATTEMPTS_PER_IP=100000 \
  setsid nohup java -Xmx1g -jar backend/api/build/libs/ivorovi-api.jar --spring.profiles.active=dev --server.port=$PORT \
    >> "$LOG_DIR/delivery.log" 2>&1 < /dev/null &
  for _ in $(seq 1 90); do curl -sf "localhost:$PORT/actuator/health/readiness" > /dev/null && return 0; sleep 2; done
  echo "delivery did not start" >&2; return 1
}
# newest PLATFORM incident of Aurora as "id startedAt endedAt affected status"
incident() {
  curl -sf -H "Authorization: Bearer $(token)" "$STAFF/api/v1/exam/incidents" | python3 -c '
import sys, json
rows = [r for r in json.load(sys.stdin) if r.get("source") == "PLATFORM"]
rows.sort(key=lambda r: r["startedAt"], reverse=True)
r = rows[0] if rows else None
print("" if not r else " ".join(str(x) for x in (r["id"], r["startedAt"], r.get("endedAt") or "-", r["affected"], r["status"])))'
}

for p in 8090 $PORT; do
  old=$(pid_on $p || true)
  if [ -n "$old" ]; then say "stopping delivery process on :$p (graceful, deregisters its heartbeat)"; kill "$old"; sleep 5; fi
done
before=$(incident)
say "starting delivery on :$PORT"
start_delivery
say "harness: $N candidates, 6 minutes"
java backend/devtools/harness/DeliveryHarness.java --candidates "$N" --duration PT6M --ramp PT15S --heartbeat PT5S --save PT5S \
  --label "failure-$N" --pid "$(pid_on $PORT)" --out "$LOG_DIR/harness.json" > "$LOG_DIR/harness.log" 2>&1 &
HARNESS=$!
T0=$(date +%s)
at() { while [ $(( $(date +%s) - T0 )) -lt "$1" ]; do sleep 1; done; }
events=()

at 60;  say "restart Valkey";   docker restart ivorovi-valkey-1 > /dev/null;   events+=("{\"t\":$(( $(date +%s) - T0 )),\"event\":\"valkey restarted\"}")
at 100; say "restart RabbitMQ"; docker restart ivorovi-rabbitmq-1 > /dev/null; events+=("{\"t\":$(( $(date +%s) - T0 )),\"event\":\"rabbitmq restarted\"}")
at 140; say "pause PostgreSQL 10 s"; docker pause ivorovi-postgres-1 > /dev/null; sleep 10; docker unpause ivorovi-postgres-1 > /dev/null
events+=("{\"t\":$(( $(date +%s) - T0 - 10 )),\"event\":\"postgres paused 10 s\"}")
at 180; say "kill -9 delivery"; kill -9 "$(pid_on $PORT)"; KILLED=$(date +%s); events+=("{\"t\":$(( KILLED - T0 )),\"event\":\"delivery killed (SIGKILL)\"}")

detected=""; detect_s=""
for _ in $(seq 1 60); do
  cur=$(incident || true)
  if [ -n "$cur" ] && [ "$cur" != "$before" ] && [ "$(echo "$cur" | awk '{print $3}')" = "-" ]; then detected="$cur"; detect_s=$(( $(date +%s) - KILLED )); break; fi
  sleep 3
done
say "outage detected after ${detect_s:-?} s: ${detected:-NOT DETECTED}"
at 270; say "restart delivery"; start_delivery; RESTARTED=$(date +%s); events+=("{\"t\":$(( RESTARTED - T0 )),\"event\":\"delivery restarted and ready\"}")

resolved=""; resolve_s=""
for _ in $(seq 1 40); do
  cur=$(incident || true)
  if [ -n "$detected" ] && [ "$(echo "$cur" | awk '{print $1}')" = "$(echo "$detected" | awk '{print $1}')" ] && [ "$(echo "$cur" | awk '{print $3}')" != "-" ]; then
    resolved="$cur"; resolve_s=$(( $(date +%s) - RESTARTED )); break
  fi
  sleep 3
done
say "incident resolved after ${resolve_s:-?} s: ${resolved:-NOT RESOLVED}"
wait "$HARNESS" || true
api=$(curl -s -o /dev/null -w '%{http_code}' "$STAFF/actuator/health/readiness"); del=$(curl -s -o /dev/null -w '%{http_code}' "localhost:$PORT/actuator/health/readiness")
affected=$(echo "$resolved" | awk '{print $4}')
checked=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["checkedIn"])' "$LOG_DIR/harness.json")
ok=true
[ -n "$detected" ] || ok=false
[ -n "$resolved" ] || ok=false
[ "${affected:-0}" -ge "$checked" ] || ok=false
[ "$api" = 200 ] && [ "$del" = 200 ] || ok=false

python3 - "$LOG_DIR/harness.json" "$OUT" "$N" "${detect_s:-null}" "${resolve_s:-null}" "${affected:-0}" "$checked" "$api" "$del" "$ok" "[$(IFS=,; echo "${events[*]}")]" <<'PY'
import json, sys
h = json.load(open(sys.argv[1]))
out = {"candidates": int(sys.argv[3]), "events": json.loads(sys.argv[11]),
       "outageDetectedAfterSeconds": None if sys.argv[4] == "null" else int(sys.argv[4]),
       "incidentResolvedAfterRestartSeconds": None if sys.argv[5] == "null" else int(sys.argv[5]),
       "affectedCandidatesRecorded": int(sys.argv[6]), "candidatesCheckedIn": int(sys.argv[7]),
       "healthAfter": {"adminApi": int(sys.argv[8]), "delivery": int(sys.argv[9])}, "passed": sys.argv[10] == "true",
       "harness": h}
json.dump(out, open(sys.argv[2], "w"), indent=2)
print(json.dumps({k: v for k, v in out.items() if k != "harness"}, indent=2))
PY
tail -n 25 "$LOG_DIR/harness.log"
$ok && say "PASSED" || { say "FAILED"; exit 1; }
