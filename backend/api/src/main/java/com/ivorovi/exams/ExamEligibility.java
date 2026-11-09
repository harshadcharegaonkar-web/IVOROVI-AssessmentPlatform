package com.ivorovi.exams;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service-provider interface implemented by the scheduling module (dependency inversion: delivery never imports
 * scheduling). It answers which scheduled examinations a candidate may take and on which terms. Delivery treats the
 * answer as authoritative and re-checks it at every start; an absent implementation means no examination is scheduled.
 */
public interface ExamEligibility {

    /**
     * One candidate's terms for one schedule.
     *
     * @param sessionId      exam session (exams module) whose check-in admits the candidate
     * @param lateEntryUntil last moment an attempt may start (null: until {@code closesAt})
     * @param extraMinutes   approved extra time for this candidate (0 when none)
     */
    record Eligibility(UUID scheduleId, String title, UUID assessmentVersionId, UUID sessionId, Instant opensAt, Instant closesAt,
                       Instant lateEntryUntil, int durationMinutes, int extraMinutes) {}

    /** Terms for one schedule if the candidate is enrolled in it and it is not cancelled. */
    Optional<Eligibility> forCandidate(UUID scheduleId, UUID candidateAccountId);

    /** Schedules the candidate is enrolled in that close in the future or closed in the last day. */
    List<Eligibility> upcoming(UUID candidateAccountId);
}
