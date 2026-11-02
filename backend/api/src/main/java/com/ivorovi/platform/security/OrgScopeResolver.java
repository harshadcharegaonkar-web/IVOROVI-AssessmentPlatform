package com.ivorovi.platform.security;

import java.util.Optional;
import java.util.UUID;

/**
 * Port implemented by the tenancy module: the materialised hierarchy path (ltree text, e.g. {@code a.b.c}) of an
 * organization unit of the current tenant. Empty when the unit does not exist or belongs to another tenant.
 */
public interface OrgScopeResolver {

    Optional<String> pathOf(UUID orgUnitId);
}
