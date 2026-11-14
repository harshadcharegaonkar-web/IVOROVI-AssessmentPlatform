#!/usr/bin/env bash
# QA helper for the failure tests (D-7): crash or start the exam delivery process.
#   scripts/qa/delivery-process.sh kill|start
# Compose stack (default): kills / starts the "delivery" service (SIGKILL: a crash, not a graceful shutdown).
# Native process (QA_DELIVERY_MODE=native): kills whatever listens on port 8090 and starts the command in
# QA_DELIVERY_START (e.g. a script that runs the API jar with IVOROVI_PROCESS_ROLE=DELIVERY), logging to QA_DELIVERY_LOG.
# "start" waits until the readiness endpoint answers UP (at most 180 s).
set -euo pipefail
cd "$(dirname "$0")/../.."
action="${1:?kill|start}"
url="${DELIVERY_URL:-http://localhost:8090}"
mode="${QA_DELIVERY_MODE:-compose}"

wait_ready() {
  for _ in $(seq 1 90); do
    if curl -sf "$url/actuator/health/readiness" | grep -q UP; then return 0; fi
    sleep 2
  done
  echo "delivery process not ready at $url" >&2
  return 1
}

case "$mode:$action" in
  compose:kill) docker compose -f deploy/compose/compose.yaml --profile app kill -s SIGKILL delivery ;;
  compose:start) docker compose -f deploy/compose/compose.yaml --profile app start delivery; wait_ready ;;
  native:kill) fuser -k -KILL 8090/tcp || true ;;
  native:start)
    : "${QA_DELIVERY_START:?set QA_DELIVERY_START to the command that starts the delivery process}"
    setsid nohup bash -c "$QA_DELIVERY_START" >> "${QA_DELIVERY_LOG:-/dev/null}" 2>&1 < /dev/null &
    wait_ready ;;
  *) echo "usage: $0 kill|start (QA_DELIVERY_MODE=compose|native)" >&2; exit 2 ;;
esac
