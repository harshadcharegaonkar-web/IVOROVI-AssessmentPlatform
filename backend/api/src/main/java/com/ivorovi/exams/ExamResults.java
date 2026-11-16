package com.ivorovi.exams;

import com.ivorovi.exams.internal.CandidateService;
import com.ivorovi.platform.security.Authz;
import com.ivorovi.platform.web.ApiException;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exams module API (demo programme S-5): <b>released</b> results only, for admissions (entrance tests), placement
 * (aptitude and technical tests) and analytics (attainment). A score that is provisional, awaiting evaluation or
 * awaiting execution does not exist here. Never returns question content, answers, answer keys (ADR-0009), candidate
 * names or e-mail addresses: rows are keyed by the subject reference recorded through {@code scheduling.ExamScheduling},
 * or by a reference the caller supplies for e-mail addresses it already holds.
 *
 * <p>Authorization at the schedule's institution: {@code exam.results.read}, or the reading module's own permission
 * limited to its own subject type ({@code admission.read} → APPLICANT, {@code placement.read} → PLACEMENT_CANDIDATE,
 * {@code analytics.read} → STUDENT). Outside it the schedule is not found.
 */
@Component
public class ExamResults {

    public enum SubjectType { STUDENT, APPLICANT, PLACEMENT_CANDIDATE }

    /** One released attempt. {@code subjectRef} is the recorded subject or the caller's own reference. */
    public record Released(UUID scheduleId, SubjectType subjectType, UUID subjectRef, UUID attemptId, BigDecimal awarded, BigDecimal maxMarks, int correct,
                           int partial, int incorrect, int unanswered, Instant submittedAt) {}

    /** Per-question marks of a released attempt. {@code source}: AUTOMATIC, EVALUATION or EXECUTION. */
    public record Item(UUID attemptId, int position, UUID questionVersionId, String outcome, BigDecimal awarded, BigDecimal marks, String source) {}

    /** A schedule with released results the caller may read. */
    public record ReleasedSchedule(UUID scheduleId, String title, UUID orgUnitId, UUID assessmentVersionId, Instant closesAt, int released) {}

    static final String RESULTS_READ = "exam.results.read";
    static final Map<SubjectType, String> MODULE_READ = Map.of(SubjectType.APPLICANT, "admission.read", SubjectType.PLACEMENT_CANDIDATE, "placement.read",
            SubjectType.STUDENT, "analytics.read");
    static final int MAX_ATTEMPTS = 5000;

    private final JdbcClient jdbc;
    private final Authz authz;
    private final CandidateService candidates;

    ExamResults(JdbcClient jdbc, Authz authz, CandidateService candidates) {
        this.jdbc = jdbc;
        this.authz = authz;
        this.candidates = candidates;
    }

    private UUID requireReadable(UUID scheduleId, SubjectType type) {
        UUID orgUnit = jdbc.sql("SELECT org_unit_id FROM exam_schedule WHERE id = ?").param(scheduleId).query(UUID.class).optional()
                .orElseThrow(() -> ApiException.notFound("Results"));
        if (!authz.hasInOrgUnit(RESULTS_READ, orgUnit) && !authz.hasInOrgUnit(MODULE_READ.get(type), orgUnit)) throw ApiException.notFound("Results");
        return orgUnit;
    }

    private static final String RELEASED = """
            SELECT a.id AS attempt_id, a.submitted_at, sc.awarded, sc.max_marks, sc.correct, sc.partial, sc.incorrect, sc.unanswered%s
            FROM exam_attempt a JOIN exam_score sc ON sc.attempt_id = a.id AND sc.status = 'RELEASED'
            JOIN candidate_account c ON c.id = a.candidate_id
            JOIN exam_enrolment e ON e.schedule_id = a.schedule_id AND (e.candidate_id = a.candidate_id OR e.email_index = c.email_index)
            WHERE a.schedule_id = ? %s ORDER BY a.submitted_at, a.id LIMIT\s""" + MAX_ATTEMPTS;

    /** Released attempts of a schedule's enrolments recorded for {@code type}. */
    @Transactional(readOnly = true)
    public List<Released> released(UUID scheduleId, SubjectType type) {
        requireReadable(scheduleId, type);
        return jdbc.sql(RELEASED.formatted(", e.subject_ref", "AND e.subject_type = ?")).params(scheduleId, type.name())
                .query((rs, n) -> map(rs, scheduleId, type, rs.getObject("subject_ref", UUID.class))).list();
    }

