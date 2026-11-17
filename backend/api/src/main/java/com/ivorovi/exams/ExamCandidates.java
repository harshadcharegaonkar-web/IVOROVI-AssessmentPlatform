package com.ivorovi.exams;

import com.ivorovi.exams.internal.CandidateService;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Exams module API (demo programme S-8) for candidate-facing screens of other modules (the admissions applicant
 * portal): who the signed-in candidate is on a portal, with exactly the checks delivery applies (linked, active
 * account; session not ended; the portal's MFA policy satisfied). The candidate realm stays owned by this module.
 */
@Component
public class ExamCandidates {

    /** The signed-in candidate. {@code email} is the candidate's own verified address (for their own enrolment). */
    public record Candidate(UUID candidateId, UUID portalId, String portalKey, String email, String displayName) {}

    private final CandidateService candidates;

    ExamCandidates(CandidateService candidates) {
        this.candidates = candidates;
    }

    public Candidate current(String portalKey) {
        CandidateService.CandidateRef r = candidates.current(portalKey);
        return new Candidate(r.candidateId(), r.portalId(), r.portalKey(), r.email(), r.displayName());
    }

    /** Keyed index of an e-mail address in the current organization (no plaintext lookups exist). */
    public String emailIndex(String email) {
        return candidates.emailIndex(email);
    }
}
