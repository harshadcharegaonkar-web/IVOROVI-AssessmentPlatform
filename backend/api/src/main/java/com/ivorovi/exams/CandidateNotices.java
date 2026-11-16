package com.ivorovi.exams;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Provider interface (implemented by other modules, e.g. proctoring in the demo programme's add-on B): notices a person
 * addressed to the candidate of a running attempt, such as an invigilator's warning. Delivered with every attempt
 * heartbeat ({@code pulse}) so the candidate sees them within seconds. Notices carry only text a member of staff wrote
 * for the candidate; never flags, evidence or anything automated.
 */
public interface CandidateNotices {

    /** A notice for the candidate: its id (stable, so the client shows it once), the text and when it was issued. */
    record Notice(UUID id, String text, Instant issuedAt) {}

    /** Notices for this attempt, oldest first. Called in the attempt's tenant by the delivery process. */
    List<Notice> forAttempt(UUID attemptId);
}
