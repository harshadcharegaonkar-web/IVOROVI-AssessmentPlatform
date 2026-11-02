# CI/CD runbook

Pipeline: `.github/workflows/ci.yml` (GitHub Actions). Triggers on pushes to `main` and on every pull request.
**Status: not yet executed on GitHub.** The repository has not been pushed yet. Every job's commands have been run
locally with passing results, except the Trivy scans (see "Local equivalents").

## Jobs

| Job | Runs | Gate |
|---|---|---|
| `backend` | Gradle wrapper validation → `./gradlew :backend:api:build` (unit, Testcontainers integration, Spring Modulith verification) → OpenAPI drift check | Any test failure; `docs/api/openapi.json` differs from what the tests export |
| `frontend` | `pnpm install --frozen-lockfile` → regenerate API client and check drift → lint → typecheck → unit tests → build | Lint/type/test failure; stale generated client; lockfile mismatch |
| `e2e` (needs both) | Throwaway secrets → `docker compose --profile app up --build --wait` → Trivy scan of `ivorovi-api` and `ivorovi-web` images (CRITICAL, fixable) → smoke test via web container → Playwright → logs on failure → teardown | Any failure |
| `security` | Trivy filesystem scan (vulnerabilities and secrets, HIGH and CRITICAL, fixable), including `backend/api/gradle.lockfile` and `frontend/pnpm-lock.yaml` | Any finding at threshold |

Artifacts: `backend-test-reports` (always), `api-jar`, `web-dist` (3 days), `playwright-report` (on failure).

## Security posture of the pipeline

* `permissions: contents: read` workflow-wide. No job needs write access or repository secrets.
* Every third-party action is pinned to a full commit SHA (the comment gives the tag). Update the SHA and the
  comment together, from the action's release page, and review the diff.
* Pull requests from forks get no secrets by default (and none are used).
* Concurrency group cancels superseded runs on the same ref.

## Recommended repository settings (do on first push)

1. Branch protection on `main`: require PRs, require status checks `backend`, `frontend`, `e2e` and `security`,
   require linear history, disallow force pushes.
2. Enable Dependabot alerts and secret scanning (push protection).
3. Actions settings: "Read repository contents" default token permission; require approval for first-time contributors.

## Local equivalents

```bash
./gradlew :backend:api:build && git diff --exit-code -- docs/api/openapi.json
```

```bash
cd frontend && pnpm install --frozen-lockfile && pnpm generate:api && git diff --exit-code -- packages/api-client/src/generated && pnpm lint && pnpm typecheck && pnpm test && pnpm build
```

```bash
./scripts/dev-setup.sh && docker compose -f deploy/compose/compose.yaml --profile app up -d --build --wait && API_URL=http://localhost:8081 ./scripts/smoke-test.sh
```

```bash
cd frontend/apps/web && set -a && . ../../../deploy/compose/.env && set +a && pnpm e2e
```

Trivy has no local equivalent in our build sandbox. Running the scanner binary or image was not permitted there;
on a developer workstation use `trivy fs --scanners vuln,secret --severity HIGH,CRITICAL --ignore-unfixed .`.

## Triage guide

| Symptom | Likely cause | Action |
|---|---|---|
| `backend` fails on OpenAPI diff | API changed without regenerating | Run the backend tests locally, commit `docs/api/openapi.json`, then `pnpm generate:api` and commit the client |
| `frontend` fails on generated client drift | `openapi.json` committed without client regeneration | `cd frontend && pnpm generate:api`, commit |
| Gradle fails with lock state errors | Dependency changed without updating locks | `./gradlew :backend:api:dependencies --write-locks`, review and commit `gradle.lockfile` |
| Testcontainers cannot start | Docker unavailable on runner or image pull rate-limited | Re-run. Persistent Docker Hub limits mean we should mirror images to a registry we control |
| `e2e` stack not healthy | See "Container logs on failure" step output | Usually Keycloak start-up time or a migration error |
| Trivy finding | New CVE in a dependency or base image | Upgrade. If no fix exists it is ignored (`ignore-unfixed`). Otherwise record a time-boxed exception in `.trivyignore` with justification and an expiry date |

## Delivery (not yet implemented)

Image publishing to a registry, signed images (cosign), SBOM generation, environment promotion (staging →
production) and Helm releases are planned for Phase 8. Until then the pipeline is CI-only.
