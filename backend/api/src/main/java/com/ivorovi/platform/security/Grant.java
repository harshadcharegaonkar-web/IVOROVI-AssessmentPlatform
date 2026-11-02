package com.ivorovi.platform.security;

import java.util.UUID;

/** One permission granted to a user at one scope, derived from a role assignment. */
public record Grant(String permission, String roleCode, ScopeType scopeType, UUID scopeId) {}
