package com.ivorovi.identity;

import java.util.Optional;
import java.util.UUID;

/**
 * Published identity operation for the students module: creates a sign-in account for a student on demand. The account
 * receives only the system STUDENT role (at the student's institution) and goes through the normal invitation flow;
 * accounts linked to students can never be given another role. Callers check {@code student.account.manage}.
 */
public interface StudentAccounts {

    record Account(UUID userId, String email, String status, String invitationStatus) {}

    /** Creates an INVITED account with the STUDENT role and sends the invitation after commit. Returns the user id. */
    UUID createInvited(String email, String givenName, String familyName, UUID institutionId);

    Optional<Account> find(UUID userId);

    /** Thrown when the email already belongs to an account of the organization. */
    class EmailInUseException extends RuntimeException {
        public EmailInUseException() { super("email_in_use"); }
    }
}
