package com.ivorovi.exams.internal;

import com.ivorovi.platform.audit.AuditLog;
import com.ivorovi.platform.jobs.JobLeases;
import com.ivorovi.platform.outbox.Outbox;
import com.ivorovi.platform.process.ProcessProperties;
import com.ivorovi.platform.process.ProcessRole;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.questions.QuestionMarking;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * M4 automatic scoring of submitted attempts. Each paper position is marked with the marks and negative marks the
 * published assessment assigned (stored in the paper); grading runs inside the question module, so no key is read here.
 * A score is written once (idempotent: an already scored attempt is skipped) with a hash over the per-answer grades.
 * Scores stay PROVISIONAL until released through independent approval (a later step).
 */
@Service
public class ScoringService {

    private static final Logger log = LoggerFactory.getLogger(ScoringService.class);

    public record Score(UUID attemptId, BigDecimal awarded, BigDecimal maxMarks, int correct, int partial, int incorrect, int unanswered, int notMarkable,
                        String scoreHash) {}

    private final JdbcClient jdbc;
    private final QuestionMarking marking;
    private final AuditLog audit;
    private final Outbox outbox;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final JobLeases leases;
    private final ProcessProperties process;

    ScoringService(JdbcClient jdbc, QuestionMarking marking, AuditLog audit, Outbox outbox, TransactionTemplate tx, JsonMapper json, JobLeases leases,
                   ProcessProperties process) {
        this.jdbc = jdbc;
        this.marking = marking;
        this.audit = audit;
        this.outbox = outbox;
        this.tx = tx;
        this.json = json;
        this.leases = leases;
        this.process = process;
    }

    @Scheduled(fixedDelayString = "${ivorovi.exams.scoring-interval:PT20S}", initialDelayString = "PT40S")
    void scheduled() {
        if (process.role() != ProcessRole.ADMIN) return;
        if (!leases.tryAcquire("exams.scoring", Duration.ofSeconds(19))) return;
        try {
            scoreDue();
        } catch (RuntimeException e) {
            log.warn("Scoring run failed: {}", e.getClass().getSimpleName());
        }
    }

    /** Scores every submitted, unscored attempt in every active organization. Returns the number scored. */
    public int scoreDue() {
        List<UUID> tenants = TenantContext.callAsPlatform(() -> jdbc.sql("SELECT id FROM tenant WHERE status IN ('ACTIVE', 'TRIAL')").query(UUID.class).list());
        int total = 0;
        for (UUID t : tenants) {
            List<UUID> due = TenantContext.callInTenant(t, () -> tx.execute(s -> jdbc.sql("""
                    SELECT a.id FROM exam_attempt a WHERE a.status IN ('SUBMITTED', 'AUTO_SUBMITTED')
                      AND NOT EXISTS (SELECT 1 FROM exam_score s WHERE s.attempt_id = a.id) ORDER BY a.submitted_at LIMIT 200""").query(UUID.class).list()));
            for (UUID id : due == null ? List.<UUID>of() : due) {
                try {
                    if (TenantContext.callInTenant(t, () -> tx.execute(s -> score(id))) != null) total++;
                } catch (RuntimeException e) {
                    log.warn("Scoring of one attempt failed: {}", e.getClass().getSimpleName());
                }
            }
        }
        return total;
    }

