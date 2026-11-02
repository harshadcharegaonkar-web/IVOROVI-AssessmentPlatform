package com.ivorovi.platform.security;

import java.util.Optional;
import java.util.UUID;

/**
 * Port implemented by the identity module. Called with the tenant context already bound, so lookups are
 * RLS-restricted to that tenant. Returns empty when the subject has no ACTIVE account in the tenant.
 */
public interface PrincipalResolver {

    Optional<IvoroviPrincipal> resolve(UUID tenantId, String subject);
}
