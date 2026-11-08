package com.ivorovi.exams;

/**
 * Second-factor state of a candidate at the candidate identity provider. The provider enforces an enrolled factor at every
 * sign-in (password + authenticator app, or a one-time recovery code), so "enrolled" means every session used it.
 */
public interface CandidateMfa {

    /** True when an authenticator app (TOTP) is enrolled for the candidate-realm subject. */
    boolean enrolled(String subject);

    /** Support reset after a lost device: removes the second factors and signs out every session of the candidate. */
    void reset(String subject);

    /** A completed password reset at the identity provider (email link + second factor). */
    record PasswordReset(String subject, java.time.Instant at, boolean otherSessionsSignedOut) {}

    /** Password resets completed since {@code since} (from the candidate realm's event log). */
    default java.util.List<PasswordReset> passwordResetsSince(java.time.Instant since) {
        return java.util.List.of();
    }

    /** Signs out every session of the candidate at the identity provider. */
    default void signOutEverywhere(String subject) {}
}
