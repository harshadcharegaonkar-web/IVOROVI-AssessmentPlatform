package com.ivorovi.exams.internal;

import com.ivorovi.approvals.ApprovalHandler;
import com.ivorovi.approvals.ApprovalService;
import com.ivorovi.platform.audit.AuditLog;
import com.ivorovi.platform.crypto.FieldCipher;
import com.ivorovi.platform.ids.Ids;
import com.ivorovi.platform.outbox.Outbox;
import com.ivorovi.platform.security.Authz;
import com.ivorovi.platform.security.CurrentUser;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.platform.web.ApiException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * M4 result workflow (decision D-10). Provisional scores are reviewed by an authorized person (exam.results.review), who
 * requests publication; an independent second person holding exam.results.approve approves it through the four-eyes
 * framework (never the reviewer: the framework refuses self-decisions and the database refuses a review approved by its
 * reviewer). Approval releases exactly the reviewed scores; candidates see only released results. Every step is audited.
 */
@Service
public class ResultService implements ApprovalHandler {

    public static final String READ = "exam.results.read";
    public static final String REVIEW = "exam.results.review";
    public static final String APPROVE = "exam.results.approve";
    static final String APPROVAL_TYPE = "exam.results.release";

    @Schema(name = "ExamResultRow")
    public record Row(UUID attemptId, UUID candidateId, String candidateName, String candidateEmail, String attemptStatus, Instant startedAt, Instant submittedAt,
                      BigDecimal awarded, BigDecimal maxMarks, Integer correct, Integer incorrect, Integer unanswered, String scoreStatus) {}

    @Schema(name = "ExamResultReview")
    public record Review(UUID id, String status, String reviewerName, String approverName, int attempts, String comment, UUID approvalId, Instant createdAt,
                         Instant decidedAt) {}

    @Schema(name = "ExamResults")
    public record Results(UUID scheduleId, int inProgress, int awaitingScore, int provisional, int released, List<Row> rows, List<Review> reviews,
                          boolean canReview) {}

    @Schema(name = "CandidateResult")
    public record CandidateResult(UUID attemptId, String title, BigDecimal awarded, BigDecimal maxMarks, int correct, int partial, int incorrect, int unanswered,
                                  Instant submittedAt) {}

    private final JdbcClient jdbc;
    private final Authz authz;
    private final AuditLog audit;
    private final Outbox outbox;
    private final FieldCipher cipher;
    private final ApprovalService approvals;
    private final CandidateService candidates;

    ResultService(JdbcClient jdbc, Authz authz, AuditLog audit, Outbox outbox, FieldCipher cipher, @Lazy ApprovalService approvals, CandidateService candidates) {
        this.jdbc = jdbc;
        this.authz = authz;
        this.audit = audit;
        this.outbox = outbox;
        this.cipher = cipher;
        this.approvals = approvals;
        this.candidates = candidates;
    }

    record Scope(UUID sessionId, UUID orgUnitId) {}

