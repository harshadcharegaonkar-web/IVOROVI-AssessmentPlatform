package com.ivorovi.exams.internal;

import java.time.Instant;

/** Test access to package-private rules of {@link CandidateService}. */
public final class CandidateServiceTestAccess {

    private CandidateServiceTestAccess() {}

    public static boolean revoked(Instant issuedAt, String sid, Instant revokedBefore, String keptSession) {
        return CandidateService.revoked(issuedAt, sid, revokedBefore, keptSession);
    }
}
