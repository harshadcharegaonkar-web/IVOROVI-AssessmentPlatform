# ADR-0002: Keycloak for authentication, Ivorovi for authorization

* Status: Accepted (approved by the product owner, 2026-11-02)

## Decision
* Keycloak is bundled as the identity provider in all topologies. The API is a standard OAuth2 resource server,
  so customers may replace the IdP with any OIDC provider that meets the token contract in
  `runbooks/configuration.md`.
* The tenant is bound to the signed `tid` claim. The API then requires an ACTIVE tenant and an ACTIVE
  `user_account` for `(tid, sub)`. Request headers, query parameters or bodies can never select a tenant.
* Authorization is a code-defined permission catalog, roles stored in the database, and scoped, time-bounded
  role assignments, evaluated server-side (`@authz`). Token role claims are ignored (covered by a test).

## Consequences
* Changing a user's access does not require IdP changes and takes effect on the next request.
* Every request performs tenant + account + grants lookups (3 small indexed queries). A short-TTL cache is planned
  for Phase 1 together with cache invalidation on role changes.
