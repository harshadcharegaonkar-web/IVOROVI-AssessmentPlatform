package com.ivorovi.exams.internal;

import com.ivorovi.assessments.PublishedAssessments;
import com.ivorovi.exams.ExamEligibility;
import com.ivorovi.platform.audit.AuditLog;
import com.ivorovi.platform.ids.Ids;
import com.ivorovi.platform.jobs.JobLeases;
import com.ivorovi.platform.outbox.Outbox;
import com.ivorovi.platform.process.ProcessProperties;
import com.ivorovi.platform.process.ProcessRole;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.platform.web.ApiException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * M3 delivery: a candidate's attempt at a scheduled examination.
 * <ul>
 *   <li>Start (or resume) only while the candidate is eligible (scheduling's {@link ExamEligibility}), inside the window,
 *       before late entry closes, and checked in to the schedule's exam session. One attempt per candidate and schedule.</li>
 *   <li>The attempt is bound to the exact published version and to a paper generated for it; the deadline is fixed at
 *       start: duration plus approved extra time, never beyond the window's close.</li>
 *   <li>Answers are saved per paper position with the client's sequence number; a save is acknowledged only once
 *       durable, a repeated or older save is a no-op, and every accepted save is journalled.</li>
 *   <li>Submission is idempotent (same result for a retried request); attempts past their deadline are submitted
 *       automatically. The database enforces all of this as well.</li>
 * </ul>
 */
@Service
public class AttemptService {

    private static final Logger log = LoggerFactory.getLogger(AttemptService.class);
    static final Duration GRACE = Duration.ofSeconds(30);
    static final int MAX_RESPONSE_CHARS = 20_000;

    @Schema(name = "CandidateExam")
    public record ExamView(UUID scheduleId, String title, Instant opensAt, Instant closesAt, Instant lateEntryUntil, int durationMinutes, int extraMinutes,
                           boolean checkedIn, String attemptStatus, UUID attemptId, String availability) {}

    @Schema(name = "CandidateAnswer")
    public record Answer(int position, Map<String, Object> response, long seq, Instant savedAt) {}

    @Schema(name = "CandidateAttempt")
    public record AttemptView(UUID id, UUID scheduleId, String title, String status, Instant startedAt, Instant deadlineAt, int extraMinutes,
                              Instant serverTime, Instant submittedAt, List<PublishedAssessments.CandidateItem> items, List<Answer> answers) {}

    @Schema(name = "AnswerAck")
    public record AnswerAck(int position, long seq, boolean applied, Instant savedAt, Instant serverTime) {}

    @Schema(name = "SubmitResult")
    public record SubmitResult(UUID attemptId, String status, Instant submittedAt, int answered, int questions) {}

    record Attempt(UUID id, UUID scheduleId, UUID candidateId, UUID sessionId, UUID versionId, UUID paperId, String status, Instant startedAt,
                   Instant deadlineAt, int extraMinutes, Instant submittedAt, String submitKey) {}

    private final JdbcClient jdbc;
    private final CandidateService candidates;
    private final List<ExamEligibility> eligibility;
    private final PublishedAssessments assessments;
    private final AuditLog audit;
    private final Outbox outbox;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final JobLeases leases;
    private final ProcessProperties process;

    AttemptService(JdbcClient jdbc, CandidateService candidates, List<ExamEligibility> eligibility, PublishedAssessments assessments, AuditLog audit,
                   Outbox outbox, TransactionTemplate tx, JsonMapper json, JobLeases leases, ProcessProperties process) {
        this.jdbc = jdbc;
        this.candidates = candidates;
        this.eligibility = eligibility;
        this.assessments = assessments;
        this.audit = audit;
        this.outbox = outbox;
        this.tx = tx;
        this.json = json;
        this.leases = leases;
        this.process = process;
    }

    private Optional<ExamEligibility.Eligibility> eligible(UUID scheduleId, UUID candidate) {
        for (ExamEligibility e : eligibility) {
            Optional<ExamEligibility.Eligibility> r = e.forCandidate(scheduleId, candidate);
            if (r.isPresent()) return r;
        }
        return Optional.empty();
    }

    // ================================================================================================ my examinations

    public List<ExamView> exams(String portalKey) {
        return tx.execute(s -> {
            CandidateService.Portal p = candidates.portal(portalKey);
            UUID me = candidates.requireCandidate(p);
            List<ExamView> out = new ArrayList<>();
            Instant now = Instant.now();
            for (ExamEligibility e : eligibility) {
                for (ExamEligibility.Eligibility x : e.upcoming(me)) {
                    Optional<Attempt> a = attemptFor(x.scheduleId(), me);
                    boolean checkedIn = jdbc.sql("SELECT EXISTS (SELECT 1 FROM check_in WHERE session_id = ? AND candidate_id = ?)").params(x.sessionId(), me)
                            .query(Boolean.class).single();
                    out.add(new ExamView(x.scheduleId(), x.title(), x.opensAt(), x.closesAt(), x.lateEntryUntil(), x.durationMinutes(), x.extraMinutes(), checkedIn,
                            a.map(Attempt::status).orElse(null), a.map(Attempt::id).orElse(null), availability(x, a.orElse(null), checkedIn, now)));
                }
            }
            out.sort(java.util.Comparator.comparing(ExamView::opensAt));
            return out;
        });
    }

    static String availability(ExamEligibility.Eligibility x, Attempt a, boolean checkedIn, Instant now) {
        if (a != null) return "IN_PROGRESS".equals(a.status()) && now.isBefore(a.deadlineAt()) ? "RESUME" : "FINISHED";
        if (now.isBefore(x.opensAt())) return "NOT_OPEN";
        Instant lastStart = x.lateEntryUntil() != null && x.lateEntryUntil().isBefore(x.closesAt()) ? x.lateEntryUntil() : x.closesAt();
        if (!now.isBefore(lastStart)) return "CLOSED";
        return checkedIn ? "START" : "CHECK_IN_REQUIRED";
    }

    // ================================================================================================ start / resume

    public AttemptView start(String portalKey, UUID scheduleId) {
        return candidates.runGuarded(() -> {
            CandidateService.Portal p = candidates.portal(portalKey);
            UUID me = candidates.requireCandidate(p);
            // concurrent starts by the same candidate (double click, two tabs) are serialized: exactly one attempt and one paper
            jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))").param("exam-attempt:" + scheduleId + ":" + me).query((rs, n) -> 0).list();
            Optional<Attempt> existing = attemptFor(scheduleId, me);
            if (existing.isPresent()) return view(existing.get());           // resume (or show a finished attempt)
            ExamEligibility.Eligibility x = eligible(scheduleId, me)
                    .orElseThrow(() -> ApiException.notFound("Examination"));
            Instant now = Instant.now();
            String why = availability(x, null, checked(x.sessionId(), me), now);
            switch (why) {
                case "NOT_OPEN" -> throw ApiException.conflict("not_open", "This examination has not opened yet.");
                case "CLOSED" -> {
                    audit.recordDenied("exam.attempt.start_refused", Map.of("schedule", scheduleId.toString(), "reason", "closed"));
                    throw ApiException.conflict("closed", "Entry to this examination has closed.");
                }
                case "CHECK_IN_REQUIRED" -> throw ApiException.conflict("not_checked_in", "Check in with your session code first.");
                default -> { }
            }
            Instant byDuration = now.plus(Duration.ofMinutes((long) x.durationMinutes() + x.extraMinutes()));
            Instant deadline = byDuration.isAfter(x.closesAt()) ? x.closesAt() : byDuration;
            UUID id = Ids.newId();
            UUID paper = assessments.paperForAttempt(x.assessmentVersionId(), "attempt:" + id);
            jdbc.sql("""
                    INSERT INTO exam_attempt (id, tenant_id, schedule_id, candidate_id, session_id, version_id, paper_id, started_at, deadline_at, extra_minutes)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(id, TenantContext.requireTenantId(), scheduleId, me, x.sessionId(), x.assessmentVersionId(), paper, Timestamp.from(now),
                            Timestamp.from(deadline), x.extraMinutes()).update();
            assessments.reference(x.assessmentVersionId(), "exam_attempt", id.toString());
            audit.recordSuccess("exam.attempt.started", "exam_attempt", id.toString(), Map.of("schedule", scheduleId.toString(),
                    "deadline", deadline.toString(), "extraMinutes", String.valueOf(x.extraMinutes())));
            return view(attempt(id));
        });
    }

    private boolean checked(UUID sessionId, UUID candidate) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM check_in WHERE session_id = ? AND candidate_id = ?)").params(sessionId, candidate).query(Boolean.class).single();
    }

    public AttemptView get(String portalKey, UUID attemptId) {
        return tx.execute(s -> view(mine(portalKey, attemptId)));
    }

    /** KI-83: an image of a question in the caller's own open attempt (404 for anything else, including after submission). */
    public com.ivorovi.questions.ApprovedQuestions.Image image(String portalKey, UUID attemptId, UUID assetId) {
        return tx.execute(s -> {
            Attempt a = mine(portalKey, attemptId);
            if (!"IN_PROGRESS".equals(a.status())) throw ApiException.notFound("Image");
            return assessments.paperImage(a.paperId(), assetId).orElseThrow(() -> ApiException.notFound("Image"));
        });
    }

    // ================================================================================================ answers

    @SuppressWarnings("unchecked")
    public AnswerAck save(String portalKey, UUID attemptId, int position, long seq, Map<String, Object> response) {
        return candidates.runGuarded(() -> {
            Attempt a = mine(portalKey, attemptId);
            Instant now = Instant.now();
            if (!"IN_PROGRESS".equals(a.status())) throw ApiException.conflict("attempt_submitted", "This attempt has been submitted; answers can no longer change.");
            if (now.isAfter(a.deadlineAt().plus(GRACE))) throw ApiException.conflict("time_up", "Time is up for this attempt.");
            List<Map<String, Object>> items = paperItems(a.paperId());
            if (position < 1 || position > items.size()) throw ApiException.badRequest("invalid_position", "No such question in this paper.");
            if (seq < 0) throw ApiException.badRequest("invalid_seq", "The sequence number must not be negative.");
            validateResponse(String.valueOf(items.get(position - 1).get("questionVersionId")), response);
            String body = json.writeValueAsString(response);
            if (body.length() > MAX_RESPONSE_CHARS) throw ApiException.badRequest("response_too_large", "This answer is too long.");
            UUID qv = UUID.fromString(String.valueOf(items.get(position - 1).get("questionVersionId")));
            int n = jdbc.sql("""
                    INSERT INTO exam_answer (tenant_id, attempt_id, position, question_version_id, response, seq) VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?)
                    ON CONFLICT (attempt_id, position) DO UPDATE SET response = EXCLUDED.response, seq = EXCLUDED.seq, saved_at = now()
                    WHERE exam_answer.seq < EXCLUDED.seq""")
                    .params(TenantContext.requireTenantId(), attemptId, position, qv, body, seq).update();
            if (n > 0) {
                jdbc.sql("INSERT INTO exam_answer_journal (tenant_id, attempt_id, position, seq, response) VALUES (?, ?, ?, ?, CAST(? AS jsonb))")
                        .params(TenantContext.requireTenantId(), attemptId, position, seq, body).update();
                jdbc.sql("UPDATE exam_attempt SET answers_saved = answers_saved + 1, version = version + 1 WHERE id = ?").param(attemptId).update();
            }
            Instant savedAt = jdbc.sql("SELECT saved_at FROM exam_answer WHERE attempt_id = ? AND position = ?").params(attemptId, position)
                    .query(Timestamp.class).single().toInstant();
            long stored = jdbc.sql("SELECT seq FROM exam_answer WHERE attempt_id = ? AND position = ?").params(attemptId, position).query(Long.class).single();
            return new AnswerAck(position, stored, n > 0, savedAt, Instant.now());
        });
    }

    /**
     * A response is {"value": …} with the shape scoring expects: an option id (single choice), a list of option ids
     * (multiple choice), a boolean (true/false), or text (numeric, short answer); {"value": null} clears an answer.
     * Option ids must belong to the question; nothing else is stored.
     */
    private void validateResponse(String questionVersionId, Map<String, Object> response) {
        if (response == null || !response.keySet().equals(java.util.Set.of("value"))) throw ApiException.badRequest("invalid_response", "An answer is {\"value\": …}.");
        Object v = response.get("value");
        if (v == null) return;
        Map<String, Object> q = jdbc.sql("SELECT type_code, body::text FROM question_version WHERE id = ?::uuid").param(questionVersionId)
                .query((rs, n) -> Map.<String, Object>of("type", rs.getString(1), "body", rs.getString(2))).single();
        @SuppressWarnings("unchecked") Map<String, Object> body = json.readValue(String.valueOf(q.get("body")), Map.class);
        java.util.Set<String> options = new java.util.HashSet<>();
        if (body.get("options") instanceof List<?> l) for (Object o : l) if (o instanceof Map<?, ?> m) options.add(String.valueOf(m.get("id")));
        boolean ok = switch (String.valueOf(q.get("type"))) {
            case "SINGLE_CHOICE" -> v instanceof String s && options.contains(s);
            case "MULTIPLE_CHOICE" -> v instanceof List<?> l && l.size() <= options.size() && l.stream().allMatch(o -> o instanceof String s && options.contains(s))
                    && new java.util.HashSet<>(l).size() == l.size();
            case "TRUE_FALSE" -> v instanceof Boolean;
            case "NUMERIC", "SHORT_ANSWER" -> v instanceof String s && s.length() <= 1000;
            default -> false;
        };
        if (!ok) throw ApiException.badRequest("invalid_response", "This answer does not fit the question.");
    }

    // ================================================================================================ submission

    public SubmitResult submit(String portalKey, UUID attemptId, String submitKey) {
        return candidates.runGuarded(() -> {
            Attempt a = mine(portalKey, attemptId);
            if (!"IN_PROGRESS".equals(a.status())) return result(attempt(attemptId));     // idempotent: same answer for a retry
            int n = jdbc.sql("UPDATE exam_attempt SET status = 'SUBMITTED', submitted_at = now(), submit_key = ?, version = version + 1 WHERE id = ? AND status = 'IN_PROGRESS'")
                    .params(submitKey == null || submitKey.isBlank() ? null : submitKey.strip(), attemptId).update();
            Attempt done = attempt(attemptId);
            if (n > 0) submitted(done, "CANDIDATE");
            return result(done);
        });
    }

    private void submitted(Attempt a, String by) {
        int answered = jdbc.sql("SELECT count(*) FROM exam_answer WHERE attempt_id = ?").param(a.id()).query(Integer.class).single();
        Map<String, String> d = new LinkedHashMap<>();
        d.put("schedule", a.scheduleId().toString());
        d.put("by", by);
        d.put("answered", String.valueOf(answered));
        audit.recordSuccess("exam.attempt.submitted", "exam_attempt", a.id().toString(), d);
        outbox.append("exam_attempt", a.id().toString(), "exam.attempt.submitted", Map.of("attemptId", a.id().toString(), "scheduleId", a.scheduleId().toString(),
                "versionId", a.versionId().toString(), "by", by));
    }

    /** Submits attempts whose deadline (plus grace) has passed. Runs in the delivery process on one instance at a time. */
    @Scheduled(fixedDelayString = "${ivorovi.exams.auto-submit-interval:PT15S}", initialDelayString = "PT30S")
    void scheduledAutoSubmit() {
        if (process.role() != ProcessRole.DELIVERY) return;
        if (!leases.tryAcquire("exams.auto-submit", Duration.ofSeconds(14))) return;
        try {
            autoSubmitDue();
        } catch (RuntimeException e) {
            log.warn("Automatic submission failed: {}", e.getClass().getSimpleName());
        }
    }

    /** Returns the number of attempts submitted because time was up. */
    public int autoSubmitDue() {
        List<UUID> tenants = TenantContext.callAsPlatform(() -> jdbc.sql("SELECT id FROM tenant WHERE status IN ('ACTIVE', 'TRIAL')").query(UUID.class).list());
        int total = 0;
        for (UUID t : tenants) {
            Integer n = TenantContext.callInTenant(t, () -> tx.execute(s -> {
                List<UUID> due = jdbc.sql("SELECT id FROM exam_attempt WHERE status = 'IN_PROGRESS' AND deadline_at + interval '30 seconds' < now() LIMIT 500 FOR UPDATE SKIP LOCKED")
                        .query(UUID.class).list();
                for (UUID id : due) {
                    jdbc.sql("UPDATE exam_attempt SET status = 'AUTO_SUBMITTED', submitted_at = now(), version = version + 1 WHERE id = ? AND status = 'IN_PROGRESS'").param(id).update();
                    submitted(attempt(id), "TIME_UP");
                }
                return due.size();
            }));
            total += n == null ? 0 : n;
        }
        return total;
    }

    // ================================================================================================ helpers

    private SubmitResult result(Attempt a) {
        int answered = jdbc.sql("SELECT count(*) FROM exam_answer WHERE attempt_id = ?").param(a.id()).query(Integer.class).single();
        return new SubmitResult(a.id(), a.status(), a.submittedAt(), answered, paperItems(a.paperId()).size());
    }

    /** The caller's own attempt (404 for anyone else's). */
    private Attempt mine(String portalKey, UUID attemptId) {
        CandidateService.Portal p = candidates.portal(portalKey);
        UUID me = candidates.requireCandidate(p);
        Attempt a = jdbc.sql(SELECT + " WHERE id = ?").param(attemptId).query((rs, n) -> row(rs)).optional().orElseThrow(() -> ApiException.notFound("Attempt"));
        if (!a.candidateId().equals(me)) throw ApiException.notFound("Attempt");
        return a;
    }

    private Optional<Attempt> attemptFor(UUID scheduleId, UUID candidate) {
        return jdbc.sql(SELECT + " WHERE schedule_id = ? AND candidate_id = ?").params(scheduleId, candidate).query((rs, n) -> row(rs)).optional();
    }

    private Attempt attempt(UUID id) {
        return jdbc.sql(SELECT + " WHERE id = ?").param(id).query((rs, n) -> row(rs)).single();
    }

    private static final String SELECT = "SELECT id, schedule_id, candidate_id, session_id, version_id, paper_id, status, started_at, deadline_at, extra_minutes, submitted_at, submit_key FROM exam_attempt";

    private static Attempt row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Attempt(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getObject(4, UUID.class),
                rs.getObject(5, UUID.class), rs.getObject(6, UUID.class), rs.getString(7), rs.getTimestamp(8).toInstant(), rs.getTimestamp(9).toInstant(),
                rs.getInt(10), rs.getTimestamp(11) == null ? null : rs.getTimestamp(11).toInstant(), rs.getString(12));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> paperItems(UUID paperId) {
        return json.readValue(jdbc.sql("SELECT items::text FROM assessment_paper WHERE id = ?").param(paperId).query(String.class).single(), List.class);
    }

    @SuppressWarnings("unchecked")
    private AttemptView view(Attempt a) {
        String title = jdbc.sql("SELECT title FROM assessment_version WHERE id = ?").param(a.versionId()).query(String.class).single();
        List<Answer> answers = jdbc.sql("SELECT position, response::text, seq, saved_at FROM exam_answer WHERE attempt_id = ? ORDER BY position").param(a.id())
                .query((rs, n) -> new Answer(rs.getInt(1), json.readValue(rs.getString(2), Map.class), rs.getLong(3), rs.getTimestamp(4).toInstant())).list();
        boolean open = "IN_PROGRESS".equals(a.status());
        return new AttemptView(a.id(), a.scheduleId(), title, a.status(), a.startedAt(), a.deadlineAt(), a.extraMinutes(), Instant.now(), a.submittedAt(),
                open ? assessments.candidatePaper(a.paperId()) : List.of(), answers);
    }
}
