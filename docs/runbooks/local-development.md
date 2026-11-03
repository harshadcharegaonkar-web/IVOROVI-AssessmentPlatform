# Local development and deployment verification

Verified on 2026-11-02 from an empty Docker state (see "Verification log" at the end).

## Prerequisites

| Tool | Version |
|---|---|
| Docker Engine + Compose plugin | Docker 24+ / Compose v2.20+ |
| JDK | 21 (the Gradle wrapper downloads Gradle 9.8.2) |
| Node.js + pnpm | Node 22, pnpm 10 (`corepack enable`) |
| Free ports (bound to 127.0.0.1) | 5432, 6379, 5672, 15672, 8333, 1025, 8025, 8180, 8080, 8081, 5173 |

## 1. Generate local secrets

```bash
./scripts/dev-setup.sh
```

This creates `deploy/compose/.env` (git-ignored, mode 600) with random passwords. `DEV_USER_PASSWORD` in that
file is the password for all seeded login users.

## 2a. Run everything in containers (closest to a deployment)

```bash
./gradlew :backend:api:bootJar
```

```bash
cd frontend && pnpm install --frozen-lockfile && pnpm build && cd ..
```

```bash
docker compose -f deploy/compose/compose.yaml --profile app up -d --build --wait
```

Open http://localhost:8081 and sign in, for example as `alice.admin@aurora.test`.

## 2b. Or run the infrastructure in containers and the apps from source (fast feedback)

```bash
docker compose -f deploy/compose/compose.yaml up -d --wait
```

```bash
. scripts/api-env.sh && ./gradlew :backend:api:bootRun --args='--spring.profiles.active=dev'
```

```bash
cd frontend && pnpm --filter @ivorovi/web dev
```

Open http://localhost:5173. Vite proxies `/api` to the API on port 8080.

## Seeded development identities (synthetic)

| User | Tenant | Role / scope | Demonstrates |
|---|---|---|---|
| alice.admin@aurora.test | Aurora | Organization Administrator (tenant) | full tenant administration |
| ines.instadmin@aurora.test | Aurora | Institution Administrator (Aurora Institute of Technology) | scope confinement |
| arun.faculty@aurora.test | Aurora | Faculty (Aurora Institute of Technology) | read-only, institution-scoped |
| asha.student@aurora.test | Aurora | Student | minimal navigation, denied admin pages |
| bela.admin@borealis.test | Borealis | Organization Administrator | a second tenant for isolation checks |
| ghost.user@aurora.test | Aurora (IdP only) | no Ivorovi account | rejected by the API |

Fixtures live in `backend/api/src/main/resources/db/devdata` (loaded **only** with the `dev` profile) and
`deploy/compose/keycloak/realm-ivorovi.json`.

## Generate synthetic data

```bash
./scripts/seed.sh --ivorovi.seed.tenants=3 --ivorovi.seed.institutions-per-tenant=3 \
  --ivorovi.seed.users-per-tenant=200 --ivorovi.seed.random-seed=42
```

* The generator lives in the separate `backend:devtools` application (`ivorovi-devtools.jar`); the production API jar contains no seeding code or Datafaker.
* The generator refuses to run unless `ivorovi.deployment.environment` is DEVELOPMENT or TEST.
* Tenant codes derive from the random seed, so re-running with the **same** seed fails on duplicate tenant codes. Use a new seed for more data.
* Generated accounts (`synthetic|<uuid>` subjects, `*.example.test` emails) cannot sign in. They exist for volume and isolation testing.

## Verify a deployment

```bash
./scripts/smoke-test.sh
```

```bash
API_URL=http://localhost:8081 ./scripts/smoke-test.sh
```

```bash
cd frontend/apps/web && set -a && . ../../../deploy/compose/.env && set +a && pnpm e2e
```

The smoke test obtains real tokens from Keycloak and checks: authentication, tenant resolution, rejection of
identities without an account, cross-tenant 404s, authorization denials and escalation attempts, the 409 rule
for AI features, and audit-chain integrity.

## Run the test suites

```bash
./gradlew :backend:api:build
```

```bash
cd frontend && pnpm lint && pnpm typecheck && pnpm test && pnpm build
```

Backend integration tests start their own containers through Testcontainers, so Docker must be running.

## Useful local UIs

| URL | What |
|---|---|
| http://localhost:8081 | Web app (container) |
| http://localhost:8080/swagger-ui.html | API documentation (dev only) |
| http://localhost:8180/admin | Keycloak admin (user `admin`, password `KEYCLOAK_ADMIN_PASSWORD`) |
| http://localhost:15672 | RabbitMQ management (user `ivorovi`) |
| http://localhost:8025 | Mailpit (captured emails) |

## Reset

```bash
docker compose -f deploy/compose/compose.yaml --profile app down -v
```

This deletes all local data. Keycloak re-imports the realm on the next start.

## Troubleshooting

* **Keycloak realm changes not visible:** realm import only happens when the realm does not exist. Reset (above) or remove the realm in the admin console.
* **`no_active_account` after login:** the Keycloak user has no matching `user_account` row in the token's tenant. This is expected for `ghost.user`.
* **Ports in use:** all ports bind to 127.0.0.1. Stop the conflicting local service or change the mapping in `compose.yaml`.

## Upgrading an existing local environment to increment 1.2

Increment 1.2 adds the Keycloak client `ivorovi-provisioner` (creates logins when invitations are accepted).

1. `./scripts/dev-setup.sh` — adds the new `KEYCLOAK_PROVISIONER_SECRET` to `deploy/compose/.env` and keeps
   every existing secret.
2. Keycloak imports the realm only on first start (KI-11). Either reset the Keycloak database, or create the
   client in the running realm:

   ```bash
   set -a; . deploy/compose/.env; set +a
   docker exec ivorovi-keycloak-1 bash -c "
     /opt/keycloak/bin/kcadm.sh config credentials --server http://localhost:8080 --realm master --user admin --password '$KEYCLOAK_ADMIN_PASSWORD' &&
     /opt/keycloak/bin/kcadm.sh create clients -r ivorovi -s clientId=ivorovi-provisioner -s enabled=true -s publicClient=false \
       -s serviceAccountsEnabled=true -s standardFlowEnabled=false -s directAccessGrantsEnabled=false -s secret='$KEYCLOAK_PROVISIONER_SECRET' &&
     /opt/keycloak/bin/kcadm.sh add-roles -r ivorovi --uusername service-account-ivorovi-provisioner --cclientid realm-management \
       --rolename manage-users --rolename view-users --rolename query-users"
   ```
3. Invitation emails arrive in Mailpit (http://localhost:8025); links point to `IVOROVI_APP_BASE_URL`.
