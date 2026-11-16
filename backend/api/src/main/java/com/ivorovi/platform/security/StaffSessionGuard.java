package com.ivorovi.platform.security;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service-provider interface for organization security policies on staff sessions (mandatory module I): a second
 * factor at sign-in, an idle limit and an absolute session limit. Implemented by the platform administration module;
 * the platform's {@link StaffSessionPolicyFilter} only translates a refusal into a response. All organizations share one
 * staff identity realm, so these per-organization rules are enforced here, on every API call, from verified token claims.
 */
public interface StaffSessionGuard {

    enum Refusal {
        /** The organization requires a second factor and the token's {@code amr} has none. */
        MFA_REQUIRED,
        /** No API call in this session for longer than the idle limit; the session stays ended. */
        SESSION_IDLE,
        /** The session is older than the absolute limit. */
        SESSION_EXPIRED,
        /** A session limit applies but the token carries no session id ({@code sid}), so it cannot be enforced. */
        SESSION_UNTRACKED
    }

    /**
     * @param sessionId {@code sid} claim of the identity provider's session (may be null)
     * @param authTime  {@code auth_time} claim (may be null)
     * @param amr       authentication methods of the sign-in (empty when the provider reports none)
     */
    Optional<Refusal> check(UUID tenantId, UUID userId, String subject, String sessionId, Instant authTime, List<String> amr);
}
