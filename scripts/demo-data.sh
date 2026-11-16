#!/usr/bin/env bash
# Demo dataset (D-18): one interconnected demo institution for all modules (see docs/demo-programme/demo-data.md).
#   scripts/demo-data.sh steps | rebuild | reset | seed | live | verify
set -euo pipefail
cd "$(dirname "$0")/.."
exec python3 scripts/demo/demo.py "$@"
