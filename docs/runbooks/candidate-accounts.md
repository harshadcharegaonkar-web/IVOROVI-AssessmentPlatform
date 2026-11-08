# Runbook: candidate accounts, exam portals and session codes

Increment 2.0 (C-1, C-4). Candidates are **not** staff or students: they live in the separate Keycloak realm
`ivorovi-candidates` and get a `candidate_account` per organization when they first open that organization's portal.
Real candidates must not be processed until the production blockers are closed (C-7, `production-readiness.md`).

## Configuration

| Setting | Where | Notes |
|---|---|---|
| Candidate realm | `deploy/compose/keycloak/realm-ivorovi-candidates.json` (realm as code) | Import on a new environment; for changes on an existing one, apply the same change through the admin console and update the file. |
| `IVOROVI_DELIVERY_OIDC_ISSUER` / `_JWKS` | delivery and admin processes | Issuer and keys of the candidate realm. Required outside the dev profile. |
| `IVOROVI_EXAM_APP_ORIGINS` | delivery process | Exact exam app origin(s) for CORS. Empty means no cross-origin use (exam host proxies the API). |
| `IVOROVI_EXAM_APP_BASE_URL` | admin process | Link used in candidate emails and staff screens. |
| `IVOROVI_CANDIDATE_IDP_SECRET` | admin and delivery processes | Secret of `ivorovi-candidate-provisioner` (view/manage/query users and view events of the candidate realm only). From the secret store. |
| `IVOROVI_DELIVERY_CODE_ATTEMPTS_PER_IP` | delivery process | Session-code attempts per address per minute (default 300). Raise for very large halls behind one campus NAT address. |

The provisioner client must keep `view-events` (recovery guard) and must not get any staff-realm role.

## Everyday tasks (staff UI → Examinations)

* **Create a portal**: Exam Portals → New portal. The address (`…/p/<address>`) identifies the institution; MFA policy
  OFF / OPTIONAL / REQUIRED; self-registration open or closed. Needs `exam.session.manage` organization-wide.
* **Readiness session and codes**: Readiness Sessions → New session → Generate codes. Codes are shown **once** (copy or
  print them); afterwards only the last two characters are visible. A code works once, for one candidate, inside its
  window (default: 30 minutes before the start until the end). Revoke unused codes that leaked; cancelling a session
  revokes all its unused codes.
* **Check-ins**: the session page lists checked-in candidates with clock offset and last heartbeat (every 15 s).

## Candidate support

| Situation | What to do |
|---|---|
| Did not receive the verification email | Check the address; the candidate can request a new link on the sign-in page. Email delivery is through the configured relay. |
| Forgot password | Self-service "Forgot password": email link **and** authenticator code (or one recovery code). All sessions end afterwards (the platform enforces it within ~10 s and audits `exam.candidate.recovered`). |
| Lost phone, has recovery codes | Sign in with a recovery code ("Try another way"), then set up a new authenticator and new recovery codes. |
| Lost phone **and** recovery codes | Verify identity (institution procedure), then Candidates → *Reset sign-in factor* with a reason. Removes the authenticator and recovery codes, signs out every session, emails the candidate, audited `exam.candidate.mfa_reset`. |
| Locked out after failed sign-ins | Brute-force protection: the wait grows to at most 15 minutes and lifts automatically. |
| Account misuse | Candidates → Suspend (all delivery calls refused with `candidate_suspended`); Reactivate when resolved. |
| "This code is not valid now" | The message is the same for wrong, used, revoked, expired or not-yet-valid codes (no oracle). Check the window on the session page; issue a new code if needed. |
| "Too many attempts" | Per candidate 10/min, per code 5 failed/min, per address 300/min. Wait a minute. |

## Security notes

* Codes are stored as HMAC (keyed from the data key); rotating the data key invalidates all unused codes (generate new ones).
* Every refusal (wrong code, rate limit, MFA required) is audited with the candidate as actor (`candidate:<subject>`).
* Candidate tokens are not accepted by the staff API and staff tokens are not accepted by the delivery API.
