#!/usr/bin/env bash
# Repeatable demonstration dataset for local development (see docs/runbooks/demo-data.md).
#   scripts/demo-data.sh reset | seed | verify | rebuild
set -euo pipefail
cd "$(dirname "$0")/.."
exec python3 scripts/demo/demo_data.py "$@"
