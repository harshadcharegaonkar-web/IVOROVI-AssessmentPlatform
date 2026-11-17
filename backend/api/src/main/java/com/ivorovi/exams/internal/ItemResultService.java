package com.ivorovi.exams.internal;

import com.ivorovi.exams.ItemResults;
import com.ivorovi.platform.audit.AuditLog;
import com.ivorovi.platform.security.CurrentUser;
import com.ivorovi.platform.security.IvoroviPrincipal;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.platform.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Contract C-1: deferred items and their marks (see {@link ItemResults}). */
@Service
class ItemResultService implements ItemResults {

    private final JdbcClient jdbc;
    private final AuditLog audit;
    private final JsonMapper json;
    private final ScoringService scoring;

    ItemResultService(JdbcClient jdbc, AuditLog audit, JsonMapper json, @Lazy ScoringService scoring) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.json = json;
        this.scoring = scoring;
    }

    private record Deferred(UUID paperId, Integer[] evaluation, Integer[] execution) {}

    private Deferred load(UUID attemptId, boolean lock) {
        return jdbc.sql("SELECT a.paper_id, d.evaluation_positions, d.execution_positions FROM exam_attempt_deferred d JOIN exam_attempt a ON a.id = d.attempt_id"
                        + " WHERE d.attempt_id = ?" + (lock ? " FOR UPDATE OF a" : "")).param(attemptId)
                .query((rs, n) -> new Deferred(rs.getObject(1, UUID.class), (Integer[]) rs.getArray(2).getArray(), (Integer[]) rs.getArray(3).getArray()))
                .optional().orElse(null);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> paper(UUID paperId) {
        return json.readValue(jdbc.sql("SELECT items::text FROM assessment_paper WHERE id = ?").param(paperId).query(String.class).single(), List.class);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> deferredAttempts(UUID scheduleId, Source source) {
        String column = source == Source.EVALUATION ? "evaluation_positions" : "execution_positions";
        return jdbc.sql("SELECT d.attempt_id FROM exam_attempt_deferred d JOIN exam_attempt a ON a.id = d.attempt_id WHERE a.schedule_id = ? AND cardinality(d."
                + column + ") > 0 ORDER BY d.created_at, d.attempt_id").param(scheduleId).query(UUID.class).list();
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Optional<UUID> scheduleUnit(UUID scheduleId) {
        return jdbc.sql("SELECT rs.org_unit_id FROM exam_attempt a JOIN readiness_session rs ON rs.id = a.session_id WHERE a.schedule_id = ? LIMIT 1")
                .param(scheduleId).query(UUID.class).optional();
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Object response(UUID attemptId, int position) {
        String status = jdbc.sql("SELECT status FROM exam_attempt WHERE id = ?").param(attemptId).query(String.class).optional()
                .orElseThrow(() -> ApiException.notFound("Attempt"));
        if ("IN_PROGRESS".equals(status)) throw ApiException.conflict("attempt_in_progress", "The attempt is still in progress.");
        return jdbc.sql("SELECT response::text FROM exam_answer WHERE attempt_id = ? AND position = ?").params(attemptId, position).query(String.class).optional()
                .map(t -> ((Map<String, Object>) json.readValue(t, Map.class)).get("value")).orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Item> deferred(UUID attemptId) {
        Deferred d = load(attemptId, false);
        if (d == null) return List.of();
        List<Map<String, Object>> items = paper(d.paperId());
        Map<Integer, BigDecimal> marked = new HashMap<>();
        jdbc.sql("SELECT position, awarded FROM exam_item_mark WHERE attempt_id = ?").param(attemptId)
                .query((rs, n) -> marked.put(rs.getInt(1), rs.getBigDecimal(2))).list();
        List<Item> out = new ArrayList<>();
        for (int p = 1; p <= items.size(); p++) {
            Source s = Arrays.asList(d.evaluation()).contains(p) ? Source.EVALUATION : Arrays.asList(d.execution()).contains(p) ? Source.EXECUTION : null;
            if (s == null) continue;
            Map<String, Object> e = items.get(p - 1);
            out.add(new Item(attemptId, p, UUID.fromString(String.valueOf(e.get("questionVersionId"))), new BigDecimal(String.valueOf(e.get("marks"))), s, marked.get(p)));
        }
        return out;
    }

    @Override
    @Transactional
    public boolean record(UUID attemptId, int position, BigDecimal awarded, Source source, String detail) {
        Deferred d = load(attemptId, true);
        if (d == null) throw ApiException.notFound("Deferred item");
        Integer[] positions = source == Source.EVALUATION ? d.evaluation() : d.execution();
        if (!Arrays.asList(positions).contains(position)) throw ApiException.badRequest("not_deferred", "This answer is not marked by " + source.name().toLowerCase() + ".");
        BigDecimal marks = new BigDecimal(String.valueOf(paper(d.paperId()).get(position - 1).get("marks")));
        if (awarded == null || awarded.signum() < 0 || awarded.compareTo(marks) > 0 || awarded.stripTrailingZeros().scale() > 2)
            throw ApiException.badRequest("invalid_marks", "Marks must be between 0 and " + marks.stripTrailingZeros().toPlainString() + ", with at most two decimals.");
        BigDecimal value = awarded.setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal existing = jdbc.sql("SELECT awarded FROM exam_item_mark WHERE attempt_id = ? AND position = ?").params(attemptId, position)
                .query(BigDecimal.class).optional().orElse(null);
        if (existing != null) {
            if (existing.compareTo(value) == 0) return false;
            throw ApiException.conflict("already_marked", "Different marks were already recorded for this answer; a change goes through re-evaluation.");
        }
        if (jdbc.sql("SELECT EXISTS (SELECT 1 FROM exam_score WHERE attempt_id = ?)").param(attemptId).query(Boolean.class).single())
            throw ApiException.conflict("already_scored", "This attempt's score is complete; a change goes through re-evaluation.");
        String text = detail == null || detail.isBlank() ? null : detail.strip();
        if (text != null && text.length() > 200) text = text.substring(0, 200);
        UUID actor = CurrentUser.get().map(IvoroviPrincipal::userId).orElse(null);
        jdbc.sql("INSERT INTO exam_item_mark (tenant_id, attempt_id, position, source, awarded, marks, detail, recorded_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                .params(TenantContext.requireTenantId(), attemptId, position, source.name(), value, marks, text, actor).update();
        Map<String, String> a = new LinkedHashMap<>();
        a.put("position", String.valueOf(position));
        a.put("source", source.name());
        a.put("awarded", value.toPlainString());
        a.put("marks", marks.toPlainString());
        audit.recordSuccess("exam.item.marked", "exam_attempt", attemptId.toString(), a);
        int marked = jdbc.sql("SELECT count(*) FROM exam_item_mark WHERE attempt_id = ?").param(attemptId).query(Integer.class).single();
        return marked == d.evaluation().length + d.execution().length && scoring.score(attemptId) != null;
    }
}
