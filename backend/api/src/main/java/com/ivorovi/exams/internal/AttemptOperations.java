package com.ivorovi.exams.internal;

import com.ivorovi.approvals.ApprovalHandler;
import com.ivorovi.approvals.ApprovalService;
import com.ivorovi.platform.audit.AuditLog;
import com.ivorovi.platform.outbox.Outbox;
import com.ivorovi.platform.ids.Ids;
import com.ivorovi.platform.security.Authz;
import com.ivorovi.platform.security.CurrentUser;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.platform.web.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Day 5 examination operations on a running attempt, with the permission separation of the operations workflow:
 * <ul>
 *   <li><b>Extension</b> (decision R-2): a designated holder of the privileged exam.attempt.extend (exam controllers and
 *       invigilators; administrators do not hold it automatically) adds 1–60 minutes to one candidate's running attempt with
 *       a reason. Each grant is a permanent row with the candidate, examination, actor, time, previous and new expiry; the
 *       deadline only moves forward by the recorded minutes (database guard), never beyond the window. Where the examination
 *       requires a second person (an institution's high-stakes setting), the grant is a four-eyes request decided by a
 *       different holder of exam.attempt.extend and applied only on approval ({@link ExtensionApprovals}).</li>
 *   <li><b>Termination</b> (four-eyes): an invigilator (exam.invigilate) requests it with a reason; a different person holding
 *       the privileged exam.attempt.terminate decides. On approval the attempt ends as TERMINATED; its answers are kept and
 *       it is scored like any other, so results still go through review and independent approval (R-1).</li>
 * </ul>
 * Audit details hold identifiers, minutes and status only; reasons are not copied into the audit trail.
 */
@Service
public class AttemptOperations implements ApprovalHandler {

    public static final String EXTEND = "exam.attempt.extend";
    static final String EXTENSION_APPROVAL = "exam.attempt.extend";
    public static final String REQUEST_TERMINATION = "exam.invigilate";
    public static final String TERMINATE = "exam.attempt.terminate";
    static final String APPROVAL_TYPE = "exam.attempt.terminate";

    /**
     * The result of an extension: APPLIED (the deadline moved from previousDeadline to deadlineAt) or PENDING_APPROVAL (a
     * second person must approve; nothing changed yet; approvalId identifies the request).
     */
    public record Extension(UUID attemptId, String status, int minutes, int extendedMinutes, Instant previousDeadline, Instant deadlineAt, UUID approvalId) {}

    private final JdbcClient jdbc;
    private final Authz authz;
    private final AuditLog audit;
    private final Outbox outbox;
    private final ApprovalService approvals;

    AttemptOperations(JdbcClient jdbc, Authz authz, AuditLog audit, Outbox outbox, @Lazy ApprovalService approvals) {
        this.jdbc = jdbc;
        this.authz = authz;
        this.audit = audit;
        this.outbox = outbox;
        this.approvals = approvals;
    }

    private record Open(UUID id, Instant deadline, int extended, String status) {}

    private Open attempt(UUID scheduleId, UUID candidateId, boolean lock) {
        return jdbc.sql("SELECT id, deadline_at, extended_minutes, status FROM exam_attempt WHERE schedule_id = ? AND candidate_id = ?" + (lock ? " FOR UPDATE" : ""))
                .params(scheduleId, candidateId).query((rs, n) -> new Open(rs.getObject(1, UUID.class), rs.getTimestamp(2).toInstant(), rs.getInt(3), rs.getString(4)))
                .optional().orElseThrow(() -> ApiException.notFound("Attempt"));
    }

    @Transactional
    public Extension extend(UUID scheduleId, UUID orgUnitId, Instant windowCloses, boolean secondPerson, UUID candidateId, String candidateName,
                            int minutes, String reason) {
        if (!authz.hasInOrgUnit(EXTEND, orgUnitId)) throw new AccessDeniedException("Missing permission " + EXTEND);
        if (minutes < 1 || minutes > 60) throw ApiException.badRequest("minutes_invalid", "Add between 1 and 60 minutes at a time.");
        String why = reason == null ? "" : reason.strip();
        if (why.isEmpty()) throw ApiException.badRequest("reason_required", "Give the reason for the extra time.");
        if (why.length() > 500) throw ApiException.badRequest("reason_too_long", "The reason may be at most 500 characters.");
        Open a = attempt(scheduleId, candidateId, true);
        requireExtendable(a, minutes, windowCloses);
        if (!secondPerson) return apply(a, scheduleId, candidateId, minutes, why, CurrentUser.require().userId(), null, null);
        UUID approval = approvals.request(EXTENSION_APPROVAL, "exam_attempt", a.id().toString(),
                "Add " + minutes + " minutes for " + (candidateName == null ? "a candidate" : candidateName) + ": " + (why.length() > 120 ? why.substring(0, 119) + "…" : why),
                Map.of("scheduleId", scheduleId.toString(), "attemptId", a.id().toString(), "candidateId", candidateId.toString(), "minutes", minutes,
                        "reason", why, "windowCloses", windowCloses.toString()), EXTEND, orgUnitId, Duration.ofMinutes(30), true);
        audit.recordSuccess("exam.attempt.extension_requested", "exam_attempt", a.id().toString(), Map.of("schedule", scheduleId.toString(),
                "candidate", candidateId.toString(), "minutes", String.valueOf(minutes), "approval", approval.toString()));
        return new Extension(a.id(), "PENDING_APPROVAL", minutes, a.extended(), a.deadline(), a.deadline(), approval);
    }

    private static void requireExtendable(Open a, int minutes, Instant windowCloses) {
        if (!"IN_PROGRESS".equals(a.status())) throw ApiException.conflict("attempt_not_running", "This candidate's examination has already ended.");
        if (a.deadline().plus(Duration.ofMinutes(minutes)).isAfter(windowCloses)) {
            throw ApiException.conflict("beyond_window", "The extra time would go past the end of the examination window ("
                    + Duration.between(a.deadline(), windowCloses).toMinutes() + " minutes are left).");
        }
    }

    /**
     * Records the grant (permanent row: candidate, examination, actor, previous and new expiry, approval if any) and moves
     * the deadline. The previous expiry is taken from the locked attempt row in the same statement, so the record always
     * matches the attempt (the database also checks it).
     */
    private Extension apply(Open a, UUID scheduleId, UUID candidateId, int minutes, String why, UUID grantedBy, UUID approvedBy, UUID approvalId) {
        UUID id = Ids.newId();
        jdbc.sql("""
                INSERT INTO exam_attempt_extension (id, tenant_id, attempt_id, schedule_id, candidate_id, minutes, reason, granted_by, approved_by, approval_id,
                                                    previous_deadline, new_deadline)
                SELECT ?, ?, a.id, a.schedule_id, a.candidate_id, ?, ?, ?, ?, ?, a.deadline_at, a.deadline_at + make_interval(mins => ?)
                FROM exam_attempt a WHERE a.id = ?""")
                .params(id, TenantContext.requireTenantId(), minutes, why, grantedBy, approvedBy, approvalId, minutes, a.id()).update();
        jdbc.sql("UPDATE exam_attempt SET deadline_at = deadline_at + make_interval(mins => ?), extended_minutes = extended_minutes + ?, version = version + 1 WHERE id = ?")
                .params(minutes, minutes, a.id()).update();
        Instant deadline = a.deadline().plus(Duration.ofMinutes(minutes));
        Map<String, String> d = new java.util.LinkedHashMap<>();
        d.put("schedule", scheduleId.toString());
        d.put("candidate", candidateId.toString());
        d.put("minutes", String.valueOf(minutes));
        d.put("previousExpiry", a.deadline().toString());
        d.put("newExpiry", deadline.toString());
        d.put("extendedMinutes", String.valueOf(a.extended() + minutes));
        d.put("grantedBy", grantedBy.toString());
        if (approvalId != null) {
            d.put("approvedBy", approvedBy.toString());
            d.put("approval", approvalId.toString());
        }
        audit.recordSuccess("exam.attempt.extended", "exam_attempt", a.id().toString(), d);
        return new Extension(a.id(), "APPLIED", minutes, a.extended() + minutes, a.deadline(), deadline, approvalId);
    }

    /** Applies an approved second-person extension (called by {@link ExtensionApprovals} in the deciding transaction). */
    Extension applyApproved(ApprovalService.Request request) {
        Map<String, Object> p = request.payload();
        UUID scheduleId = UUID.fromString(String.valueOf(p.get("scheduleId")));
        UUID candidateId = UUID.fromString(String.valueOf(p.get("candidateId")));
        int minutes = Integer.parseInt(String.valueOf(p.get("minutes")));
        Open a = attempt(scheduleId, candidateId, true);
        if (!a.id().toString().equals(String.valueOf(p.get("attemptId")))) throw new ApprovalHandler.Invalidated("The attempt changed.");
        try {
            requireExtendable(a, minutes, Instant.parse(String.valueOf(p.get("windowCloses"))));
        } catch (ApiException e) {
            throw new ApprovalHandler.Invalidated(e.getMessage());
        }
        return apply(a, scheduleId, candidateId, minutes, String.valueOf(p.get("reason")), request.requestedBy(), request.decidedBy(), request.id());
    }

    /** Running attempts of the given ids that are still running (an extension request is decided only while it is). */
    boolean running(String attemptId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM exam_attempt WHERE id = CAST(? AS uuid) AND status = 'IN_PROGRESS')").param(attemptId)
                .query(Boolean.class).single();
    }

    /** An invigilator asks for a candidate's examination to be ended; a different holder of exam.attempt.terminate decides. */
    @Transactional
    public UUID requestTermination(UUID scheduleId, UUID orgUnitId, UUID candidateId, String candidateName, String reason) {
        if (!authz.hasInOrgUnit(REQUEST_TERMINATION, orgUnitId)) throw new AccessDeniedException("Missing permission " + REQUEST_TERMINATION);
        String why = reason == null ? "" : reason.strip();
        if (why.isEmpty()) throw ApiException.badRequest("reason_required", "Give the reason for ending the examination.");
        if (why.length() > 1000) throw ApiException.badRequest("reason_too_long", "The reason may be at most 1,000 characters.");
        Open a = attempt(scheduleId, candidateId, true);
        if (!"IN_PROGRESS".equals(a.status())) throw ApiException.conflict("attempt_not_running", "This candidate's examination has already ended.");
        boolean pending = jdbc.sql("SELECT EXISTS (SELECT 1 FROM exam_attempt_termination WHERE attempt_id = ? AND status = 'PENDING')").param(a.id())
                .query(Boolean.class).single();
        if (pending) throw ApiException.conflict("termination_pending", "Ending this examination is already waiting for a decision.");
        UUID id = Ids.newId();
        jdbc.sql("INSERT INTO exam_attempt_termination (id, tenant_id, attempt_id, reason, requested_by) VALUES (?, ?, ?, ?, ?)")
                .params(id, TenantContext.requireTenantId(), a.id(), why, CurrentUser.require().userId()).update();
        UUID approval = approvals.request(APPROVAL_TYPE, "exam_attempt_termination", id.toString(),
                "End the examination of " + (candidateName == null ? "a candidate" : candidateName) + ": " + (why.length() > 120 ? why.substring(0, 119) + "…" : why),
                Map.of("scheduleId", scheduleId.toString(), "attemptId", a.id().toString()), TERMINATE, orgUnitId, Duration.ofHours(4), false);
        jdbc.sql("UPDATE exam_attempt_termination SET approval_id = ? WHERE id = ?").params(approval, id).update();
        audit.recordSuccess("exam.attempt.termination_requested", "exam_attempt", a.id().toString(), Map.of("schedule", scheduleId.toString(),
                "approval", approval.toString()));
        return approval;
    }

    // ================================================================================================ four-eyes handler

    @Override
    public String type() {
        return APPROVAL_TYPE;
    }

    @Override
    public String label() {
        return "End a candidate's examination";
    }

    /** Only while the attempt is still running and the request still pending. */
    @Override
    public boolean unchanged(ApprovalService.Request request) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM exam_attempt_termination t JOIN exam_attempt a ON a.id = t.attempt_id
                               WHERE t.id = CAST(? AS uuid) AND t.status = 'PENDING' AND a.status = 'IN_PROGRESS')""").param(request.subjectId())
                .query(Boolean.class).single();
    }

    /** Never the person who asked (also refused by the framework and the database). */
    @Override
    public boolean mayDecide(ApprovalService.Request request, UUID decider) {
        UUID requester = jdbc.sql("SELECT requested_by FROM exam_attempt_termination WHERE id = CAST(? AS uuid)").param(request.subjectId())
                .query(UUID.class).optional().orElse(null);
        return requester != null && !requester.equals(decider);
    }

    @Override
    public void onApproved(ApprovalService.Request request) {
        UUID id = UUID.fromString(request.subjectId());
        UUID attempt = jdbc.sql("SELECT attempt_id FROM exam_attempt_termination WHERE id = ?").param(id).query(UUID.class).single();
        // the request first: the database lets an attempt become TERMINATED only while an approved request exists (QA-D6-1)
        jdbc.sql("UPDATE exam_attempt_termination SET status = 'APPROVED', decided_by = ?, decided_at = now() WHERE id = ?").params(request.decidedBy(), id).update();
        int n = jdbc.sql("UPDATE exam_attempt SET status = 'TERMINATED', submitted_at = now(), version = version + 1 WHERE id = ? AND status = 'IN_PROGRESS'")
                .param(attempt).update();
        if (n == 0) throw new ApprovalHandler.Invalidated("The examination had already ended.");
        String schedule = String.valueOf(request.payload().get("scheduleId"));
        audit.recordSuccess("exam.attempt.terminated", "exam_attempt", attempt.toString(), Map.of("schedule", schedule, "approval", request.id().toString()));
        outbox.append("exam_attempt", attempt.toString(), "exam.attempt.submitted", Map.of("attemptId", attempt.toString(), "scheduleId", schedule, "by", "TERMINATED"));
    }

    @Override
    public void onRejected(ApprovalService.Request request) {
        close(request, "REJECTED");
    }

    @Override
    public void onClosed(ApprovalService.Request request) {
        close(request, "CLOSED");
    }

    private void close(ApprovalService.Request request, String status) {
        int n = jdbc.sql("UPDATE exam_attempt_termination SET status = ?, decided_at = now() WHERE id = CAST(? AS uuid) AND status = 'PENDING'")
                .params(status, request.subjectId()).update();
        if (n > 0) audit.recordSuccess("exam.attempt.termination_" + status.toLowerCase(java.util.Locale.ROOT), "exam_attempt_termination", request.subjectId(),
                Map.of("schedule", String.valueOf(request.payload().get("scheduleId"))));
    }
}
