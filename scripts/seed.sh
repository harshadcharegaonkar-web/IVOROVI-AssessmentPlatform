#!/usr/bin/env bash
# Generates synthetic tenants/institutions/users in the local stack. Arguments are passed to the generator, e.g.
#   ./scripts/seed.sh --ivorovi.seed.tenants=5 --ivorovi.seed.users-per-tenant=1000 --ivorovi.seed.random-seed=7
# Runs the separate devtools application (the production API jar contains no seeding code).
set -euo pipefail
cd "$(dirname "$0")/.."
. scripts/api-env.sh
JAR=backend/devtools/build/libs/ivorovi-devtools.jar
[[ -f $JAR ]] || ./gradlew :backend:devtools:bootJar -q
exec java -jar "$JAR" --spring.profiles.active=dev,seed "$@"
