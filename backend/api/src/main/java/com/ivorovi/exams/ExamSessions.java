package com.ivorovi.exams;

import com.ivorovi.exams.internal.CandidateService;
import com.ivorovi.exams.internal.ExamAdminService;
import com.ivorovi.platform.security.Authz;
import org.springframework.security.access.AccessDeniedException;
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

    /**
     * Invigilation view of one candidate (Day 4): check-in, connection and attempt progress. Never answers, keys or scores:
     * only how many questions have a saved answer and when the last save happened.
     */
    public record Progress(UUID candidateId, Instant checkedInAt, Instant lastSeenAt, String attemptStatus, Instant startedAt, Instant deadlineAt,
                           Instant submittedAt, int extraMinutes, int questions, int answered, Instant lastSavedAt) {}

    private final ExamAdminService admin;
    private final CandidateService candidates;
    private final JdbcClient jdbc;
    private final Authz authz;

    ExamSessions(ExamAdminService admin, CandidateService candidates, JdbcClient jdbc, Authz authz) {
        this.admin = admin;
        this.candidates = candidates;
        this.jdbc = jdbc;
        this.authz = authz;
    }

    /**
     * Progress of the given candidates in a schedule's session and attempts, for invigilators (exam.invigilate) or session
     * managers of the schedule's institution; the caller passes the candidates it has enrolled.
     */
    @Transactional(readOnly = true)
    public List<Progress> progress(UUID scheduleId, UUID sessionId, UUID orgUnitId, java.util.Collection<UUID> candidateIds) {
        if (!authz.hasInOrgUnit("exam.invigilate", orgUnitId) && !authz.hasInOrgUnit("exam.session.manage", orgUnitId)) {
            throw new AccessDeniedException("Missing permission exam.invigilate");
        }
        if (candidateIds.isEmpty()) return List.of();
        return jdbc.sql("""
                SELECT ca.id, ci.checked_in_at, p.last_seen_at, a.status, a.started_at, a.deadline_at, a.submitted_at, coalesce(a.extra_minutes, 0),
                       coalesce((SELECT jsonb_array_length(ap.items) FROM assessment_paper ap WHERE ap.id = a.paper_id), 0),
                       (SELECT count(*) FROM exam_answer x WHERE x.attempt_id = a.id AND jsonb_typeof(x.response -> 'value') IS DISTINCT FROM 'null'),
                       (SELECT max(x.saved_at) FROM exam_answer x WHERE x.attempt_id = a.id)
                FROM candidate_account ca
                LEFT JOIN check_in ci ON ci.candidate_id = ca.id AND ci.session_id = ?
                LEFT JOIN delivery_presence p ON p.candidate_id = ca.id AND p.session_id = ?
                LEFT JOIN exam_attempt a ON a.candidate_id = ca.id AND a.schedule_id = ?
                WHERE ca.id = ANY (?)""").params(sessionId, sessionId, scheduleId, candidateIds.toArray(UUID[]::new))
                .query((rs, n) -> new Progress(rs.getObject(1, UUID.class), ts(rs.getTimestamp(2)), ts(rs.getTimestamp(3)), rs.getString(4),
                        ts(rs.getTimestamp(5)), ts(rs.getTimestamp(6)), ts(rs.getTimestamp(7)), rs.getInt(8), rs.getInt(9), rs.getInt(10), ts(rs.getTimestamp(11))))
                .list();
    }

    private static Instant ts(java.sql.Timestamp t) {
        return t == null ? null : t.toInstant();
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

    /** A candidate account's portal and keyed email index; empty when no active account. No personal data is returned. */
    public record CandidateIdentity(UUID id, UUID portalId, String emailIndex) {}

    @Transactional(readOnly = true)
    public Optional<CandidateIdentity> candidate(UUID candidateAccountId) {
        return jdbc.sql("SELECT id, portal_id, email_index FROM candidate_account WHERE id = ? AND status = 'ACTIVE'").param(candidateAccountId)
                .query((rs, n) -> new CandidateIdentity(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3))).optional();
    }

    @Transactional(readOnly = true)
    public boolean checkedIn(UUID sessionId, UUID candidateAccountId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM check_in WHERE session_id = ? AND candidate_id = ?)").params(sessionId, candidateAccountId)
                .query(Boolean.class).single();
    }
}
