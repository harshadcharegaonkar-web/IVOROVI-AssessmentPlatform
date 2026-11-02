package com.ivorovi.identity;

import java.util.Optional;
import java.util.UUID;

/**
 * Published port to the identity provider (Keycloak by default). Ivorovi never authenticates users itself: it creates the
 * login account once an invitation is accepted, and disables or re-enables it with the Ivorovi account status.
 */
public interface IdentityProvisioner {

    /** An identity-provider account; {@code tenantId} is the organization it signs in to (token claim {@code tid}). */
    record IdpUser(String id, String email, boolean enabled, UUID tenantId) {}

    record NewUser(String email, String givenName, String familyName, UUID tenantId, char[] password) {}

    Optional<IdpUser> findByEmail(String email);

    /** Creates an enabled account with a verified email and the given password. Returns the subject (user id). */
    String create(NewUser user);

    /** Enables or disables sign-in; disabling also ends every active session of the user. */
    void setEnabled(String subject, boolean enabled);

    /** Thrown for invalid input the provider rejects (e.g. a password that violates its policy). */
    class RejectedException extends RuntimeException {
        public RejectedException(String message) { super(message); }
    }

    /** Thrown when the provider cannot be reached or fails unexpectedly. */
    class UnavailableException extends RuntimeException {
        public UnavailableException(String message, Throwable cause) { super(message, cause); }
    }
}
