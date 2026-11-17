package com.ivorovi.exams;

import com.ivorovi.exams.internal.AttemptService;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Module API for delivery-side modules (demo programme: proctoring, coding): which of the calling candidate's attempts an
 * id names. Resolution is the one every delivery call uses (candidate principal of the portal, linked account, ended
 * sessions, suspension, MFA policy), in the same guarded transaction. Empty for another candidate's attempt, an attempt
 * of another portal, or another organization's; other refusals (for example a suspended account) still throw.
 */
@Component
public class CandidateAttempts {

    /** The attempt: schedule, institution, status, current deadline and the candidate's enrolment in the schedule. */
    public record AttemptRef(UUID attemptId, UUID scheduleId, UUID orgUnitId, String status, Instant deadlineAt, UUID enrolmentId) {}

    private final AttemptService attempts;

    CandidateAttempts(AttemptService attempts) {
        this.attempts = attempts;
    }

    public Optional<AttemptRef> ownedBy(String portalKey, UUID attemptId) {
        return attempts.owned(portalKey, attemptId).map(o -> new AttemptRef(o.attemptId(), o.scheduleId(), o.orgUnitId(), o.status(), o.deadlineAt(), o.enrolmentId()));
    }
}
