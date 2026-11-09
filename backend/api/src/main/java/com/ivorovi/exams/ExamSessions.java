package com.ivorovi.exams;

import com.ivorovi.exams.internal.CandidateService;
import com.ivorovi.exams.internal.ExamAdminService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Module API for scheduling (Increment 2.2 interface contract, owned by the exams module): exam sessions, single-use
 * session codes and check-ins stay here; scheduling creates one session per schedule and issues its codes through this
 * API. Every call is authorized inside the exams module (exam.session.manage on the session's institution); codes are
 * returned once and stored only as keyed hashes.
 */
@Component
public class ExamSessions {

    /** Public views of the exams module's own records (scheduling must not depend on exams internals). */
    public record Session(UUID id, UUID portalId, String portalName, UUID orgUnitId, String name, Instant startsAt, Instant endsAt, String status,
                          long codes, long codesUsed, long checkIns) {
        static Session of(ExamAdminService.Session s) {
            return new Session(s.id(), s.portalId(), s.portalName(), s.orgUnitId(), s.name(), s.startsAt(), s.endsAt(), s.status(), s.codes(), s.codesUsed(), s.checkIns());
        }
    }

    public record Portal(UUID id, String portalKey, String name, String status, String url) {}

    public record IssuedCode(UUID id, String code, String hint, Instant validFrom, Instant validUntil) {}

    public record Code(UUID id, String hint, Instant validFrom, Instant validUntil, String status, Instant consumedAt, String consumedByName) {}

    public record CheckIn(UUID candidateId, String displayName, Instant checkedInAt, Instant lastSeenAt) {}

    private final ExamAdminService admin;
    private final CandidateService candidates;
    private final JdbcClient jdbc;

    ExamSessions(ExamAdminService admin, CandidateService candidates, JdbcClient jdbc) {
        this.admin = admin;
        this.candidates = candidates;
        this.jdbc = jdbc;
    }

    public Session create(UUID portalId, UUID orgUnitId, String name, Instant startsAt, Instant endsAt) {
        return Session.of(admin.createSession(portalId, orgUnitId, name, startsAt, endsAt));
    }

    public Session session(UUID sessionId) {
        return Session.of(admin.session(sessionId));
    }

    public Session cancel(UUID sessionId) {
        return Session.of(admin.cancelSession(sessionId));
    }

    public List<Portal> portals() {
        return admin.portals().stream().map(p -> new Portal(p.id(), p.portalKey(), p.name(), p.status(), p.url())).toList();
    }

    /** New single-use codes; the plain codes are in this result only. */
    public List<IssuedCode> issueCodes(UUID sessionId, int count, Instant validFrom, Instant validUntil) {
        return admin.generateCodes(sessionId, count, validFrom, validUntil).stream()
                .map(c -> new IssuedCode(c.id(), c.code(), c.hint(), c.validFrom(), c.validUntil())).toList();
    }

    public List<Code> codes(UUID sessionId) {
        return admin.codes(sessionId).stream().map(c -> new Code(c.id(), c.hint(), c.validFrom(), c.validUntil(), c.status(), c.consumedAt(), c.consumedByName())).toList();
    }

    public void revokeCode(UUID sessionId, UUID codeId) {
        admin.revokeCode(sessionId, codeId);
    }

    public List<CheckIn> checkIns(UUID sessionId) {
        return admin.checkIns(sessionId).stream().map(c -> new CheckIn(c.candidateId(), c.displayName(), c.checkedInAt(), c.lastSeenAt())).toList();
    }

    /** Candidate accounts of this organization that signed in with this email on the portal (via the blind index). */
    @Transactional(readOnly = true)
    public Optional<UUID> candidateByEmail(UUID portalId, String email) {
        return jdbc.sql("SELECT id FROM candidate_account WHERE email_index = ? AND portal_id = ? AND status = 'ACTIVE' ORDER BY created_at LIMIT 1")
                .params(candidates.emailIndex(email), portalId).query(UUID.class).optional();
    }

    /** The same blind index for an invited email, so scheduling can store and match it without keeping plaintext lookups. */
    public String emailIndex(String email) {
        return candidates.emailIndex(email);
    }

    @Transactional(readOnly = true)
    public boolean checkedIn(UUID sessionId, UUID candidateAccountId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM check_in WHERE session_id = ? AND candidate_id = ?)").params(sessionId, candidateAccountId)
                .query(Boolean.class).single();
    }
}
