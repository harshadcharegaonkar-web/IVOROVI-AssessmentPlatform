package com.ivorovi.questions;

import com.ivorovi.platform.web.ApiException;
import com.ivorovi.questions.internal.QuestionAccess;
import com.ivorovi.questions.internal.QuestionService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Read-only module API for assessments (Increment 2.2): approved question versions of banks the caller may read, in the
 * candidate projection only. It never returns answer keys, explanations or marking rules beyond marks. "Approved" means
 * the question's current approved version of an active question; superseded and retired versions are reported by
 * {@link #states} so that callers can warn about them.
 */
@Component
public class ApprovedQuestions {

    @Schema(name = "AssessmentSourceBank")
    public record Bank(UUID id, UUID orgUnitId, String name, List<String> intendedUses, boolean highStakes) {}

    @Schema(name = "ApprovedQuestion")
    public record Approved(UUID versionId, UUID questionId, UUID bankId, String type, Map<String, Object> content, Map<String, Object> projection,
                           BigDecimal marks, BigDecimal negativeMarks, UUID subjectId, String subjectName, UUID topicId, String topicName,
                           UUID difficultyId, String difficultyLabel, String excerpt) {}

    @Schema(name = "ApprovedQuestionPage")
    public record Page(List<Approved> items, long total) {}

    /** Version state together with its question's status (ACTIVE or RETIRED). */
    public record State(UUID versionId, UUID questionId, String state, String questionStatus, UUID currentApprovedVersionId) {
        public boolean usable() {
            return "APPROVED".equals(state) && "ACTIVE".equals(questionStatus);
        }
    }

    public record PoolEntry(UUID versionId, UUID questionId) {}

    /** A question image for delivery (bytes and media type). */
    public record Image(byte[] bytes, String mediaType) {}

    private final JdbcClient jdbc;
    private final QuestionAccess access;
    private final QuestionService questions;
    private final JsonMapper json;

    private final com.ivorovi.questions.internal.AssetService assets;

    ApprovedQuestions(JdbcClient jdbc, QuestionAccess access, QuestionService questions, JsonMapper json, com.ivorovi.questions.internal.AssetService assets) {
        this.jdbc = jdbc;
        this.access = access;
        this.questions = questions;
        this.json = json;
        this.assets = assets;
    }

    /**
     * KI-83: an image used by one of the given question versions (the candidate's paper), only when its malware scan is
     * clean; empty otherwise. The caller (delivery) must already have checked that the versions are the caller's own paper.
     */
    /** KI-83: true when every image of these question versions can be delivered to candidates (see AssetService.deliverable). */
    @Transactional(readOnly = true)
    public boolean imagesDeliverable(Collection<UUID> versionIds) {
        return assets.imagesDeliverable(versionIds);
    }

    public java.util.Optional<Image> imageForVersions(UUID assetId, Collection<UUID> versionIds) {
        return assets.serveForVersions(assetId, versionIds).map(s -> new Image(s.bytes(), s.mediaType()));
    }

    /** The bank, if the caller may read it (404 otherwise, as everywhere in the question module). */
    @Transactional(readOnly = true)
    public Bank bank(UUID bankId) {
        access.bank(bankId);
        return jdbc.sql("SELECT id, org_unit_id, name, intended_uses, approval_policy FROM question_bank WHERE id = ?").param(bankId)
                .query((rs, n) -> new Bank(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        Arrays.asList((String[]) rs.getArray(4).getArray()), "HIGH_STAKES".equals(rs.getString(5)))).single();
    }

    /** Banks the caller may read (any unit). */
    @Transactional(readOnly = true)
    public List<Bank> readableBanks() {
        List<Bank> all = jdbc.sql("SELECT id, org_unit_id, name, intended_uses, approval_policy FROM question_bank WHERE status = 'ACTIVE' ORDER BY name")
                .query((rs, n) -> new Bank(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        Arrays.asList((String[]) rs.getArray(4).getArray()), "HIGH_STAKES".equals(rs.getString(5)))).list();
        return all.stream().filter(b -> access.can(QuestionAccess.READ, b.orgUnitId())).toList();
    }

    private static final String SELECT = """
            SELECT v.id, q.id AS qid, q.bank_id, v.type_code, v.content::text AS content, v.body::text AS body, v.marks, v.negative_marks,
                   v.subject_id, s.name AS subject, v.topic_id, t.name AS topic, v.difficulty_id, d.label AS difficulty, left(v.plain_text, 200) AS excerpt
            FROM question q JOIN question_version v ON v.id = q.approved_version_id
            LEFT JOIN question_subject s ON s.id = v.subject_id LEFT JOIN question_topic t ON t.id = v.topic_id
            LEFT JOIN difficulty_level d ON d.id = v.difficulty_id""";

    @SuppressWarnings("unchecked")
    private Approved row(ResultSet rs, int n) throws SQLException {
        String type = rs.getString("type_code");
        Map<String, Object> body = json.readValue(rs.getString("body"), Map.class);
        return new Approved(rs.getObject("id", UUID.class), rs.getObject("qid", UUID.class), rs.getObject("bank_id", UUID.class), type,
                json.readValue(rs.getString("content"), Map.class), questions.type(type).deliveryProjection(body), rs.getBigDecimal("marks"),
                rs.getBigDecimal("negative_marks"), rs.getObject("subject_id", UUID.class), rs.getString("subject"), rs.getObject("topic_id", UUID.class),
                rs.getString("topic"), rs.getObject("difficulty_id", UUID.class), rs.getString("difficulty"), excerpt(json.readValue(rs.getString("content"), Map.class)));
    }

    /**
     * Excerpt of the question statement only (paragraph text, mathematics as $…$, list items, image descriptions), at
     * most 200 characters. Options are never included: the stored plain text also contains option text, which made
     * excerpts read as if the options were part of the question.
     */
    static String excerpt(Map<String, Object> content) {
        StringBuilder sb = new StringBuilder();
        appendText(content == null ? null : content.get("blocks"), sb);
        String s = sb.toString().replaceAll("\\s+", " ").strip();
        return s.length() > 200 ? s.substring(0, 199).stripTrailing() + "…" : s;
    }

    private static void appendText(Object node, StringBuilder sb) {
        if (node instanceof List<?> l) {
            for (Object o : l) appendText(o, sb);
        } else if (node instanceof Map<?, ?> m) {
            String type = String.valueOf(m.get("type"));
            if ("math".equals(type) && m.get("tex") != null) {
                sb.append(" $").append(m.get("tex")).append("$ ");
            } else if ("image".equals(type) && m.get("alt") != null) {
                sb.append(" [").append(m.get("alt")).append("] ");
            } else if (m.get("math") instanceof String tex) {
                sb.append('$').append(tex).append('$');
            } else if (m.get("text") instanceof String t) {
                sb.append(t);
            }
            if (m.get("runs") != null) { appendText(m.get("runs"), sb); sb.append(' '); }
            if (m.get("items") != null) { appendText(m.get("items"), sb); sb.append(' '); }
            if (m.get("blocks") != null) appendText(m.get("blocks"), sb);
        }
    }

    /** Current approved versions of active questions in a readable bank, filtered (text, subject, topic, difficulty, type). */
    @Transactional(readOnly = true)
    public Page search(UUID bankId, String text, UUID subjectId, UUID topicId, UUID difficultyId, String type, int page, int size) {
        access.bank(bankId);
        StringBuilder where = new StringBuilder(" WHERE q.bank_id = :bank AND q.status = 'ACTIVE' AND v.state = 'APPROVED'");
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("bank", bankId);
        if (subjectId != null) { where.append(" AND v.subject_id = :subject"); p.put("subject", subjectId); }
        if (topicId != null) { where.append(" AND v.topic_id = :topic"); p.put("topic", topicId); }
        if (difficultyId != null) { where.append(" AND v.difficulty_id = :difficulty"); p.put("difficulty", difficultyId); }
        if (type != null) { where.append(" AND v.type_code = :type"); p.put("type", type); }
        if (text != null && !text.isBlank()) {
            where.append(" AND (v.search_vector @@ websearch_to_tsquery('simple', :q) OR v.plain_text ILIKE :like)");
            p.put("q", text.strip());
            p.put("like", "%" + text.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        var count = jdbc.sql("SELECT count(*) FROM question q JOIN question_version v ON v.id = q.approved_version_id" + where);
        for (var e : p.entrySet()) count = count.param(e.getKey(), e.getValue());
        long total = count.query(Long.class).single();
        int sz = Math.max(1, Math.min(100, size));
        var query = jdbc.sql(SELECT + where + " ORDER BY q.updated_at DESC, q.id LIMIT :size OFFSET :offset");
        for (var e : p.entrySet()) query = query.param(e.getKey(), e.getValue());
        return new Page(query.param("size", sz).param("offset", Math.max(0, page) * sz).query(this::row).list(), total);
    }

    /** Pool for a random rule: ids of current approved versions of active questions matching the filters (sorted, deterministic). */
    @Transactional(readOnly = true)
    public List<PoolEntry> pool(UUID bankId, UUID subjectId, UUID topicId, UUID difficultyId, String type) {
        access.bank(bankId);
        return jdbc.sql("""
                SELECT v.id, q.id FROM question q JOIN question_version v ON v.id = q.approved_version_id
                WHERE q.bank_id = ? AND q.status = 'ACTIVE' AND v.state = 'APPROVED'
                  AND (CAST(? AS uuid) IS NULL OR v.subject_id = CAST(? AS uuid)) AND (CAST(? AS uuid) IS NULL OR v.topic_id = CAST(? AS uuid))
                  AND (CAST(? AS uuid) IS NULL OR v.difficulty_id = CAST(? AS uuid)) AND (CAST(? AS text) IS NULL OR v.type_code = CAST(? AS text))
                ORDER BY v.id""").params(bankId, subjectId, subjectId, topicId, topicId, difficultyId, difficultyId, type, type)
                .query((rs, n) -> new PoolEntry(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class))).list();
    }

    /**
     * Candidate projection of specific versions (any state), for previews of assessments the caller is entitled to; the
     * caller (assessment module) has authorized access to the assessment. Never contains answer information.
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Map<UUID, Approved> versions(Collection<UUID> versionIds) {
        if (versionIds.isEmpty()) return Map.of();
        Map<UUID, Approved> out = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT v.id, q.id AS qid, q.bank_id, v.type_code, v.content::text AS content, v.body::text AS body, v.marks, v.negative_marks,
                       v.subject_id, s.name AS subject, v.topic_id, t.name AS topic, v.difficulty_id, d.label AS difficulty, left(v.plain_text, 200) AS excerpt
                FROM question_version v JOIN question q ON q.id = v.question_id
                LEFT JOIN question_subject s ON s.id = v.subject_id LEFT JOIN question_topic t ON t.id = v.topic_id
                LEFT JOIN difficulty_level d ON d.id = v.difficulty_id WHERE v.id = ANY (?)""").param(versionIds.toArray(UUID[]::new))
                .query(this::row).list().forEach(a -> out.put(a.versionId(), a));
        return out;
    }

    /** Current state of versions (for validation and the retired/superseded warning). */
    @Transactional(readOnly = true)
    public Map<UUID, State> states(Collection<UUID> versionIds) {
        if (versionIds.isEmpty()) return Map.of();
        Map<UUID, State> out = new LinkedHashMap<>();
        jdbc.sql("SELECT v.id, q.id, v.state, q.status, q.approved_version_id FROM question_version v JOIN question q ON q.id = v.question_id WHERE v.id = ANY (?)")
                .param(versionIds.toArray(UUID[]::new))
                .query((rs, n) -> new State(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getObject(5, UUID.class)))
                .list().forEach(s -> out.put(s.versionId(), s));
        return out;
    }

    /** Banks of the given versions (for bank-access and admission-bank checks). */
    @Transactional(readOnly = true)
    public Map<UUID, UUID> bankOf(Collection<UUID> versionIds) {
        if (versionIds.isEmpty()) return Map.of();
        Map<UUID, UUID> out = new LinkedHashMap<>();
        jdbc.sql("SELECT v.id, q.bank_id FROM question_version v JOIN question q ON q.id = v.question_id WHERE v.id = ANY (?)")
                .param(versionIds.toArray(UUID[]::new)).query((rs, n) -> { out.put(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)); return null; }).list();
        return out;
    }

    /** Fails with 404 when the caller may not read the bank. */
    public void requireReadable(UUID bankId) {
        try {
            access.bank(bankId);
        } catch (ApiException e) {
            throw ApiException.notFound("Question bank");
        }
    }

    /** All option ids of a choice question's projection (for option shuffling); empty for other types. */
    @SuppressWarnings("unchecked")
    public static List<String> optionIds(Map<String, Object> projection) {
        List<String> ids = new ArrayList<>();
        if (projection != null && projection.get("options") instanceof List<?> l) {
            for (Object o : l) if (o instanceof Map<?, ?> m) ids.add(String.valueOf(m.get("id")));
        }
        return ids;
    }
}
