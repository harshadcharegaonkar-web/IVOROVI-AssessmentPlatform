# API configuration reference

All settings are environment variables, so the same artifact runs in SaaS, dedicated and on-premises
deployments. Secrets have no defaults: supply them from the platform secret store (Kubernetes Secrets,
Vault, or a cloud secret manager) and never commit them.

| Variable | Required | Default | Purpose |
|---|---|---|---|
| `IVOROVI_DB_URL` | yes | — | JDBC URL, e.g. `jdbc:postgresql://db:5432/ivorovi` |
| `IVOROVI_DB_APP_USER` / `IVOROVI_DB_APP_PASSWORD` | yes (password) | `ivorovi_app` | Runtime role: **must not** have `BYPASSRLS` or superuser |
| `IVOROVI_DB_OWNER_USER` / `IVOROVI_DB_OWNER_PASSWORD` | yes (password) | `ivorovi_owner` | Schema owner, used only by Flyway migrations |
| `IVOROVI_DB_POOL_SIZE` | no | 20 | Hikari pool size |
| `IVOROVI_REDIS_HOST/PORT/PASSWORD/SSL` | host yes | localhost/6379/—/false | Valkey or Redis-protocol cache |
| `IVOROVI_RABBITMQ_HOST/PORT/USER/PASSWORD/VHOST` | yes | localhost/5672/ivorovi/—// | Message broker for integration events |
| `IVOROVI_S3_ENDPOINT` | no | AWS default | Any S3-compatible endpoint (SeaweedFS, MinIO, Ceph RGW, GCS interop) |
| `IVOROVI_S3_REGION/BUCKET` | no | ap-south-1 / ivorovi | |
| `IVOROVI_S3_ACCESS_KEY/SECRET_KEY` | no | — | Leave empty to use IAM roles / workload identity |
| `IVOROVI_S3_PATH_STYLE` | no | true | Path-style addressing (needed by most self-hosted stores) |
| `IVOROVI_S3_CREATE_BUCKET` | no | false | Create the bucket on start (dev only) |
| `IVOROVI_SMTP_HOST/PORT/USER/PASSWORD`, `IVOROVI_MAIL_FROM` | for email | localhost/1025 | Outbound SMTP relay |
| `IVOROVI_OIDC_ISSUER` | yes | — | Exact `iss` claim the tokens carry |
| `IVOROVI_OIDC_JWKS` | yes | — | Signing-key URL (may be an internal address of the same IdP) |
| `IVOROVI_OIDC_AUDIENCE` | no | ivorovi-api | Required `aud` value |
| `IVOROVI_DEPLOYMENT_MODE` | no | SAAS | `SAAS`, `DEDICATED`, `ON_PREM` (operational toggles only) |
| `IVOROVI_ENVIRONMENT` | no | PRODUCTION | `DEVELOPMENT`, `TEST`, `STAGING`, `PRODUCTION` (guards dev tooling) |
| `IVOROVI_FEATURES_DEPLOYMENT_DISABLED` | no | — | Comma-separated flags or prefixes forced off for every tenant, e.g. `ai.*` |
| `IVOROVI_RATE_LIMIT_ENABLED` | no | true | Disables all API rate limiting (emergency use only) |
| `IVOROVI_RATE_LIMIT_USER_READ` / `_USER_WRITE` | no | 300 / 60 | Requests per minute per user for GET/HEAD and other methods |
| `IVOROVI_RATE_LIMIT_TENANT` | no | 3000 | Requests per minute per tenant |
| `IVOROVI_RATE_LIMIT_FAILED_AUTH_IP` | no | 30 | Failed authentications per minute per client IP before 429 (behind a proxy, configure forwarded headers so the client IP is correct) |
| `IVOROVI_RATE_LIMIT_PUBLIC_IP` | no | 20 | Requests per minute per client IP to the public invitation endpoints |
| `IVOROVI_APP_BASE_URL` | yes for invitations | http://localhost:5173 | Public URL of the web app; invitation links point to `{url}/invitation#token=…` |
| `IVOROVI_INVITATION_TTL` | no | P7D | Invitation validity (ISO-8601 duration) |
| `IVOROVI_IDP_ADMIN_URL` / `IVOROVI_IDP_REALM` | yes for provisioning | http://localhost:8180 / ivorovi | Keycloak base URL reachable from the API, and realm |
| `IVOROVI_IDP_PROVISIONER_CLIENT_ID` / `IVOROVI_IDP_PROVISIONER_SECRET` | yes for provisioning | ivorovi-provisioner / — | Confidential client whose service account holds only realm-management `manage-users`, `view-users`, `query-users` (D-P1-04) |
| `IVOROVI_OPENAPI_ENABLED` | no | true (false with `prod` profile) | Exposes `/v3/api-docs` and Swagger UI |
| `SPRING_PROFILES_ACTIVE` | no | — | `prod` enables JSON (ECS) logs. Never use `dev` or `seed` outside development |

## Identity provider requirements (any OIDC provider; Keycloak is bundled)

* Access tokens must carry `tid` (the Ivorovi tenant UUID), `sub` and `aud` = `ivorovi-api`.
* `tid` must come from an attribute that end users **cannot edit** (in Keycloak, a user-profile attribute with
  admin-only edit permission, as in the dev realm).
* Roles in tokens are ignored. Authorization comes from Ivorovi's own role assignments.
* The web client is public with PKCE S256. Password grant is enabled **only** on the dev realm's
  `ivorovi-dev-cli` client.

## Web container

| Variable | Purpose |
|---|---|
| `OIDC_AUTHORITY` | Issuer URL that the browser can reach |
| `OIDC_CLIENT_ID` | Public client id (default `ivorovi-web`) |
| `API_UPSTREAM` | Where nginx proxies `/api/` (default `http://api:8080`) |
| `API_BASE_URL` | Leave empty (same-origin through the proxy) unless the API is served from another origin |

## Database prerequisites (managed PostgreSQL / on-prem DBA)

Run the equivalent of `deploy/compose/postgres/init/01-init.sh`: create the `ivorovi_owner` and `ivorovi_app`
roles, the `ivorovi` schema owned by the owner role, and default privileges granting DML to the app role.
PostgreSQL 16 or newer is required (`UNIQUE NULLS NOT DISTINCT`).
