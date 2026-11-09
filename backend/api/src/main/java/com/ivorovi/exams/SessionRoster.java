package com.ivorovi.exams;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Hook for scheduled enrolments (M2, Session A): the candidate accounts expected in a readiness/exam session. The exams
 * module uses it to size the check-in anomaly alarm (L4) and to list expected candidates who never checked in during an
 * incident as affected (KI-73). A module that owns enrolments provides a bean; without one, the expectation falls back to
 * the session codes issued for the session (a count, without identities).
 */
public interface SessionRoster {

    /**
     * Candidate account ids ({@code candidate_account.id}) enrolled in the session, or empty when this provider has no roster
     * for it. Called with the session's organization bound as tenant context; must never return another organization's
     * candidates.
     */
    Optional<Set<UUID>> expectedCandidates(UUID sessionId);
}
