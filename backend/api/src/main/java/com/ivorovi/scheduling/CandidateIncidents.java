package com.ivorovi.scheduling;

import com.ivorovi.scheduling.internal.InvigilationService;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Module API for other modules that act on an invigilator's behalf (demo programme, add-on B: proctoring decisions).
 * A thin facade over the invigilation workflow: the same permissions (exam.invigilate at the schedule's institution),
 * validation, permanent records and audit apply as when an invigilator uses the monitor. Nothing is decided here:
 * a termination is only REQUESTED, and a different holder of exam.attempt.terminate decides it (four-eyes, ADR-0007).
 */
@Component
public class CandidateIncidents {

    private final InvigilationService invigilation;

    CandidateIncidents(InvigilationService invigilation) {
        this.invigilation = invigilation;
    }

    /** Records a permanent candidate incident (category as in the monitor, e.g. SUSPECTED_MALPRACTICE); returns its id. */
    public UUID record(UUID scheduleId, UUID enrolmentId, String category, String note) {
        return invigilation.record(scheduleId, new InvigilationService.IncidentInput(enrolmentId, category, note, null)).id();
    }

    /** Requests the four-eyes termination of the candidate's running attempt; returns the approval request id. */
    public UUID requestTermination(UUID scheduleId, UUID enrolmentId, String reason) {
        return invigilation.requestTermination(scheduleId, enrolmentId, reason);
    }
}
