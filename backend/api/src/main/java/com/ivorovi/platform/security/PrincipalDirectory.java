package com.ivorovi.platform.security;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The CURRENT principal (account status and valid grants) of a user, for authorization decisions made on that user's
 * behalf — e.g. delegated API clients (KI-59). Uses the same shared cache as interactive requests, which every API
 * instance evicts right after a role, assignment or account-status change commits; the account status itself is read
 * from the database on every call.
 */
@Component
public class PrincipalDirectory {

    private final PrincipalResolver resolver;
    private final PrincipalCache cache;

    PrincipalDirectory(PrincipalResolver resolver, PrincipalCache cache) {
        this.resolver = resolver;
        this.cache = cache;
    }

    /** Empty when the user is not ACTIVE in the bound tenant. Call with the tenant context bound. */
    public Optional<IvoroviPrincipal> activeUser(UUID tenantId, UUID userId) {
        return resolver.activeSubject(tenantId, userId).flatMap(subject -> cache.get(tenantId, subject)
                .or(() -> resolver.resolve(tenantId, subject).map(p -> { cache.put(p); return p; })));
    }
}