    /** Scores one submitted attempt (in the caller's tenant and transaction); null when already scored or not submitted. */
    @SuppressWarnings("unchecked")
    Score score(UUID attemptId) {
        record A(UUID paperId, String status, UUID scheduleId) {}
        A a = jdbc.sql("SELECT paper_id, status, schedule_id FROM exam_attempt WHERE id = ? FOR UPDATE").param(attemptId)
                .query((rs, n) -> new A(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class))).optional().orElse(null);
        if (a == null || "IN_PROGRESS".equals(a.status())) return null;
        if (jdbc.sql("SELECT EXISTS (SELECT 1 FROM exam_score WHERE attempt_id = ?)").param(attemptId).query(Boolean.class).single()) return null;
        List<Map<String, Object>> items = json.readValue(jdbc.sql("SELECT items::text FROM assessment_paper WHERE id = ?").param(a.paperId()).query(String.class).single(), List.class);
        Map<Integer, Object> responses = new HashMap<>();
        jdbc.sql("SELECT position, response::text FROM exam_answer WHERE attempt_id = ?").param(attemptId)
                .query((rs, n) -> { responses.put(rs.getInt(1), ((Map<String, Object>) json.readValue(rs.getString(2), Map.class)).get("value")); return null; }).list();

        QuestionMarking.Run run = marking.run();
        Map<QuestionMarking.Outcome, Integer> counts = new EnumMap<>(QuestionMarking.Outcome.class);
        BigDecimal awarded = BigDecimal.ZERO;
        BigDecimal max = BigDecimal.ZERO;
        StringBuilder evidence = new StringBuilder("ivorovi.score.v1|").append(attemptId);
        UUID tenant = TenantContext.requireTenantId();
        List<Object[]> rows = new java.util.ArrayList<>();
        int position = 0;
        for (Map<String, Object> e : items) {
            position++;
            UUID qv = UUID.fromString(String.valueOf(e.get("questionVersionId")));
            BigDecimal marks = new BigDecimal(String.valueOf(e.get("marks")));
            BigDecimal neg = new BigDecimal(String.valueOf(e.get("negativeMarks")));
            QuestionMarking.Grade g = run.mark(qv, responses.get(position), marks, neg);
            counts.merge(g.outcome(), 1, Integer::sum);
            awarded = awarded.add(g.awarded());
            max = max.add(marks);
            evidence.append('|').append(position).append(':').append(qv).append(':').append(g.outcome()).append(':').append(g.awarded().stripTrailingZeros().toPlainString());
            rows.add(new Object[] {tenant, attemptId, position, qv, g.outcome().name(), g.awarded(), marks});
        }
        String hash = sha256(evidence.toString());
        Score sc = new Score(attemptId, awarded, max, counts.getOrDefault(QuestionMarking.Outcome.CORRECT, 0), counts.getOrDefault(QuestionMarking.Outcome.PARTIAL, 0),
                counts.getOrDefault(QuestionMarking.Outcome.INCORRECT, 0) + counts.getOrDefault(QuestionMarking.Outcome.INVALID, 0),
                counts.getOrDefault(QuestionMarking.Outcome.UNANSWERED, 0), counts.getOrDefault(QuestionMarking.Outcome.NOT_MARKABLE, 0), hash);
        jdbc.sql("""
                INSERT INTO exam_score (attempt_id, tenant_id, awarded, max_marks, correct, partial, incorrect, unanswered, not_markable, score_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(attemptId, tenant, sc.awarded(), sc.maxMarks(), sc.correct(), sc.partial(), sc.incorrect(), sc.unanswered(), sc.notMarkable(), hash).update();
        for (Object[] r : rows) {
            jdbc.sql("INSERT INTO exam_answer_score (tenant_id, attempt_id, position, question_version_id, outcome, awarded, marks) VALUES (?, ?, ?, ?, ?, ?, ?)")
                    .params(r).update();
        }
        Map<String, String> d = new LinkedHashMap<>();
        d.put("schedule", a.scheduleId().toString());
        d.put("awarded", sc.awarded().toPlainString());
        d.put("maxMarks", sc.maxMarks().toPlainString());
        d.put("notMarkable", String.valueOf(sc.notMarkable()));
        d.put("scoreHash", hash);
        audit.recordSuccess("exam.attempt.scored", "exam_attempt", attemptId.toString(), d);
        outbox.append("exam_attempt", attemptId.toString(), "exam.attempt.scored", Map.of("attemptId", attemptId.toString(), "scheduleId", a.scheduleId().toString()));
        return sc;
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
