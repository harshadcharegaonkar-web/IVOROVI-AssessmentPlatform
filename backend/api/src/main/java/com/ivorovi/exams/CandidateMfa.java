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

    /**
     * A completed password reset at the identity provider (email link + second factor). {@code resetSessionId} is the
     * session the reset flow itself signed in (may be null when the provider does not report it).
     */
    record PasswordReset(String subject, java.time.Instant at, boolean otherSessionsSignedOut, String resetSessionId) {
        public PasswordReset(String subject, java.time.Instant at, boolean otherSessionsSignedOut) {
            this(subject, at, otherSessionsSignedOut, null);
        }
    }

    /** Password resets completed since {@code since} (from the candidate realm's event log). */
    default java.util.List<PasswordReset> passwordResetsSince(java.time.Instant since) {
        return java.util.List.of();
    }

    /** Signs out every session of the candidate at the identity provider. */
    default void signOutEverywhere(String subject) {}

    /**
     * KI-79: after a password reset, ends every session that existed before the reset ({@code start < resetAt}) but not the
     * session the reset flow just created ({@code keepSessionId}). The default falls back to ending every session, which
     * is never less secure.
     */
    default void signOutSessionsBefore(String subject, java.time.Instant resetAt, String keepSessionId) {
        signOutEverywhere(subject);
    }
}
