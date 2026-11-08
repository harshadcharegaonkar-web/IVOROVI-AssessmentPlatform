# ADR-0008: Candidate identity boundary and exam application security

* Status: **Implemented in Increment 2.0** (proposed for acceptance with 2.0; conditions C-1 and C-4; decisions P2-03,
  Q-02, Q-03, Q-07)
* Applies to: everything candidates touch (exam portal, delivery API) from 2.0 on

## Decision

### Identity (C-1, Q-02, Q-03)

1. **Separate realm** `ivorovi-candidates` (realm-as-code: `deploy/compose/keycloak/realm-ivorovi-candidates.json`) with
   its own clients, password policy, brute-force lockout (5 failures, growing wait up to 15 min), short sessions
   (access 5 min, idle 30 min, max 4 h), security emails and event log. The staff realm is unchanged.
2. **Verified email is mandatory.** Keycloak 26.8 issues no token before the address is verified, and asks for the
   password only after the email link. The API additionally rejects any candidate token without `email_verified=true`.
3. **MFA:** authenticator app (TOTP) per tenant policy OFF / OPTIONAL / REQUIRED (portal setting); SMS is never a factor.
   One-time **recovery codes** are available. With REQUIRED, every delivery call except the status call is refused
   (`403 mfa_required`, audited) until the candidate has an authenticator; the check uses the token's `amr` or the
   identity provider's credential state (only "enrolled" is cached, 60 s; an unreachable identity provider fails closed).
4. **Recovery never bypasses the second factor.** Keycloak's default reset flow *removes* the authenticator after an
   email link; the candidate realm replaces that step with "authenticator code or recovery code". Losing both goes to the
   institution: an audited staff action resets the second factor, signs out every session and emails the candidate.
5. **Recovery ends every session.** Keycloak only signs out other devices when the candidate ticks the box; the
   administrative process watches the realm's events (`RecoveryGuard`, every 10 s, one instance) and signs the candidate
   out everywhere when they did not, auditing `exam.candidate.recovered` in each organization. The identity provider
   emails the candidate about the password change.
6. **Candidates are not staff:** a candidate account (`candidate_account`) is per organization and linked on first
   visit to a portal (self-registration per portal), never a `user_account`, never holds permissions. Candidate actions
   are audited as `candidate:<subject>`.

### Session codes (C-1, Q-05 still open)

7. Expiring single-use codes: 10 characters from a 31-character alphabet without look-alikes (≈49 bits), shown once,
   stored as an HMAC keyed from the data key (a copied database does not allow offline guessing), scoped to tenant,
   session and time window, consumed by one atomic conditional update (100 concurrent attempts → exactly one wins).
   Attempts are limited per candidate (10/min), per address (300/min, configurable for campus NAT) and per code (5 failed
   attempts/min); every refusal is audited and every refusal returns the same message.
8. Compensation entitlements are codes **bound** to one candidate (nothing to type or send).

### Delivery process and browser boundary (C-4, P2-03, Q-07)

9. **Process role** (`IVOROVI_PROCESS_ROLE`): the same build runs as `ADMIN` or `DELIVERY`. A servlet filter before
   security returns a bare 404 for every path the role does not serve: the delivery process has no staff endpoint,
   the administrative API no delivery endpoint. Each process has its own pool and rate limits.
10. **Token boundary:** the delivery chain trusts only the candidate realm (issuer, keys, audience `ivorovi-delivery`,
    verified email); the staff chain trusts only the staff realm. A token from one is rejected by the other (tested both
    ways, also with forged combinations of issuer, key and audience).
11. **Tenant from server-side configuration:** the exam domain is shared; `/p/{portal}` identifies the organization,
    resolved by a security-definer lookup. A browser can never choose a tenant id; a disabled portal or suspended
    organization looks like an unknown portal.
12. **Exam application** (`frontend/apps/exam`): separate build, origin and OIDC client; no staff code (checked from the
    bundle's source maps in CI); own budget (≤ 120 kB initial gzip; 88 kB at 2.0). Strict headers: CSP without inline
    script or style, `connect-src` only self + candidate realm, `frame-ancestors 'none'`, no referrer, Permissions-Policy
    camera/fullscreen only, COOP/COEP/CORP, nosniff, DENY framing. CORS on the delivery API allows only the exam origin.
    Tokens in memory only; a non-extractable device key (ECDSA P-256) in IndexedDB, bound to attempt tokens from 2.5.

## Consequences

* Keycloak upgrades must re-verify the realm behaviour above (reset flow, email-first registration, events);
  the exam E2E suite exercises it against the real identity provider.
* Attempt-scoped tokens, device binding and the answer-save path arrive with delivery (2.5).