    /** The schedule's exam session and institution, from its attempts' session (404 when the caller may not read results). */
    private Scope scope(UUID scheduleId, String permission) {
        Scope s = jdbc.sql("""
                SELECT a.session_id, rs.org_unit_id FROM exam_attempt a JOIN readiness_session rs ON rs.id = a.session_id
                WHERE a.schedule_id = ? LIMIT 1""").param(scheduleId)
                .query((rs, n) -> new Scope(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class))).optional().orElse(null);
        if (s == null || !authz.hasInOrgUnit(READ, s.orgUnitId())) throw ApiException.notFound("Results");
        if (!READ.equals(permission) && !authz.hasInOrgUnit(permission, s.orgUnitId())) throw new AccessDeniedException("Missing permission " + permission);
        return s;
    }

    // ================================================================================================ staff view

    @Transactional(readOnly = true)
    public Results results(UUID scheduleId) {
        Scope s = scope(scheduleId, READ);
        UUID tenant = TenantContext.requireTenantId();
        List<Row> rows = jdbc.sql("""
                SELECT a.id, a.candidate_id, c.display_name, c.email_enc, a.status, a.started_at, a.submitted_at,
                       sc.awarded, sc.max_marks, sc.correct, sc.incorrect, sc.unanswered, sc.status AS score_status
                FROM exam_attempt a JOIN candidate_account c ON c.id = a.candidate_id LEFT JOIN exam_score sc ON sc.attempt_id = a.id
                WHERE a.schedule_id = ? ORDER BY c.display_name NULLS LAST, a.started_at""").param(scheduleId)
                .query((rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        cipher.decrypt(rs.getString(4), ExamAdminService.EMAIL_FIELD, tenant), rs.getString(5), ts(rs, "started_at"), ts(rs, "submitted_at"),
                        rs.getBigDecimal(8), rs.getBigDecimal(9), (Integer) rs.getObject(10), (Integer) rs.getObject(11), (Integer) rs.getObject(12),
                        rs.getString(13))).list();
        int inProgress = (int) rows.stream().filter(r -> "IN_PROGRESS".equals(r.attemptStatus())).count();
        int awaiting = (int) rows.stream().filter(r -> !"IN_PROGRESS".equals(r.attemptStatus()) && r.scoreStatus() == null).count();
        int provisional = (int) rows.stream().filter(r -> "PROVISIONAL".equals(r.scoreStatus())).count();
        int released = (int) rows.stream().filter(r -> "RELEASED".equals(r.scoreStatus())).count();
        List<Review> reviews = jdbc.sql("""
                SELECT r.id, r.status, ru.display_name, au.display_name, cardinality(r.attempt_ids), r.comment, r.approval_id, r.created_at, r.decided_at
                FROM exam_result_review r LEFT JOIN user_account ru ON ru.id = r.reviewer_id LEFT JOIN user_account au ON au.id = r.approved_by
                WHERE r.schedule_id = ? ORDER BY r.created_at DESC""").param(scheduleId)
                .query((rs, n) -> new Review(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5), rs.getString(6),
                        rs.getObject(7, UUID.class), ts(rs, "created_at"), ts(rs, "decided_at"))).list();
        boolean pending = reviews.stream().anyMatch(r -> "PENDING_APPROVAL".equals(r.status()));
        boolean canReview = provisional > 0 && !pending && authz.hasInOrgUnit(REVIEW, s.orgUnitId());
        return new Results(scheduleId, inProgress, awaiting, provisional, released, rows, reviews, canReview);
    }

    // ================================================================================================ review → approval

    /** Reviews every provisional score of the schedule and requests their publication from an independent approver. */
    @Transactional
    public Results review(UUID scheduleId, String comment) {
        Scope s = scope(scheduleId, REVIEW);
        List<Object[]> scores = jdbc.sql("""
                SELECT sc.attempt_id, sc.score_hash FROM exam_score sc JOIN exam_attempt a ON a.id = sc.attempt_id
                WHERE a.schedule_id = ? AND sc.status = 'PROVISIONAL' ORDER BY sc.attempt_id""").param(scheduleId)
                .query((rs, n) -> new Object[] {rs.getObject(1, UUID.class), rs.getString(2)}).list();
        if (scores.isEmpty()) throw ApiException.conflict("nothing_to_review", "There are no provisional results to review.");
        UUID[] ids = scores.stream().map(o -> (UUID) o[0]).toArray(UUID[]::new);
        String hash = hash(scores);
        UUID me = CurrentUser.require().userId();
        UUID id = Ids.newId();
        String text = comment == null || comment.isBlank() ? null : comment.strip();
        if (text != null && text.length() > 2000) throw ApiException.badRequest("too_long", "Comment: at most 2,000 characters.");
        try {
            jdbc.sql("""
                    INSERT INTO exam_result_review (id, tenant_id, schedule_id, session_id, org_unit_id, reviewer_id, comment, attempt_ids, scores_hash)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(id, TenantContext.requireTenantId(), scheduleId, s.sessionId(), s.orgUnitId(), me, text, ids, hash).update();
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw ApiException.conflict("review_pending", "A review of these results is already waiting for approval.");
        }
        String title = jdbc.sql("SELECT name FROM readiness_session WHERE id = ?").param(s.sessionId()).query(String.class).single();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scheduleId", scheduleId.toString());
        payload.put("reviewId", id.toString());
        payload.put("results", ids.length);
        payload.put("scoresHash", hash);
        UUID approval = approvals.request(APPROVAL_TYPE, "exam_result_review", id.toString(), "Publish " + ids.length + " result(s) of " + title, payload, APPROVE,
                s.orgUnitId(), Duration.ofDays(7), false);
        jdbc.sql("UPDATE exam_result_review SET approval_id = ? WHERE id = ?").params(approval, id).update();
        audit.recordSuccess("exam.results.reviewed", "exam_result_review", id.toString(), Map.of("schedule", scheduleId.toString(),
                "results", String.valueOf(ids.length), "scoresHash", hash, "approval", approval.toString()));
        return results(scheduleId);
    }

    @Override
    public String type() {
        return APPROVAL_TYPE;
    }

    @Override
    public String label() {
        return "Result publication";
    }

    /** Approval is refused when the reviewed scores are no longer exactly those reviewed (or already released). */
    @Override
    public boolean unchanged(ApprovalService.Request request) {
        record R(UUID[] ids, String hash, String status) {}
        R r = jdbc.sql("SELECT attempt_ids, scores_hash, status FROM exam_result_review WHERE id = CAST(? AS uuid)").param(request.subjectId())
                .query((rs, n) -> new R((UUID[]) rs.getArray(1).getArray(), rs.getString(2), rs.getString(3))).optional().orElse(null);
        if (r == null || !"PENDING_APPROVAL".equals(r.status())) return false;
        List<Object[]> now = jdbc.sql("SELECT attempt_id, score_hash FROM exam_score WHERE attempt_id = ANY (?) AND status = 'PROVISIONAL' ORDER BY attempt_id")
                .param(r.ids()).query((rs, n) -> new Object[] {rs.getObject(1, UUID.class), rs.getString(2)}).list();
        return now.size() == r.ids().length && hash(now).equals(r.hash());
    }

    /** The reviewer may never approve (also refused by the framework and the database). */
    @Override
    public boolean mayDecide(ApprovalService.Request request, UUID decider) {
        UUID reviewer = jdbc.sql("SELECT reviewer_id FROM exam_result_review WHERE id = CAST(? AS uuid)").param(request.subjectId()).query(UUID.class).optional().orElse(null);
        return reviewer != null && !reviewer.equals(decider);
    }

    @Override
    public void onApproved(ApprovalService.Request request) {
        UUID id = UUID.fromString(request.subjectId());
        UUID[] ids = jdbc.sql("SELECT attempt_ids FROM exam_result_review WHERE id = ? FOR UPDATE").param(id)
                .query((rs, n) -> (UUID[]) rs.getArray(1).getArray()).single();
        jdbc.sql("UPDATE exam_result_review SET status = 'PUBLISHED', approved_by = ?, decided_at = now() WHERE id = ?").params(request.decidedBy(), id).update();
        int n = jdbc.sql("UPDATE exam_score SET status = 'RELEASED' WHERE attempt_id = ANY (?) AND status = 'PROVISIONAL'").param(ids).update();
        if (n != ids.length) throw new ApprovalHandler.Invalidated("The reviewed results changed before approval; review them again.");
        String schedule = String.valueOf(request.payload().get("scheduleId"));
        audit.recordSuccess("exam.results.published", "exam_result_review", id.toString(), Map.of("schedule", schedule, "results", String.valueOf(n)));
        outbox.append("exam_result_review", id.toString(), "exam.results.published", Map.of("scheduleId", schedule, "results", String.valueOf(n)));
    }

    @Override
    public void onRejected(ApprovalService.Request request) {
        jdbc.sql("UPDATE exam_result_review SET status = 'REJECTED', decided_at = now() WHERE id = CAST(? AS uuid) AND status = 'PENDING_APPROVAL'")
                .param(request.subjectId()).update();
        audit.recordSuccess("exam.results.publication_rejected", "exam_result_review", request.subjectId(), Map.of("schedule", String.valueOf(request.payload().get("scheduleId"))));
    }

    @Override
    public void onClosed(ApprovalService.Request request) {
        jdbc.sql("UPDATE exam_result_review SET status = 'CLOSED', decided_at = now() WHERE id = CAST(? AS uuid) AND status = 'PENDING_APPROVAL'")
                .param(request.subjectId()).update();
    }

    // ================================================================================================ candidate view

    /** The candidate's own result, only once published (404 for another candidate's attempt; 409 before publication). */
    public CandidateResult candidateResult(String portalKey, UUID attemptId) {
        return candidates.runGuarded(() -> {
            UUID me = candidates.requireCandidate(candidates.portal(portalKey));
            record A(UUID candidate, UUID version, Instant submitted) {}
            A a = jdbc.sql("SELECT candidate_id, version_id, submitted_at FROM exam_attempt WHERE id = ?").param(attemptId)
                    .query((rs, n) -> new A(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), ts(rs, "submitted_at"))).optional()
                    .filter(x -> x.candidate().equals(me)).orElseThrow(() -> ApiException.notFound("Attempt"));
            return jdbc.sql("""
                    SELECT sc.awarded, sc.max_marks, sc.correct, sc.partial, sc.incorrect, sc.unanswered, v.title FROM exam_score sc
                    JOIN assessment_version v ON v.id = ? WHERE sc.attempt_id = ? AND sc.status = 'RELEASED'""").params(a.version(), attemptId)
                    .query((rs, n) -> new CandidateResult(attemptId, rs.getString(7), rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getInt(3), rs.getInt(4),
                            rs.getInt(5), rs.getInt(6), a.submitted())).optional()
                    .orElseThrow(() -> ApiException.conflict("result_not_published", "Your result has not been published yet."));
        });
    }

    // ================================================================================================ helpers

    static String hash(List<Object[]> scores) {
        StringBuilder sb = new StringBuilder("ivorovi.results.v1");
        for (Object[] s : scores) sb.append('|').append(s[0]).append(':').append(s[1]);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Instant ts(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        var t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }
}
