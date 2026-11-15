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
 *   <li><b>Extension</b> (exam.schedule.manage, an exam controller): minutes added to one candidate's running attempt with a
 *       reason; append-only record, the deadline only moves forward by the recorded minutes (database guard), never beyond
 *       the examination window. Invigilators cannot extend; they record an incident and ask a controller.</li>
 *   <li><b>Termination</b> (four-eyes): an invigilator (exam.invigilate) requests it with a reason; a different person holding
 *       the privileged exam.attempt.terminate decides. On approval the attempt ends as TERMINATED; its answers are kept and
 *       it is scored like any other, so results still go through review and independent approval (R-1).</li>
 * </ul>
 * Audit details hold identifiers, minutes and status only; reasons are not copied into the audit trail.
 */
@Service
public class AttemptOperations implements ApprovalHandler {

    public static final String EXTEND = "exam.schedule.manage";
    public static final String REQUEST_TERMINATION = "exam.invigilate";
    public static final String TERMINATE = "exam.attempt.terminate";
    static final String APPROVAL_TYPE = "exam.attempt.terminate";

    public record Extension(UUID attemptId, int minutes, int extendedMinutes, Instant deadlineAt) {}

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
    public Extension extend(UUID scheduleId, UUID orgUnitId, Instant windowCloses, UUID candidateId, int minutes, String reason) {
        if (!authz.hasInOrgUnit(EXTEND, orgUnitId)) throw new AccessDeniedException("Missing permission " + EXTEND);
        if (minutes < 1 || minutes > 60) throw ApiException.badRequest("minutes_invalid", "Add between 1 and 60 minutes at a time.");
        String why = reason == null ? "" : reason.strip();
        if (why.isEmpty()) throw ApiException.badRequest("reason_required", "Give the reason for the extra time.");
        if (why.length() > 500) throw ApiException.badRequest("reason_too_long", "The reason may be at most 500 characters.");
        Open a = attempt(scheduleId, candidateId, true);
        if (!"IN_PROGRESS".equals(a.status())) throw ApiException.conflict("attempt_not_running", "This candidate's examination has already ended.");
        Instant deadline = a.deadline().plus(Duration.ofMinutes(minutes));
        if (deadline.isAfter(windowCloses)) {
            throw ApiException.conflict("beyond_window", "The extra time would go past the end of the examination window ("
                    + Duration.between(a.deadline(), windowCloses).toMinutes() + " minutes are left).");
        }
        UUID id = Ids.newId();
        jdbc.sql("INSERT INTO exam_attempt_extension (id, tenant_id, attempt_id, minutes, reason, granted_by) VALUES (?, ?, ?, ?, ?, ?)")
                .params(id, TenantContext.requireTenantId(), a.id(), minutes, why, CurrentUser.require().userId()).update();
        jdbc.sql("UPDATE exam_attempt SET deadline_at = deadline_at + make_interval(mins => ?), extended_minutes = extended_minutes + ?, version = version + 1 WHERE id = ?")
                .params(minutes, minutes, a.id()).update();
        audit.recordSuccess("exam.attempt.extended", "exam_attempt", a.id().toString(), Map.of("schedule", scheduleId.toString(), "minutes", String.valueOf(minutes),
                "extendedMinutes", String.valueOf(a.extended() + minutes)));
        return new Extension(a.id(), minutes, a.extended() + minutes, deadline);
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
        int n = jdbc.sql("UPDATE exam_attempt SET status = 'TERMINATED', submitted_at = now(), version = version + 1 WHERE id = ? AND status = 'IN_PROGRESS'")
                .param(attempt).update();
        if (n == 0) throw new ApprovalHandler.Invalidated("The examination had already ended.");
        jdbc.sql("UPDATE exam_attempt_termination SET status = 'APPROVED', decided_by = ?, decided_at = now() WHERE id = ?").params(request.decidedBy(), id).update();
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
