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
                           Instant submittedAt, int extraMinutes, int questions, int answered, Instant lastSavedAt, int extendedMinutes, int interruptions,
                           long interruptedSeconds, boolean terminationPending, boolean extensionPending) {
        Progress withExtensionPending() {
            return new Progress(candidateId, checkedInAt, lastSeenAt, attemptStatus, startedAt, deadlineAt, submittedAt, extraMinutes, questions, answered,
                    lastSavedAt, extendedMinutes, interruptions, interruptedSeconds, terminationPending, true);
        }
    }

    private record Row(Progress progress, String attemptId) {}

    /** One extra-time grant (R-2): permanent; visible to the examination's invigilators and controllers. */
    public record ExtensionRecord(UUID id, int minutes, String reason, String grantedByName, String approvedByName, Instant grantedAt,
                                  Instant previousExpiry, Instant newExpiry) {}

    /** The designated permission for extra time during an examination (R-2). */
    public static final String EXTEND = com.ivorovi.exams.internal.AttemptOperations.EXTEND;

    private final ExamAdminService admin;
    private final CandidateService candidates;
    private final JdbcClient jdbc;
    private final Authz authz;
    private final com.ivorovi.exams.internal.AttemptOperations operations;

    private final com.ivorovi.approvals.ApprovalService approvals;

    ExamSessions(ExamAdminService admin, CandidateService candidates, JdbcClient jdbc, Authz authz, com.ivorovi.exams.internal.AttemptOperations operations,
                 @org.springframework.context.annotation.Lazy com.ivorovi.approvals.ApprovalService approvals) {
        this.operations = operations;
        this.approvals = approvals;
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
        List<Row> rows = jdbc.sql("""
                SELECT ca.id, ci.checked_in_at, p.last_seen_at, a.status, a.started_at, a.deadline_at, a.submitted_at, coalesce(a.extra_minutes, 0),
                       coalesce((SELECT jsonb_array_length(ap.items) FROM assessment_paper ap WHERE ap.id = a.paper_id), 0),
                       (SELECT count(*) FROM exam_answer x WHERE x.attempt_id = a.id AND jsonb_typeof(x.response -> 'value') IS DISTINCT FROM 'null'),
                       (SELECT max(x.saved_at) FROM exam_answer x WHERE x.attempt_id = a.id),
                       coalesce(a.extended_minutes, 0),
                       (SELECT count(*) FROM delivery_presence_gap g WHERE g.candidate_id = ca.id AND g.session_id = ?),
                       (SELECT coalesce(sum(extract(epoch FROM g.gap_end - g.gap_start)), 0)::bigint FROM delivery_presence_gap g WHERE g.candidate_id = ca.id AND g.session_id = ?),
                       EXISTS (SELECT 1 FROM exam_attempt_termination t WHERE t.attempt_id = a.id AND t.status = 'PENDING'),
                       a.id
                FROM candidate_account ca
                LEFT JOIN check_in ci ON ci.candidate_id = ca.id AND ci.session_id = ?
                LEFT JOIN delivery_presence p ON p.candidate_id = ca.id AND p.session_id = ?
                LEFT JOIN exam_attempt a ON a.candidate_id = ca.id AND a.schedule_id = ?
                WHERE ca.id = ANY (?)""").params(sessionId, sessionId, sessionId, sessionId, scheduleId, candidateIds.toArray(UUID[]::new))
                .query((rs, n) -> new Row(new Progress(rs.getObject(1, UUID.class), ts(rs.getTimestamp(2)), ts(rs.getTimestamp(3)), rs.getString(4),
                        ts(rs.getTimestamp(5)), ts(rs.getTimestamp(6)), ts(rs.getTimestamp(7)), rs.getInt(8), rs.getInt(9), rs.getInt(10), ts(rs.getTimestamp(11)),
                        rs.getInt(12), rs.getInt(13), rs.getLong(14), rs.getBoolean(15), false), rs.getString(16)))
                .list();
        java.util.Set<String> pending = approvals.pendingSubjects("exam.attempt.extend",
                rows.stream().map(Row::attemptId).filter(java.util.Objects::nonNull).toList());
        return rows.stream().map(r -> r.attemptId() == null || !pending.contains(r.attemptId()) ? r.progress() : r.progress().withExtensionPending()).toList();
    }

    /**
     * The result of an extension (R-2): APPLIED (the expiry moved from previousDeadline to deadlineAt) or PENDING_APPROVAL
     * (the examination requires a second person; nothing changed yet).
     */
    public record AttemptExtension(UUID attemptId, String status, int minutes, int extendedMinutes, Instant previousDeadline, Instant deadlineAt,
                                   UUID approvalId) {}

    /** Extra time for a running attempt by a designated holder of exam.attempt.extend (see AttemptOperations). */
    public AttemptExtension extend(UUID scheduleId, UUID orgUnitId, Instant windowCloses, boolean secondPerson, UUID candidateId, String candidateName,
                                   int minutes, String reason) {
        var e = operations.extend(scheduleId, orgUnitId, windowCloses, secondPerson, candidateId, candidateName, minutes, reason);
        return new AttemptExtension(e.attemptId(), e.status(), e.minutes(), e.extendedMinutes(), e.previousDeadline(), e.deadlineAt(), e.approvalId());
    }

    /** Every extra-time grant of one candidate in a schedule, oldest first, for its invigilators and controllers. */
    @Transactional(readOnly = true)
    public List<ExtensionRecord> extensions(UUID scheduleId, UUID orgUnitId, UUID candidateId) {
        if (!authz.hasInOrgUnit("exam.invigilate", orgUnitId) && !authz.hasInOrgUnit("exam.session.manage", orgUnitId)) {
            throw new AccessDeniedException("Missing permission exam.invigilate");
        }
        return jdbc.sql("""
                SELECT x.id, x.minutes, x.reason, g.display_name, ap.display_name, x.granted_at, x.previous_deadline, x.new_deadline
                FROM exam_attempt_extension x LEFT JOIN user_account g ON g.id = x.granted_by LEFT JOIN user_account ap ON ap.id = x.approved_by
                WHERE x.schedule_id = ? AND x.candidate_id = ? ORDER BY x.granted_at, x.id""").params(scheduleId, candidateId)
                .query((rs, n) -> new ExtensionRecord(rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        ts(rs.getTimestamp(6)), ts(rs.getTimestamp(7)), ts(rs.getTimestamp(8)))).list();
    }

    /** Day 5: an invigilator's request to end a running attempt (four-eyes; returns the approval request id). */
    public UUID requestTermination(UUID scheduleId, UUID orgUnitId, UUID candidateId, String candidateName, String reason) {
        return operations.requestTermination(scheduleId, orgUnitId, candidateId, candidateName, reason);
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
