package com.ivorovi.assessments.internal;

import static com.ivorovi.assessments.internal.AssessmentService.AUTHOR;
import static com.ivorovi.assessments.internal.AssessmentService.PUBLISH;
import static com.ivorovi.assessments.internal.AssessmentService.REVIEW;

import com.ivorovi.platform.audit.AuditLog;
import com.ivorovi.platform.crypto.FieldCipher;
import com.ivorovi.platform.ids.Ids;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.platform.web.ApiException;
import com.ivorovi.questions.ApprovedQuestions;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Generated papers (Q-2.2-4). A paper is the concrete list of question versions, marks and option orders drawn from an
 * assessment version with a 256-bit seed from {@link SecureRandom}. The seed is stored encrypted (field
 * {@value #SEED_FIELD}), never returned in a paper view, and disclosed only to holders of assessment.publish, audited.
 * Papers never change; reproducing one regenerates it from the stored seed and compares the paper hash. Previews are
 * candidate projections: they never contain answer keys, explanations or marking rules.
 */
@Service
public class PaperService {

    static final String SEED_FIELD = "assessment_paper.seed";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Schema(name = "AssessmentPaperItem")
    public record PaperItem(int position, UUID questionVersionId, String type, Map<String, Object> content, Map<String, Object> projection,
                            List<String> optionOrder, BigDecimal marks, BigDecimal negativeMarks, String source) {}

    @Schema(name = "AssessmentPaper")
    public record PaperView(UUID id, UUID assessmentId, UUID versionId, int versionNo, String versionState, String purpose, int generatorVersion,
                            String hash, BigDecimal totalMarks, int durationMinutes, String title, String instructions, Instant createdAt,
                            List<PaperItem> items) {}

    @Schema(name = "AssessmentPaperReproduction")
    public record Reproduction(UUID paperId, boolean identical, String storedHash, String regeneratedHash, String note) {}

    @Schema(name = "AssessmentPaperSeed")
    public record Seed(UUID paperId, String seedBase64, int generatorVersion) {}

    @Schema(name = "AssessmentPaperSummary")
    public record PaperSummary(UUID id, int versionNo, String purpose, String hash, BigDecimal totalMarks, int questions, String createdBy, Instant createdAt) {}

    private final JdbcClient jdbc;
    private final AssessmentService assessments;
    private final ApprovedQuestions questions;
    private final FieldCipher cipher;
    private final AuditLog audit;
    private final JsonMapper json;

    PaperService(JdbcClient jdbc, AssessmentService assessments, ApprovedQuestions questions, FieldCipher cipher, AuditLog audit, JsonMapper json) {
        this.jdbc = jdbc;
        this.assessments = assessments;
        this.questions = questions;
        this.cipher = cipher;
        this.audit = audit;
        this.json = json;
    }

    /** Staff who take part in the workflow (authors, reviewers, publishers) may preview; readers alone may not see questions. */
    private void requirePreviewer(AssessmentService.Head h) {
        if (!(assessments.canAuthor(h.purpose(), h.orgUnitId()) || assessments.can(REVIEW, h.orgUnitId()) || assessments.can(PUBLISH, h.orgUnitId()))) {
            throw new AccessDeniedException("Missing permission " + AUTHOR + ", " + REVIEW + " or " + PUBLISH);
        }
    }

    // ================================================================================================ generation

    /** Generates and stores a preview paper of the given version (current version when null). */
    @Transactional
    public PaperView preview(UUID assessmentId, Integer versionNo) {
        AssessmentService.Head h = assessments.head(assessmentId, false);
        requirePreviewer(h);
        UUID vid = versionNo == null ? h.currentId() : versionId(assessmentId, versionNo);
        AssessmentService.Validation val = assessments.validateVersion(h, vid);
        List<AssessmentService.Issue> blocking = val.errors().stream()
                .filter(i -> List.of("pool_too_small", "empty", "question_not_approved", "rules_overlap").contains(i.code())).toList();
        if (!blocking.isEmpty()) throw ApiException.conflict("preview_not_possible", "A paper cannot be generated yet: " + blocking.getFirst().message());
        byte[] seed = new byte[32];
        RANDOM.nextBytes(seed);
        PaperGenerator.Paper paper = generate(vid, seed);
        UUID id = store(vid, "PREVIEW", null, seed, paper);
        Map<String, String> d = new LinkedHashMap<>();
        d.put("assessment", assessmentId.toString());
        d.put("versionId", vid.toString());
        d.put("purpose", "PREVIEW");
        d.put("questions", String.valueOf(paper.entries().size()));
        d.put("paperHash", paper.hash());
        audit.recordSuccess("assessment.paper_generated", "assessment_paper", id.toString(), d);
        return view(id, h);
    }

    /**
     * Paper for one candidate attempt (Increment 2.5 delivery): only from a published version; idempotent per attempt
     * reference, so a retried request returns the same paper. Callers are trusted modules, not staff endpoints.
     */
    @Transactional
    public UUID generateForAttempt(UUID versionId, String attemptRef) {
        if (attemptRef == null || attemptRef.isBlank() || attemptRef.length() > 100) throw new IllegalArgumentException("attempt reference");
        UUID existing = jdbc.sql("SELECT id FROM assessment_paper WHERE attempt_ref = ?").param(attemptRef).query(UUID.class).optional().orElse(null);
        if (existing != null) return existing;
        String state = jdbc.sql("SELECT state FROM assessment_version WHERE id = ?").param(versionId).query(String.class).optional()
                .orElseThrow(() -> ApiException.notFound("Assessment version"));
        if (!"PUBLISHED".equals(state)) throw ApiException.conflict("not_published", "Attempt papers come only from the published version.");
        byte[] seed = new byte[32];
        RANDOM.nextBytes(seed);
        PaperGenerator.Paper paper = generate(versionId, seed);
        try {
            UUID id = store(versionId, "ATTEMPT", attemptRef, seed, paper);
            audit.recordSuccess("assessment.paper_generated", "assessment_paper", id.toString(), Map.of("versionId", versionId.toString(), "purpose", "ATTEMPT",
                    "questions", String.valueOf(paper.entries().size()), "paperHash", paper.hash()));
            return id;
        } catch (DuplicateKeyException e) {
            return jdbc.sql("SELECT id FROM assessment_paper WHERE attempt_ref = ?").param(attemptRef).query(UUID.class).single();
        }
    }

    /** Runs the generator for a version: fixed questions, then each rule's pool (frozen for published versions, live for drafts). */
    PaperGenerator.Paper generate(UUID versionId, byte[] seed) {
        AssessmentService.Version v = assessments.version(versionId);
        boolean frozen = List.of("PUBLISHED", "SUPERSEDED", "WITHDRAWN").contains(v.state());
        Map<UUID, ApprovedQuestions.Approved> fixedQ = questions.versions(v.items().stream().map(AssessmentService.Item::questionVersionId).toList());
        List<PaperGenerator.Fixed> fixed = new ArrayList<>();
        for (AssessmentService.Item it : v.items()) {
            ApprovedQuestions.Approved a = fixedQ.get(it.questionVersionId());
            if (a == null) throw ApiException.conflict("question_missing", "A question of this assessment no longer exists.");
            fixed.add(new PaperGenerator.Fixed(it.questionVersionId(), a.questionId(), it.marks(), it.negativeMarks(), ApprovedQuestions.optionIds(a.projection()),
                    shufflable(a.projection())));
        }
        List<PaperGenerator.Rule> rules = new ArrayList<>();
        for (AssessmentService.Rule r : v.rules()) {
            List<ApprovedQuestions.PoolEntry> pool = assessments.poolOf(r, frozen);
            Map<UUID, ApprovedQuestions.Approved> pq = questions.versions(pool.stream().map(ApprovedQuestions.PoolEntry::versionId).toList());
            List<PaperGenerator.Candidate> candidates = new ArrayList<>();
            for (ApprovedQuestions.PoolEntry e : pool) {
                ApprovedQuestions.Approved a = pq.get(e.versionId());
                if (a == null) continue;
                candidates.add(new PaperGenerator.Candidate(e.versionId(), e.questionId(), ApprovedQuestions.optionIds(a.projection()), shufflable(a.projection())));
            }
            rules.add(new PaperGenerator.Rule(r.id(), r.count(), r.marks(), r.negativeMarks(), candidates));
        }
        try {
            return PaperGenerator.generate(versionId, fixed, rules, v.shuffleQuestions(), v.shuffleOptions(), seed);
        } catch (PaperGenerator.PoolExhausted e) {
            throw ApiException.conflict("pool_too_small", "A random rule has too few questions left to draw from.");
        }
    }

    private static boolean shufflable(Map<String, Object> projection) {
        return projection == null || !Boolean.FALSE.equals(projection.get("shuffle"));
    }

    private UUID store(UUID versionId, String purpose, String attemptRef, byte[] seed, PaperGenerator.Paper paper) {
        UUID id = Ids.newId();
        UUID tenant = TenantContext.requireTenantId();
        String seedEnc = cipher.encrypt(Base64.getEncoder().encodeToString(seed), SEED_FIELD, tenant);
        UUID by = com.ivorovi.platform.security.CurrentUser.get().map(u -> u.userId()).orElse(null);
        jdbc.sql("""
                INSERT INTO assessment_paper (id, tenant_id, version_id, purpose, attempt_ref, seed_enc, generator_version, items, total_marks, paper_hash, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?)""")
                .params(id, tenant, versionId, purpose, attemptRef, seedEnc, PaperGenerator.VERSION, json.writeValueAsString(PaperGenerator.toJson(paper.entries())),
                        paper.totalMarks(), paper.hash(), by).update();
        return id;
    }

    // ================================================================================================ reading

    record Row(UUID id, UUID versionId, UUID assessmentId, String purpose, String itemsJson, BigDecimal total, String hash, String seedEnc, int generatorVersion, Instant createdAt) {}

    private Row row(UUID paperId) {
        return jdbc.sql("""
                SELECT p.id, p.version_id, v.assessment_id, p.purpose, p.items::text, p.total_marks, p.paper_hash, p.seed_enc, p.generator_version, p.created_at
                FROM assessment_paper p JOIN assessment_version v ON v.id = p.version_id WHERE p.id = ?""").param(paperId)
                .query((rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5),
                        rs.getBigDecimal(6), rs.getString(7), rs.getString(8), rs.getInt(9), rs.getTimestamp(10).toInstant())).optional()
                .orElseThrow(() -> ApiException.notFound("Paper"));
    }

    /** Staff view of a paper: candidate projection in paper order, options reordered as drawn, no answer information. */
    @Transactional(readOnly = true)
    public PaperView get(UUID paperId) {
        Row r = row(paperId);
        AssessmentService.Head h = assessments.head(r.assessmentId(), false);
        requirePreviewer(h);
        return view(paperId, h);
    }

    @SuppressWarnings("unchecked")
    private PaperView view(UUID paperId, AssessmentService.Head h) {
        Row r = row(paperId);
        List<Map<String, Object>> entries = json.readValue(r.itemsJson(), List.class);
        List<UUID> ids = entries.stream().map(e -> UUID.fromString(String.valueOf(e.get("questionVersionId")))).toList();
        Map<UUID, ApprovedQuestions.Approved> q = questions.versions(ids);
        List<PaperItem> items = new ArrayList<>();
        int pos = 0;
        for (Map<String, Object> e : entries) {
            UUID qv = UUID.fromString(String.valueOf(e.get("questionVersionId")));
            ApprovedQuestions.Approved a = q.get(qv);
            List<String> order = e.get("options") instanceof List<?> l ? l.stream().map(String::valueOf).toList() : null;
            items.add(new PaperItem(++pos, qv, a == null ? null : a.type(), a == null ? null : a.content(), a == null ? null : reorder(a.projection(), order), order,
                    new BigDecimal(String.valueOf(e.get("marks"))), new BigDecimal(String.valueOf(e.get("negativeMarks"))), String.valueOf(e.get("source"))));
        }
        var meta = jdbc.sql("SELECT version_no, state, duration_minutes, title, instructions FROM assessment_version WHERE id = ?").param(r.versionId())
                .query((rs, n) -> new Object[] {rs.getInt(1), rs.getString(2), rs.getInt(3), rs.getString(4), rs.getString(5)}).single();
        return new PaperView(r.id(), h.id(), r.versionId(), (Integer) meta[0], (String) meta[1], r.purpose(), r.generatorVersion(), r.hash(), r.total(),
                (Integer) meta[2], (String) meta[3], (String) meta[4], r.createdAt(), items);
    }

    /** Projection with its options in the drawn order (other projection fields unchanged). */
    @SuppressWarnings("unchecked")
    static Map<String, Object> reorder(Map<String, Object> projection, List<String> order) {
        if (projection == null || order == null || !(projection.get("options") instanceof List<?> opts)) return projection;
        Map<String, Object> byId = new HashMap<>();
        for (Object o : opts) if (o instanceof Map<?, ?> m) byId.put(String.valueOf(m.get("id")), o);
        List<Object> sorted = new ArrayList<>();
        for (String id : order) if (byId.containsKey(id)) sorted.add(byId.remove(id));
        sorted.addAll(byId.values());                                      // never drop an option, even if the stored order is incomplete
        Map<String, Object> out = new LinkedHashMap<>(projection);
        out.put("options", sorted);
        return out;
    }

    @Transactional(readOnly = true)
    public List<PaperSummary> list(UUID assessmentId) {
        AssessmentService.Head h = assessments.head(assessmentId, false);
        requirePreviewer(h);
        return jdbc.sql("""
                SELECT p.id, v.version_no, p.purpose, p.paper_hash, p.total_marks, jsonb_array_length(p.items), u.display_name, p.created_at
                FROM assessment_paper p JOIN assessment_version v ON v.id = p.version_id LEFT JOIN user_account u ON u.id = p.created_by
                WHERE v.assessment_id = ? AND p.purpose = 'PREVIEW' ORDER BY p.created_at DESC LIMIT 100""").param(assessmentId)
                .query((rs, n) -> new PaperSummary(rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getBigDecimal(5), rs.getInt(6),
                        rs.getString(7), rs.getTimestamp(8).toInstant())).list();
    }

    /**
     * Regenerates the paper from its stored seed and the version's definition. For published versions the pools are
     * frozen, so the result is identical; a draft's live pools may have changed since, which the note explains.
     */
    @Transactional
    public Reproduction reproduce(UUID paperId) {
        Row r = row(paperId);
        AssessmentService.Head h = assessments.head(r.assessmentId(), false);
        requirePreviewer(h);
        if (r.generatorVersion() != PaperGenerator.VERSION) {
            return new Reproduction(paperId, false, r.hash(), null, "Generated by generator version " + r.generatorVersion() + "; this server runs version " + PaperGenerator.VERSION + ".");
        }
        byte[] seed = Base64.getDecoder().decode(cipher.decrypt(r.seedEnc(), SEED_FIELD, TenantContext.requireTenantId()));
        String regenerated;
        try {
            regenerated = generate(r.versionId(), seed).hash();
        } catch (ApiException e) {
            regenerated = null;
        }
        boolean same = r.hash().equals(regenerated);
        String state = jdbc.sql("SELECT state FROM assessment_version WHERE id = ?").param(r.versionId()).query(String.class).single();
        String note = same ? "The stored seed reproduces this paper exactly."
                : List.of("PUBLISHED", "SUPERSEDED", "WITHDRAWN").contains(state)
                        ? "The regenerated paper differs from the stored one. Investigate: a published version's pools are frozen."
                        : "This version is still a draft; its question pools or definition changed after the paper was generated.";
        audit.recordSuccess("assessment.paper_reproduced", "assessment_paper", paperId.toString(), Map.of("assessment", r.assessmentId().toString(),
                "identical", String.valueOf(same)));
        return new Reproduction(paperId, same, r.hash(), regenerated, note);
    }

    /** Discloses a stored seed to a holder of assessment.publish (independent verification); always audited. */
    @Transactional
    public Seed seed(UUID paperId) {
        Row r = row(paperId);
        AssessmentService.Head h = assessments.head(r.assessmentId(), false);
        if (!assessments.can(PUBLISH, h.orgUnitId())) {
            audit.recordDenied("assessment.paper_seed_denied", Map.of("paper", paperId.toString()));
            throw new AccessDeniedException("Missing permission " + PUBLISH);
        }
        String seed = cipher.decrypt(r.seedEnc(), SEED_FIELD, TenantContext.requireTenantId());
        audit.recordSuccess("assessment.paper_seed_disclosed", "assessment_paper", paperId.toString(), Map.of("assessment", r.assessmentId().toString()));
        return new Seed(paperId, seed, r.generatorVersion());
    }

    private UUID versionId(UUID assessmentId, int no) {
        return jdbc.sql("SELECT id FROM assessment_version WHERE assessment_id = ? AND version_no = ?").params(assessmentId, no).query(UUID.class).optional()
                .orElseThrow(() -> ApiException.notFound("Assessment version"));
    }
}