    /**
     * Released attempts of the people whose e-mail addresses the caller already holds (for example students'
     * institutional e-mails), keyed by the caller's references. Addresses are compared through the keyed index only, and
     * only enrolments without a recorded subject or recorded for {@code type} are matched.
     */
    @Transactional(readOnly = true)
    public List<Released> releasedForEmails(UUID scheduleId, SubjectType type, Map<UUID, String> emailsByRef) {
        requireReadable(scheduleId, type);
        if (emailsByRef == null || emailsByRef.isEmpty()) return List.of();
        if (emailsByRef.size() > MAX_ATTEMPTS) throw ApiException.badRequest("too_many", "At most " + MAX_ATTEMPTS + " people at a time.");
        Map<String, UUID> byIndex = new LinkedHashMap<>();
        emailsByRef.forEach((ref, email) -> {
            if (email != null && !email.isBlank()) byIndex.put(candidates.emailIndex(email.strip().toLowerCase(java.util.Locale.ROOT)), ref);
        });
        if (byIndex.isEmpty()) return List.of();
        // enrolments recorded for another subject type (an applicant's entrance test, a placement test) are never matched by e-mail
        return jdbc.sql(RELEASED.formatted(", e.email_index", "AND e.email_index = ANY (?) AND (e.subject_type IS NULL OR e.subject_type = ?)"))
                .params(scheduleId, byIndex.keySet().toArray(String[]::new), type.name())
                .query((rs, n) -> map(rs, scheduleId, type, byIndex.get(rs.getString("email_index")))).list();
    }

    /** Per-question marks of released attempts of one schedule (outcome, marks, source; no content, answers or keys). */
    @Transactional(readOnly = true)
    public List<Item> releasedItems(UUID scheduleId, SubjectType type, Collection<UUID> attemptIds) {
        requireReadable(scheduleId, type);
        if (attemptIds == null || attemptIds.isEmpty()) return List.of();
        if (attemptIds.size() > MAX_ATTEMPTS) throw ApiException.badRequest("too_many", "At most " + MAX_ATTEMPTS + " attempts at a time.");
        return jdbc.sql("""
                SELECT s.attempt_id, s.position, s.question_version_id, s.outcome, s.awarded, s.marks, s.source
                FROM exam_answer_score s JOIN exam_score sc ON sc.attempt_id = s.attempt_id AND sc.status = 'RELEASED'
                JOIN exam_attempt a ON a.id = s.attempt_id AND a.schedule_id = ?
                WHERE s.attempt_id = ANY (?) ORDER BY s.attempt_id, s.position""").params(scheduleId, attemptIds.toArray(UUID[]::new))
                .query((rs, n) -> new Item(rs.getObject(1, UUID.class), rs.getInt(2), rs.getObject(3, UUID.class), rs.getString(4), rs.getBigDecimal(5),
                        rs.getBigDecimal(6), rs.getString(7))).list();
    }

    /**
     * Schedules with at least one released result in the units where the caller holds {@code exam.results.read} or the
     * module permission for {@code type}, optionally within one unit's subtree; newest first, at most 500.
     */
    @Transactional(readOnly = true)
    public List<ReleasedSchedule> releasedSchedules(SubjectType type, UUID withinOrgUnitId) {
        Set<String> permissions = Set.of(RESULTS_READ, MODULE_READ.get(type));
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (permissions.stream().noneMatch(authz::has)) {
            List<UUID> roots = new ArrayList<>();
            for (String p : permissions) roots.addAll(authz.orgUnitScopesWith(p));
            if (roots.isEmpty()) return List.of();
            where.append(" AND u.path <@ ANY (SELECT r.path FROM org_unit r WHERE r.id = ANY (?))");
            params.add(roots.toArray(UUID[]::new));
        }
        if (withinOrgUnitId != null) {
            where.append(" AND u.path <@ (SELECT path FROM org_unit WHERE id = ?)");
            params.add(withinOrgUnitId);
        }
        List<ReleasedSchedule> out = jdbc.sql("""
                SELECT s.id, s.title, s.org_unit_id, s.version_id, s.closes_at,
                       (SELECT count(*) FROM exam_attempt a JOIN exam_score sc ON sc.attempt_id = a.id AND sc.status = 'RELEASED' WHERE a.schedule_id = s.id)
                FROM exam_schedule s LEFT JOIN org_unit u ON u.id = s.org_unit_id""" + where + " ORDER BY s.closes_at DESC, s.id LIMIT 2000").params(params)
                .query((rs, n) -> new ReleasedSchedule(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class), rs.getObject(4, UUID.class),
                        rs.getTimestamp(5).toInstant(), rs.getInt(6))).list();
        return out.stream().filter(s -> s.released() > 0).limit(500).toList();
    }

    private static Released map(ResultSet rs, UUID scheduleId, SubjectType type, UUID ref) throws SQLException {
        java.sql.Timestamp submitted = rs.getTimestamp("submitted_at");
        return new Released(scheduleId, type, ref, rs.getObject("attempt_id", UUID.class), rs.getBigDecimal("awarded"), rs.getBigDecimal("max_marks"),
                rs.getInt("correct"), rs.getInt("partial"), rs.getInt("incorrect"), rs.getInt("unanswered"), submitted == null ? null : submitted.toInstant());
    }
}
