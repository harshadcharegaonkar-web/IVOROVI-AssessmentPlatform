#!/usr/bin/env bash
# Generates synthetic tenants/institutions/users in the local stack. Arguments are passed to the generator, e.g.
#   ./scripts/seed.sh --ivorovi.seed.tenants=5 --ivorovi.seed.users-per-tenant=1000 --ivorovi.seed.random-seed=7
set -euo pipefail
cd "$(dirname "$0")/.."
. scripts/api-env.sh
JAR=backend/api/build/libs/ivorovi-api.jar
[[ -f $JAR ]] || ./gradlew :backend:api:bootJar -q
exec java -jar "$JAR" --spring.profiles.active=dev,seed "$@"
