package com.ivorovi.platform.security;

import org.springframework.security.core.AuthenticationException;

/** A cryptographically valid token that does not grant access to an active tenant account. Mapped to 403. */
public class TenantAccessDeniedException extends AuthenticationException {

    private final String reason;

    public TenantAccessDeniedException(String reason) {
        super("Access to tenant denied: " + reason);
        this.reason = reason;
    }

    public String reason() { return reason; }
}
