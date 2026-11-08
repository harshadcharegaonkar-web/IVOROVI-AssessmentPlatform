package com.ivorovi.questions.internal;

import com.ivorovi.platform.audit.AuditLog;
import com.ivorovi.platform.ids.Ids;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.platform.web.ApiException;
import com.ivorovi.questions.internal.types.QuestionType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Questions and their versions: drafting, versioning, the review/approval workflow, retirement, search and history.
 * Approved versions are immutable (service has no path to change them; database trigger refuses it); editing creates a new
 * draft version that goes through the full workflow. Every transition is recorded with actor, time, reason and states.
 */
@Service
public class QuestionService {

    public static final int MAX_STATEMENT = 20_000;
    public static final int MAX_IMAGES = 10;
    public static final long MAX_IMAGE_BYTES_PER_QUESTION = 15L * 1024 * 1024;

    // ------------------------------------------------------------------------------------------------ API types

    public record DraftInput(String type, Map<String, Object> content, Map<String, Object> body, Map<String, Object> marking, Map<String, Object> answerKey,
                             String explanation, BigDecimal marks, BigDecimal negativeMarks, UUID subjectId, UUID topicId, UUID subtopicId, UUID difficultyId,
                             List<UUID> outcomeIds, List<String> tags, Map<String, Object> attributes, String language, Long version) {}

    @Schema(name = "QuestionReview")
    public record Review(UUID id, UUID reviewerId, String reviewerName, String decision, Map<String, Object> checklist, String comment, Instant at,
                         boolean valid, String invalidatedReason) {}

    @Schema(name = "QuestionImageUse")
    public record ImageUse(UUID assetId, String usage, String optionId, String altText, boolean decorative, String scanStatus) {}

    @Schema(name = "QuestionVersion")
    public record Version(UUID id, int versionNo, String state, String type, Map<String, Object> content, Map<String, Object> body, Map<String, Object> marking,
                          BigDecimal marks, BigDecimal negativeMarks, UUID subjectId, UUID topicId, UUID subtopicId, UUID difficultyId, List<UUID> outcomeIds,
                          List<String> tags, Map<String, Object> attributes, String language, List<ImageUse> images, UUID authorId, String authorName,
                          Instant createdAt, Instant updatedAt, Instant submittedAt, UUID approvedBy, String approvedByName, Instant approvedAt, Instant retiredAt,
                          String source, List<Review> reviews, int validReviews, String reviewHash, long version, boolean keyVisible) {}

    @Schema(name = "QuestionVersionSummary")
    public record VersionSummary(UUID id, int versionNo, String state, String authorName, Instant createdAt, Instant approvedAt) {}

    @Schema(name = "QuestionActions")
    public record Actions(boolean edit, boolean submit, boolean review, boolean requestChanges, boolean approve, boolean retire, boolean newVersion,
                          boolean viewKey, String blockedReason) {}

    @Schema(name = "QuestionDetail")
    public record Detail(UUID id, UUID bankId, String bankName, String status, Version current, Version approved, List<VersionSummary> versions,
                         BankService.Policy effectivePolicy, Actions actions, long version) {}

    @Schema(name = "QuestionSummary")
    public record Summary(UUID id, UUID versionId, int versionNo, String state, String type, String excerpt, String subjectName, String difficultyLabel,
                          List<String> tags, BigDecimal marks, BigDecimal negativeMarks, Integer approvedVersionNo, String status, String authorName, Instant updatedAt) {}

    @Schema(name = "QuestionPage")
    public record Page(List<Summary> items, long total, int page, int size) {}

    public record Filters(String q, String state, String type, UUID subjectId, UUID topicId, UUID subtopicId, UUID difficultyId, UUID outcomeId, String tag,
                          String attributeKey, String attributeValue, String status, boolean approvedOnly) {}

    @Schema(name = "QuestionTransition")
    public record Transition(int versionNo, String fromState, String toState, String actorName, String reason, Instant at) {}

    @Schema(name = "QuestionHistoryEvent")
    public record HistoryEvent(String action, String actorName, Instant at, Map<String, Object> details) {}

    @Schema(name = "QueueItem")
    public record QueueItem(UUID questionId, UUID versionId, int versionNo, String bankName, String state, String type, String excerpt, String authorName,
                            Instant submittedAt, int validReviews, int requiredReviews) {}

    @Schema(name = "MarkingPreviewResult")
    public record MarkingResult(Object response, String outcome, BigDecimal awarded, String detail) {}

    private final JdbcClient jdbc;
    private final QuestionAccess access;
    private final AuditLog audit;
    private final AnswerKeyStore keys;
    private final TaxonomyService taxonomy;
    private final BankService banks;
    private final JsonMapper json;
    private final Map<String, QuestionType> types;

    QuestionService(JdbcClient jdbc, QuestionAccess access, AuditLog audit, AnswerKeyStore keys, TaxonomyService taxonomy, BankService banks, JsonMapper json) {
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
        this.keys = keys;
        this.taxonomy = taxonomy;
        this.banks = banks;
        this.json = json;
        this.types = QuestionType.all().stream().collect(Collectors.toMap(QuestionType::code, Function.identity()));
    }

    public QuestionType type(String code) {
        QuestionType t = types.get(code);
        if (t == null) throw ApiException.badRequest("unknown_type", "Unknown question type.");
        return t;
    }

    public List<QuestionType> types() {
        return QuestionType.all();
    }

    // ================================================================================================ preparation

    /** A fully validated draft, ready to store. */
    record Prepared(QuestionType type, Map<String, Object> content, Map<String, Object> body, Map<String, Object> marking, Map<String, Object> key,
                    String explanation, BigDecimal marks, BigDecimal negativeMarks, UUID subjectId, UUID topicId, UUID subtopicId, UUID difficultyId,
                    List<UUID> outcomeIds, List<String> tags, Map<String, Object> attributes, String language, List<ImageUse> images, String plainText,
                    String normalized, String dupHash, String reviewHash, String keyHash) {}

    @SuppressWarnings("unchecked")
    Prepared prepare(UUID bankId, DraftInput in) {
        QuestionType t = type(in.type());
        ContentFormat.Parsed content = ContentFormat.parse(in.content(), "content", MAX_STATEMENT, MAX_IMAGES);
        QuestionType.Body body = t.body(in.body() == null ? Map.of() : in.body());
        Map<String, Object> key = t.key(in.answerKey() == null ? Map.of() : in.answerKey(), body);
        Map<String, Object> marking = t.marking(in.marking() == null ? Map.of() : in.marking());
        BankService.Bank bank = banks.find(bankId);
        BigDecimal marks = in.marks() == null ? bank.defaultMarks() : in.marks();
        BigDecimal neg = in.negativeMarks() == null ? bank.defaultNegativeMarks() : in.negativeMarks();
        if (marks.signum() <= 0 || marks.compareTo(BigDecimal.valueOf(1000)) > 0 || marks.scale() > 2) throw ApiException.badRequest("invalid_marks", "Marks must be above 0 and at most 1000, with at most two decimals.");
        if (neg.signum() < 0 || neg.compareTo(marks) > 0 || neg.scale() > 2) throw ApiException.badRequest("invalid_marks", "Negative marks must be between 0 and the question's marks.");
        String explanation = in.explanation() == null || in.explanation().isBlank() ? null : in.explanation().strip();
        if (explanation != null && explanation.length() > 5000) throw ApiException.badRequest("invalid_explanation", "Explanation longer than 5000 characters.");
        String language = in.language() == null || in.language().isBlank() ? "en" : in.language().strip();
        if (!language.matches("^[a-z]{2,3}(-[A-Za-z0-9]{2,8})?$")) throw ApiException.badRequest("invalid_language", "Language must be a code such as en or hi-IN.");

        validateTaxonomy(in);
        List<String> tags = taxonomy.resolveTags(in.tags(), bank.freeTags());
        Map<String, Object> attributes = validateAttributes(in.attributes());
        List<UUID> outcomes = in.outcomeIds() == null ? List.of() : in.outcomeIds().stream().distinct().sorted().toList();

        // images: statement and options, all assets of this bank, bounded count and combined size
        List<ImageUse> images = new ArrayList<>();
        for (ContentFormat.ImageUse u : content.images()) images.add(new ImageUse(u.assetId(), "STATEMENT", "", u.alt(), u.decorative(), null));
        for (int i = 0; i < body.optionContents().size(); i++) {
            for (ContentFormat.ImageUse u : body.optionContents().get(i).images()) images.add(new ImageUse(u.assetId(), "OPTION", body.optionIds().get(i), u.alt(), u.decorative(), null));
        }
        if (images.size() > MAX_IMAGES) throw ApiException.badRequest("too_many_images", "At most " + MAX_IMAGES + " images per question.");
        List<UUID> assetIds = images.stream().map(ImageUse::assetId).distinct().toList();
        if (!assetIds.isEmpty()) {
            record A(UUID id, UUID bank, long bytes) {}
            List<A> found = jdbc.sql("SELECT id, bank_id, bytes FROM question_asset WHERE id = ANY(?)").param(assetIds.toArray(UUID[]::new))
                    .query((rs, n) -> new A(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getLong(3))).list();
            if (found.size() != assetIds.size() || found.stream().anyMatch(a -> !a.bank().equals(bankId))) {
                throw ApiException.badRequest("unknown_asset", "An image is not part of this question bank.");
            }
            long total = found.stream().mapToLong(A::bytes).sum();
            if (total > MAX_IMAGE_BYTES_PER_QUESTION) throw ApiException.badRequest("images_too_large", "Images of one question may total at most 15 MB.");
        }

        StringBuilder plain = new StringBuilder(content.plainText());
        List<String> optionNorm = new ArrayList<>();
        for (ContentFormat.Parsed o : body.optionContents()) {
            plain.append('\n').append(o.plainText());
            optionNorm.add(ContentFormat.normalize(o.plainText()));
        }
        optionNorm.sort(String::compareTo);
        String stemNorm = ContentFormat.normalize(content.plainText());
        String dupHash = Canonical.sha256(t.code() + "|" + stemNorm + "|" + String.join("|", optionNorm));
        String keyHash = keys.hash(key);
        Map<String, Object> reviewed = new LinkedHashMap<>();
        reviewed.put("type", t.code());
        reviewed.put("content", content.document());
        reviewed.put("body", body.body());
        reviewed.put("marking", marking);
        reviewed.put("marks", marks);
        reviewed.put("negativeMarks", neg);
        reviewed.put("taxonomy", Arrays.asList(in.subjectId(), in.topicId(), in.subtopicId(), in.difficultyId(), outcomes, tags, attributes, language));
        reviewed.put("key", keyHash);
        reviewed.put("explanation", explanation == null ? "" : Canonical.sha256(explanation));
        reviewed.put("images", images.stream().map(i -> List.of(i.assetId(), i.usage(), i.optionId(), i.altText() == null ? "" : i.altText(), i.decorative())).toList());
        String reviewHash = Canonical.sha256(Canonical.json(reviewed));
        return new Prepared(t, content.document(), body.body(), marking, key, explanation, marks, neg, in.subjectId(), in.topicId(), in.subtopicId(),
                in.difficultyId(), outcomes, tags, attributes, language, images, plain.toString(), stemNorm, dupHash, reviewHash, keyHash);
    }

    private void validateTaxonomy(DraftInput in) {
        if (in.subjectId() != null && !exists("SELECT 1 FROM question_subject WHERE id = ? AND status = 'ACTIVE'", in.subjectId())) throw bad("subjectId", "unknown subject");
        if (in.topicId() != null) {
            if (in.subjectId() == null || !exists("SELECT 1 FROM question_topic WHERE id = ? AND level = 1 AND status = 'ACTIVE' AND subject_id = ?", in.topicId(), in.subjectId())) {
                throw bad("topicId", "topic of the selected subject");
            }
        }
        if (in.subtopicId() != null) {
            if (in.topicId() == null || !exists("SELECT 1 FROM question_topic WHERE id = ? AND level = 2 AND status = 'ACTIVE' AND parent_id = ?", in.subtopicId(), in.topicId())) {
                throw bad("subtopicId", "subtopic of the selected topic");
            }
        }
        if (in.difficultyId() != null && !exists("SELECT 1 FROM difficulty_level WHERE id = ? AND status = 'ACTIVE'", in.difficultyId())) throw bad("difficultyId", "unknown difficulty");
        if (in.outcomeIds() != null) {
            for (UUID o : in.outcomeIds()) {
                if (in.subjectId() == null || !exists("SELECT 1 FROM learning_outcome WHERE id = ? AND status = 'ACTIVE' AND subject_id = ?", o, in.subjectId())) {
                    throw bad("outcomeIds", "learning outcomes of the selected subject");
                }
            }
        }
    }

    private boolean exists(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(Integer.class).optional().isPresent();
    }

    private Map<String, Object> validateAttributes(Map<String, Object> in) {
        Map<String, Object> out = new java.util.TreeMap<>();
        if (in == null) return out;
        record D(String type, List<String> allowed) {}
        Map<String, D> defs = new LinkedHashMap<>();
        jdbc.sql("SELECT key, type, allowed_values FROM question_attribute_definition WHERE status = 'ACTIVE'")
                .query((rs, n) -> { defs.put(rs.getString(1), new D(rs.getString(2), BankService.strings(rs.getArray(3)))); return null; }).list();
        for (Map.Entry<String, Object> e : in.entrySet()) {
            D d = defs.get(e.getKey());
            if (d == null) throw bad("attributes." + e.getKey(), "unknown metadata field");
            Object v = e.getValue();
            if (v == null || (v instanceof String s && s.isBlank())) continue;
            switch (d.type()) {
                case "NUMBER" -> { if (!(v instanceof Number)) throw bad("attributes." + e.getKey(), "number expected"); }
                case "BOOLEAN" -> { if (!(v instanceof Boolean)) throw bad("attributes." + e.getKey(), "true or false expected"); }
                case "DATE" -> { if (!(v instanceof String s) || !s.matches("^\\d{4}-\\d{2}-\\d{2}$")) throw bad("attributes." + e.getKey(), "date YYYY-MM-DD expected"); }
                case "CHOICE" -> { if (!d.allowed().contains(String.valueOf(v))) throw bad("attributes." + e.getKey(), "one of the allowed values"); }
                default -> { if (!(v instanceof String s) || s.length() > 200) throw bad("attributes." + e.getKey(), "text of at most 200 characters"); }
            }
            out.put(e.getKey(), v instanceof String s ? s.strip() : v);
        }
        return out;
    }

    private static ApiException bad(String field, String what) {
        return ApiException.badRequest("invalid_question", field + ": " + what + ".");
    }

    // ================================================================================================ create / edit

    @Transactional
    public Detail create(UUID bankId, DraftInput in) {
        QuestionAccess.BankRef bank = access.bank(bankId);
        access.require(QuestionAccess.AUTHOR, bank);
        if (!"ACTIVE".equals(bank.status())) throw ApiException.conflict("bank_archived", "This question bank is archived.");
        Prepared p = prepare(bankId, in);
        UUID qid = Ids.newId();
        UUID vid = Ids.newId();
        UUID me = access.me();
        UUID tenant = TenantContext.requireTenantId();
        jdbc.sql("INSERT INTO question (id, tenant_id, bank_id, current_version_id, created_by) VALUES (?, ?, ?, ?, ?)").params(qid, tenant, bankId, vid, me).update();
        insertVersion(vid, qid, 1, p, me, "MANUAL", null);
        writeChildren(vid, p);
        transition(vid, null, "DRAFT", "created");
        audit.recordSuccess("question.created", "question", qid.toString(), Map.of("bank", bankId.toString(), "type", p.type().code(), "versionId", vid.toString()));
        return detail(qid);
    }

    /** Creation used by the import commit (already validated; source IMPORT). */
    UUID createImported(UUID bankId, Prepared p, UUID importId) {
        UUID qid = Ids.newId();
        UUID vid = Ids.newId();
        UUID me = access.me();
        jdbc.sql("INSERT INTO question (id, tenant_id, bank_id, current_version_id, created_by) VALUES (?, ?, ?, ?, ?)").params(qid, TenantContext.requireTenantId(), bankId, vid, me).update();
        insertVersion(vid, qid, 1, p, me, "IMPORT", importId);
        writeChildren(vid, p);
        transition(vid, null, "DRAFT", "imported");
        audit.recordSuccess("question.created", "question", qid.toString(), Map.of("bank", bankId.toString(), "type", p.type().code(), "versionId", vid.toString(),
                "source", "IMPORT", "import", importId.toString()));
        return qid;
    }

    private void insertVersion(UUID vid, UUID qid, int no, Prepared p, UUID author, String source, UUID importId) {
        jdbc.sql("""
                INSERT INTO question_version (id, tenant_id, question_id, version_no, type_code, content, body, marking, marks, negative_marks, subject_id, topic_id,
                    subtopic_id, difficulty_id, outcome_ids, tags, attributes, language, plain_text, normalized_text, dup_hash, review_hash, author_id, source, import_id)
                VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, ?, ?, ?, ?, ?, ?::text[], CAST(? AS jsonb), ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(vid, TenantContext.requireTenantId(), qid, no, p.type().code(), json.writeValueAsString(p.content()), json.writeValueAsString(p.body()),
                        json.writeValueAsString(p.marking()), p.marks(), p.negativeMarks(), p.subjectId(), p.topicId(), p.subtopicId(), p.difficultyId(),
                        p.outcomeIds().toArray(UUID[]::new), pgArray(p.tags()), json.writeValueAsString(p.attributes()), p.language(), p.plainText(), p.normalized(),
                        p.dupHash(), p.reviewHash(), author, source, importId).update();
    }

    private void writeChildren(UUID vid, Prepared p) {
        keys.write(vid, p.key(), p.explanation());
        jdbc.sql("DELETE FROM question_version_asset WHERE version_id = ?").param(vid).update();
        for (ImageUse i : p.images()) {
            jdbc.sql("""
                    INSERT INTO question_version_asset (tenant_id, version_id, asset_id, usage, option_id, alt_text, decorative) VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT DO NOTHING""").params(TenantContext.requireTenantId(), vid, i.assetId(), i.usage(), i.optionId(), i.altText(), i.decorative()).update();
        }
    }

    record Cur(UUID questionId, UUID bankId, String questionStatus, UUID currentId, UUID approvedId, int versionNo, String state, UUID authorId, long questionVersion,
               long versionVersion, String reviewHash, String type) {}

    Cur cur(UUID questionId, boolean lock) {
        return jdbc.sql("""
                SELECT q.id, q.bank_id, q.status, q.current_version_id, q.approved_version_id, v.version_no, v.state, v.author_id, q.version, v.version, v.review_hash, v.type_code
                FROM question q JOIN question_version v ON v.id = q.current_version_id WHERE q.id = ?""" + (lock ? " FOR UPDATE OF q, v" : "")).param(questionId)
                .query((rs, n) -> new Cur(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getObject(4, UUID.class),
                        rs.getObject(5, UUID.class), rs.getInt(6), rs.getString(7), rs.getObject(8, UUID.class), rs.getLong(9), rs.getLong(10), rs.getString(11),
                        rs.getString(12))).optional().orElseThrow(() -> ApiException.notFound("Question"));
    }

    /** Edits the open draft. Editing a version in review returns it to draft and invalidates every review. */
    @Transactional
    public Detail updateDraft(UUID questionId, DraftInput in) {
        QuestionAccess.BankRef bank = access.bankOfQuestion(questionId);
        access.require(QuestionAccess.AUTHOR, bank);
        Cur c = cur(questionId, true);
        if (!List.of("DRAFT", "IN_REVIEW", "REVIEWED").contains(c.state())) throw ApiException.conflict("not_editable", "Approved versions cannot be changed. Create a new version.");
        if (!access.me().equals(c.authorId())) throw new AccessDeniedException("Only the author of this draft can edit it");
        if (in.version() == null || in.version() != c.versionVersion()) throw ApiException.conflict("concurrent_modification", "This question was changed. Reload and try again.");
        Prepared p = prepare(c.bankId(), in);
        boolean wasInReview = !"DRAFT".equals(c.state());
        if (p.reviewHash().equals(c.reviewHash()) && !wasInReview) return detail(questionId);
        jdbc.sql("""
                UPDATE question_version SET state = 'DRAFT', submitted_at = NULL, type_code = ?, content = CAST(? AS jsonb), body = CAST(? AS jsonb), marking = CAST(? AS jsonb),
                    marks = ?, negative_marks = ?, subject_id = ?, topic_id = ?, subtopic_id = ?, difficulty_id = ?, outcome_ids = ?, tags = ?::text[], attributes = CAST(? AS jsonb),
                    language = ?, plain_text = ?, normalized_text = ?, dup_hash = ?, review_hash = ?, updated_at = now(), version = version + 1
                WHERE id = ?""")
                .params(p.type().code(), json.writeValueAsString(p.content()), json.writeValueAsString(p.body()), json.writeValueAsString(p.marking()), p.marks(),
                        p.negativeMarks(), p.subjectId(), p.topicId(), p.subtopicId(), p.difficultyId(), p.outcomeIds().toArray(UUID[]::new), pgArray(p.tags()),
                        json.writeValueAsString(p.attributes()), p.language(), p.plainText(), p.normalized(), p.dupHash(), p.reviewHash(), c.currentId()).update();
        writeChildren(c.currentId(), p);
        touch(questionId);
        if (wasInReview) {
            transition(c.currentId(), c.state(), "DRAFT", "modified after review: reviews invalidated");
            audit.recordSuccess("question.reviews_invalidated", "question_version", c.currentId().toString(), Map.of("from", c.state()));
        }
        audit.recordSuccess("question.draft_updated", "question_version", c.currentId().toString(), Map.of("question", questionId.toString(), "versionNo", String.valueOf(c.versionNo())));
        return detail(questionId);
    }

    /** New draft version from the approved version; the approved version is not touched. */
    @Transactional
    public Detail newVersion(UUID questionId) {
        QuestionAccess.BankRef bank = access.bankOfQuestion(questionId);
        access.require(QuestionAccess.AUTHOR, bank);
        Cur c = cur(questionId, true);
        if (!"ACTIVE".equals(c.questionStatus())) throw ApiException.conflict("question_retired", "Retired questions cannot get new versions.");
        if (List.of("DRAFT", "IN_REVIEW", "REVIEWED").contains(c.state())) throw ApiException.conflict("open_version_exists", "Version " + c.versionNo() + " is still open.");
        UUID from = c.approvedId() == null ? c.currentId() : c.approvedId();
        int no = jdbc.sql("SELECT max(version_no) FROM question_version WHERE question_id = ?").param(questionId).query(Integer.class).single() + 1;
        UUID vid = Ids.newId();
        UUID me = access.me();
        jdbc.sql("""
                INSERT INTO question_version (id, tenant_id, question_id, version_no, type_code, type_schema_version, content, body, marking, marks, negative_marks,
                    subject_id, topic_id, subtopic_id, difficulty_id, outcome_ids, tags, attributes, language, plain_text, normalized_text, dup_hash, review_hash, author_id, source)
                SELECT ?, tenant_id, question_id, ?, type_code, type_schema_version, content, body, marking, marks, negative_marks, subject_id, topic_id, subtopic_id,
                       difficulty_id, outcome_ids, tags, attributes, language, plain_text, normalized_text, dup_hash, review_hash, ?, 'MANUAL'
                FROM question_version WHERE id = ?""").params(vid, no, me, from).update();
        keys.copy(from, vid);
        jdbc.sql("""
                INSERT INTO question_version_asset (tenant_id, version_id, asset_id, usage, option_id, alt_text, decorative)
                SELECT tenant_id, ?, asset_id, usage, option_id, alt_text, decorative FROM question_version_asset WHERE version_id = ?""").params(vid, from).update();
        jdbc.sql("UPDATE question SET current_version_id = ?, updated_at = now(), version = version + 1 WHERE id = ?").params(vid, questionId).update();
        transition(vid, null, "DRAFT", "new version from version " + c.versionNo());
        audit.recordSuccess("question.version_created", "question_version", vid.toString(), Map.of("question", questionId.toString(), "versionNo", String.valueOf(no),
                "from", from.toString()));
        return detail(questionId);
    }

    // ================================================================================================ workflow

    @Transactional
    public Detail submit(UUID questionId, String reason) {
        QuestionAccess.BankRef bank = access.bankOfQuestion(questionId);
        access.require(QuestionAccess.AUTHOR, bank);
        Cur c = cur(questionId, true);
        if (!"DRAFT".equals(c.state())) throw ApiException.conflict("not_draft", "Only a draft can be submitted.");
        if (!"ACTIVE".equals(c.questionStatus())) throw ApiException.conflict("question_retired", "This question is retired.");
        if (!access.me().equals(c.authorId())) throw new AccessDeniedException("Only the author can submit this draft");
        if (keys.keyHash(c.currentId()).isEmpty()) throw ApiException.conflict("answer_key_missing", "Add the answer key before submitting.");
        List<String> notReady = jdbc.sql("""
                SELECT a.scan_status FROM question_version_asset va JOIN question_asset a ON a.id = va.asset_id
                WHERE va.version_id = ? AND a.scan_status NOT IN ('CLEAN', 'NOT_SCANNED_DEV')""").param(c.currentId()).query(String.class).list();
        if (!notReady.isEmpty()) throw ApiException.conflict("images_not_scanned", "Every image must pass the malware scan before submission.");
        List<String> missing = jdbc.sql("""
                SELECT d.label FROM question_attribute_definition d, question_version v
                WHERE v.id = ? AND d.status = 'ACTIVE' AND d.required_for_approval AND NOT jsonb_exists(v.attributes, d.key)""").param(c.currentId()).query(String.class).list();
        if (!missing.isEmpty()) throw ApiException.conflict("metadata_missing", "Required metadata missing: " + String.join(", ", missing) + ".");
        BankService.Bank b = banks.find(c.bankId());
        jdbc.sql("""
                UPDATE question_version SET state = 'IN_REVIEW', submitted_at = now(), first_submitted_at = coalesce(first_submitted_at, now()),
                    policy_snapshot = CAST(? AS jsonb), version = version + 1 WHERE id = ?""").params(json.writeValueAsString(b.effectivePolicy()), c.currentId()).update();
        touch(questionId);
        transition(c.currentId(), "DRAFT", "IN_REVIEW", reason);
        audit.recordSuccess("question.submitted", "question_version", c.currentId().toString(), Map.of("question", questionId.toString(),
                "policy", b.effectivePolicy().approvalPolicy() + "/" + b.effectivePolicy().requiredReviews()));
        return detail(questionId);
    }

    @Transactional
    public Detail review(UUID questionId, boolean approveContent, Map<String, Object> checklist, String comment) {
        QuestionAccess.BankRef bank = access.bankOfQuestion(questionId);
        access.require(QuestionAccess.REVIEW, bank);
        Cur c = cur(questionId, true);
        UUID me = access.me();
        if (me.equals(c.authorId())) {
            audit.recordDenied("question.self_review_denied", Map.of("questionId", questionId.toString()));
            throw ApiException.conflict("self_review", "You wrote this version, so someone else must review it.");
        }
        if (!List.of("IN_REVIEW", "REVIEWED").contains(c.state())) throw ApiException.conflict("not_in_review", "This version is not in review.");
        boolean already = jdbc.sql("SELECT EXISTS (SELECT 1 FROM question_review WHERE version_id = ? AND reviewer_id = ? AND invalidated_at IS NULL AND decision = 'REVIEWED')")
                .params(c.currentId(), me).query(Boolean.class).single();
        if (already && approveContent) throw ApiException.conflict("already_reviewed", "You have already reviewed this version.");
        String text = comment == null || comment.isBlank() ? null : comment.strip();
        if (!approveContent && text == null) throw ApiException.badRequest("reason_required", "Explain which changes are needed.");
        if (text != null && text.length() > 2000) throw ApiException.badRequest("comment_too_long", "At most 2000 characters.");
        Map<String, Object> cl = checklist == null ? Map.of() : checklist;
        UUID rid = Ids.newId();
        try {
            jdbc.sql("""
                    INSERT INTO question_review (id, tenant_id, version_id, reviewer_id, decision, reviewed_hash, checklist, comment)
                    VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)""").params(rid, TenantContext.requireTenantId(), c.currentId(), me,
                    approveContent ? "REVIEWED" : "CHANGES_REQUESTED", c.reviewHash(), json.writeValueAsString(cl), text).update();
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("review_refused", "This review is not allowed for this version.");
        }
        if (!approveContent) {
            returnToDraft(c, "changes requested: " + text);
        } else {
            BankService.Policy pol = banks.find(c.bankId()).effectivePolicy();
            int valid = validReviews(c.currentId(), c.reviewHash(), c.authorId());
            if ("IN_REVIEW".equals(c.state()) && valid >= pol.requiredReviews()) {
                jdbc.sql("UPDATE question_version SET state = 'REVIEWED', version = version + 1 WHERE id = ?").param(c.currentId()).update();
                transition(c.currentId(), "IN_REVIEW", "REVIEWED", valid + " of " + pol.requiredReviews() + " required review(s)");
            }
        }
        touch(questionId);
        audit.recordSuccess(approveContent ? "question.reviewed" : "question.changes_requested", "question_version", c.currentId().toString(),
                text == null ? Map.of("review", rid.toString()) : Map.of("review", rid.toString(), "comment", text.length() > 300 ? text.substring(0, 300) : text));
        return detail(questionId);
    }

    @Transactional
    public Detail requestChanges(UUID questionId, String reason) {
        QuestionAccess.BankRef bank = access.bankOfQuestion(questionId);
        access.require(QuestionAccess.APPROVE, bank);
        Cur c = cur(questionId, true);
        if (access.me().equals(c.authorId())) throw ApiException.conflict("self_review", "You wrote this version.");
        if (!List.of("IN_REVIEW", "REVIEWED").contains(c.state())) throw ApiException.conflict("not_in_review", "This version is not in review.");
        if (reason == null || reason.isBlank()) throw ApiException.badRequest("reason_required", "Explain which changes are needed.");
        returnToDraft(c, "changes requested by approver: " + reason.strip());
        touch(questionId);
        audit.recordSuccess("question.changes_requested", "question_version", c.currentId().toString(), Map.of("by", "approver", "reason", reason.strip()));
        return detail(questionId);
    }

    private void returnToDraft(Cur c, String reason) {
        jdbc.sql("UPDATE question_version SET state = 'DRAFT', submitted_at = NULL, version = version + 1 WHERE id = ?").param(c.currentId()).update();
        transition(c.currentId(), c.state(), "DRAFT", reason.length() > 500 ? reason.substring(0, 500) : reason);
    }

    int validReviews(UUID versionId, String hash, UUID author) {
        return jdbc.sql("""
                SELECT count(DISTINCT reviewer_id) FROM question_review WHERE version_id = ? AND decision = 'REVIEWED' AND invalidated_at IS NULL
                AND reviewed_hash = ? AND reviewer_id <> ?""").params(versionId, hash, author).query(Integer.class).single();
    }

    @Transactional
    public Detail approve(UUID questionId, String reason) {
        QuestionAccess.BankRef bank = access.bankOfQuestion(questionId);
        access.require(QuestionAccess.APPROVE, bank);
        Cur c = cur(questionId, true);
        UUID me = access.me();
        if (me.equals(c.authorId())) {
            audit.recordDenied("question.self_approval_denied", Map.of("questionId", questionId.toString()));
            throw ApiException.conflict("self_approval", "You wrote this version, so someone else must approve it.");
        }
        if (!"REVIEWED".equals(c.state())) throw ApiException.conflict("not_reviewed", "Only a reviewed version can be approved.");
        BankService.Policy pol = banks.find(c.bankId()).effectivePolicy();
        boolean reviewedByMe = jdbc.sql("SELECT EXISTS (SELECT 1 FROM question_review WHERE version_id = ? AND reviewer_id = ? AND invalidated_at IS NULL AND decision = 'REVIEWED')")
                .params(c.currentId(), me).query(Boolean.class).single();
        if (pol.approverDistinct() && reviewedByMe) {
            audit.recordDenied("question.approval_policy_denied", Map.of("questionId", questionId.toString(), "rule", "approver_reviewed"));
            throw ApiException.conflict("approver_reviewed", "This bank requires an approver who did not review the question.");
        }
        if (validReviews(c.currentId(), c.reviewHash(), c.authorId()) < pol.requiredReviews()) {
            throw ApiException.conflict("reviews_missing", "This bank requires " + pol.requiredReviews() + " valid review(s) of the current content.");
        }
        try {
            jdbc.sql("UPDATE question_version SET state = 'APPROVED', approved_by = ?, approved_at = now(), version = version + 1 WHERE id = ?").params(me, c.currentId()).update();
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("approval_policy_not_met", "The approval policy of this bank is not met.");
        }
        transition(c.currentId(), "REVIEWED", "APPROVED", reason);
        if (c.approvedId() != null) {
            jdbc.sql("UPDATE question_version SET state = 'SUPERSEDED', version = version + 1 WHERE id = ? AND state = 'APPROVED'").param(c.approvedId()).update();
            int prevNo = jdbc.sql("SELECT version_no FROM question_version WHERE id = ?").param(c.approvedId()).query(Integer.class).single();
            transition(c.approvedId(), "APPROVED", "SUPERSEDED", "superseded by version " + c.versionNo());
            audit.recordSuccess("question.version_superseded", "question_version", c.approvedId().toString(), Map.of("versionNo", String.valueOf(prevNo),
                    "by", c.currentId().toString()));
        }
        jdbc.sql("UPDATE question SET approved_version_id = ?, updated_at = now(), version = version + 1 WHERE id = ?").params(c.currentId(), questionId).update();
        audit.recordSuccess("question.approved", "question_version", c.currentId().toString(), Map.of("question", questionId.toString(),
                "versionNo", String.valueOf(c.versionNo()), "policy", pol.approvalPolicy() + "/" + pol.requiredReviews() + (pol.approverDistinct() ? "/distinct" : "")));
        return detail(questionId);
    }

    @Transactional
    public Detail retire(UUID questionId, String reason) {
        QuestionAccess.BankRef bank = access.bankOfQuestion(questionId);
        access.require(QuestionAccess.APPROVE, bank);
        if (reason == null || reason.isBlank()) throw ApiException.badRequest("reason_required", "Give a reason for retiring the question.");
        Cur c = cur(questionId, true);
        if (c.approvedId() == null || !"ACTIVE".equals(c.questionStatus())) throw ApiException.conflict("not_retirable", "Only an approved, active question can be retired.");
        jdbc.sql("UPDATE question_version SET state = 'RETIRED', retired_by = ?, retired_at = now(), version = version + 1 WHERE id = ?").params(access.me(), c.approvedId()).update();
        transition(c.approvedId(), "APPROVED", "RETIRED", reason.strip());
        jdbc.sql("UPDATE question SET status = 'RETIRED', updated_at = now(), version = version + 1 WHERE id = ?").param(questionId).update();
        audit.recordSuccess("question.retired", "question", questionId.toString(), Map.of("version", c.approvedId().toString(), "reason", reason.strip()));
        return detail(questionId);
    }

    // ================================================================================================ reading

    @Transactional(readOnly = true)
    public Detail get(UUID questionId) {
        access.bankOfQuestion(questionId);
        return detail(questionId);
    }

    Detail detail(UUID questionId) {
        Cur c = cur(questionId, false);
        BankService.Bank bank = banks.find(c.bankId());
        Version current = version(c.currentId());
        Version approved = c.approvedId() == null || c.approvedId().equals(c.currentId()) ? null : version(c.approvedId());
        List<VersionSummary> versions = jdbc.sql("""
                SELECT v.id, v.version_no, v.state, u.display_name, v.created_at, v.approved_at FROM question_version v
                LEFT JOIN user_account u ON u.id = v.author_id WHERE v.question_id = ? ORDER BY v.version_no DESC""").param(questionId)
                .query((rs, n) -> new VersionSummary(rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3), rs.getString(4), ts(rs, "created_at"), ts(rs, "approved_at"))).list();
        UUID me = access.me();
        UUID unit = bank.orgUnitId();
        boolean author = me.equals(c.authorId());
        boolean open = List.of("DRAFT", "IN_REVIEW", "REVIEWED").contains(c.state());
        boolean active = "ACTIVE".equals(c.questionStatus());
        boolean reviewedByMe = current.reviews().stream().anyMatch(r -> r.valid() && "REVIEWED".equals(r.decision()) && me.equals(r.reviewerId()));
        String blocked = null;
        boolean canApprove = active && "REVIEWED".equals(c.state()) && !author && access.can(QuestionAccess.APPROVE, unit);
        if (canApprove && bank.effectivePolicy().approverDistinct() && reviewedByMe) {
            canApprove = false;
            blocked = "This bank needs an approver who did not review the question.";
        }
        if ("REVIEWED".equals(c.state()) && author) blocked = "You wrote this version: someone else must approve it.";
        Actions actions = new Actions(
                active && open && author && access.can(QuestionAccess.AUTHOR, unit),
                active && "DRAFT".equals(c.state()) && author && access.can(QuestionAccess.AUTHOR, unit),
                active && List.of("IN_REVIEW", "REVIEWED").contains(c.state()) && !author && !reviewedByMe && access.can(QuestionAccess.REVIEW, unit),
                active && List.of("IN_REVIEW", "REVIEWED").contains(c.state()) && !author && (access.can(QuestionAccess.REVIEW, unit) || access.can(QuestionAccess.APPROVE, unit)),
                canApprove,
                active && c.approvedId() != null && access.can(QuestionAccess.APPROVE, unit),
                active && !open && access.can(QuestionAccess.AUTHOR, unit),
                current.keyVisible(), blocked);
        return new Detail(questionId, c.bankId(), bank.name(), c.questionStatus(), current, approved, versions, bank.effectivePolicy(), actions, c.questionVersion());
    }

    @Transactional(readOnly = true)
    public Version versionOf(UUID questionId, int no) {
        access.bankOfQuestion(questionId);
        UUID vid = jdbc.sql("SELECT id FROM question_version WHERE question_id = ? AND version_no = ?").params(questionId, no).query(UUID.class).optional()
                .orElseThrow(() -> ApiException.notFound("Question version"));
        return version(vid);
    }

    @SuppressWarnings("unchecked")
    Version version(UUID vid) {
        List<Review> reviews = jdbc.sql("""
                SELECT r.id, r.reviewer_id, u.display_name, r.decision, r.checklist::text, r.comment, r.created_at, r.invalidated_at, r.invalidated_reason, r.reviewed_hash
                FROM question_review r LEFT JOIN user_account u ON u.id = r.reviewer_id WHERE r.version_id = ? ORDER BY r.created_at""").param(vid)
                .query((rs, n) -> new Review(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                        json.readValue(rs.getString(5), Map.class), rs.getString(6), ts(rs, "created_at"), rs.getTimestamp("invalidated_at") == null,
                        rs.getString(9))).list();
        List<ImageUse> images = jdbc.sql("""
                SELECT va.asset_id, va.usage, va.option_id, va.alt_text, va.decorative, a.scan_status FROM question_version_asset va
                JOIN question_asset a ON a.id = va.asset_id WHERE va.version_id = ? ORDER BY va.usage DESC, va.option_id""").param(vid)
                .query((rs, n) -> new ImageUse(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5), rs.getString(6))).list();
        boolean keyVisible = keys.mayRead(vid);
        return jdbc.sql("""
                SELECT v.*, au.display_name AS author_name, ap.display_name AS approver_name FROM question_version v
                LEFT JOIN user_account au ON au.id = v.author_id LEFT JOIN user_account ap ON ap.id = v.approved_by WHERE v.id = ?""").param(vid)
                .query((rs, n) -> {
                    String hash = rs.getString("review_hash");
                    UUID author = rs.getObject("author_id", UUID.class);
                    int valid = (int) reviews.stream().filter(r -> r.valid() && "REVIEWED".equals(r.decision()) && !r.reviewerId().equals(author)).map(Review::reviewerId).distinct().count();
                    return new Version(vid, rs.getInt("version_no"), rs.getString("state"), rs.getString("type_code"),
                            json.readValue(rs.getString("content"), Map.class), json.readValue(rs.getString("body"), Map.class), json.readValue(rs.getString("marking"), Map.class),
                            rs.getBigDecimal("marks"), rs.getBigDecimal("negative_marks"), rs.getObject("subject_id", UUID.class), rs.getObject("topic_id", UUID.class),
                            rs.getObject("subtopic_id", UUID.class), rs.getObject("difficulty_id", UUID.class), uuids(rs.getArray("outcome_ids")),
                            BankService.strings(rs.getArray("tags")), json.readValue(rs.getString("attributes"), Map.class), rs.getString("language"), images, author,
                            rs.getString("author_name"), ts(rs, "created_at"), ts(rs, "updated_at"), ts(rs, "submitted_at"), rs.getObject("approved_by", UUID.class),
                            rs.getString("approver_name"), ts(rs, "approved_at"), ts(rs, "retired_at"), rs.getString("source"), reviews, valid, hash.substring(0, 12),
                            rs.getLong("version"), keyVisible);
                }).single();
    }

    @Transactional(readOnly = true)
    public Page search(UUID bankId, Filters f, int page, int size) {
        access.bank(bankId);
        StringBuilder where = new StringBuilder(" WHERE q.bank_id = :bank");
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("bank", bankId);
        String join = f.approvedOnly() ? "q.approved_version_id" : "q.current_version_id";
        if (f.state() != null) { where.append(" AND v.state = :state"); p.put("state", f.state()); }
        if (f.type() != null) { where.append(" AND v.type_code = :type"); p.put("type", f.type()); }
        if (f.status() != null) { where.append(" AND q.status = :status"); p.put("status", f.status()); }
        if (f.subjectId() != null) { where.append(" AND v.subject_id = :subject"); p.put("subject", f.subjectId()); }
        if (f.topicId() != null) { where.append(" AND v.topic_id = :topic"); p.put("topic", f.topicId()); }
        if (f.subtopicId() != null) { where.append(" AND v.subtopic_id = :subtopic"); p.put("subtopic", f.subtopicId()); }
        if (f.difficultyId() != null) { where.append(" AND v.difficulty_id = :difficulty"); p.put("difficulty", f.difficultyId()); }
        if (f.outcomeId() != null) { where.append(" AND :outcome = ANY (v.outcome_ids)"); p.put("outcome", f.outcomeId()); }
        if (f.tag() != null) { where.append(" AND EXISTS (SELECT 1 FROM unnest(v.tags) t WHERE lower(t) = lower(:tag))"); p.put("tag", f.tag()); }
        if (f.attributeKey() != null && f.attributeValue() != null) {
            where.append(" AND v.attributes ->> :akey = :avalue");
            p.put("akey", f.attributeKey());
            p.put("avalue", f.attributeValue());
        }
        if (f.q() != null && !f.q().isBlank()) {
            where.append(" AND (v.search_vector @@ websearch_to_tsquery('simple', :q) OR v.plain_text ILIKE :like)");
            p.put("q", f.q().strip());
            p.put("like", "%" + f.q().strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        String from = " FROM question q JOIN question_version v ON v.id = " + join
                + " LEFT JOIN question_subject s ON s.id = v.subject_id LEFT JOIN difficulty_level d ON d.id = v.difficulty_id"
                + " LEFT JOIN user_account u ON u.id = v.author_id LEFT JOIN question_version av ON av.id = q.approved_version_id";
        var count = jdbc.sql("SELECT count(*)" + from + where);
        for (var e : p.entrySet()) count = count.param(e.getKey(), e.getValue());
        long total = count.query(Long.class).single();
        var query = jdbc.sql("SELECT q.id, v.id AS vid, v.version_no, v.state, v.type_code, left(v.plain_text, 200) AS excerpt, s.name AS subject, d.label AS difficulty, v.tags,"
                + " v.marks, v.negative_marks, av.version_no AS approved_no, q.status, u.display_name AS author, q.updated_at" + from + where
                + " ORDER BY q.updated_at DESC, q.id LIMIT :size OFFSET :offset");
        for (var e : p.entrySet()) query = query.param(e.getKey(), e.getValue());
        int sz = Math.max(1, Math.min(100, size));
        List<Summary> items = query.param("size", sz).param("offset", Math.max(0, page) * sz)
                .query((rs, n) -> new Summary(rs.getObject("id", UUID.class), rs.getObject("vid", UUID.class), rs.getInt("version_no"), rs.getString("state"),
                        rs.getString("type_code"), rs.getString("excerpt"), rs.getString("subject"), rs.getString("difficulty"), BankService.strings(rs.getArray("tags")),
                        rs.getBigDecimal("marks"), rs.getBigDecimal("negative_marks"), (Integer) rs.getObject("approved_no"), rs.getString("status"),
                        rs.getString("author"), ts(rs, "updated_at"))).list();
        return new Page(items, total, page, sz);
    }

    @Transactional(readOnly = true)
    public List<QueueItem> queue(String stage) {
        boolean approval = "approval".equals(stage);
        UUID me = access.me();
        return jdbc.sql("""
                SELECT q.id, v.id AS vid, v.version_no, b.name AS bank, b.id AS bank_id, b.org_unit_id, v.state, v.type_code, left(v.plain_text, 160) AS excerpt,
                       u.display_name AS author, v.author_id, v.submitted_at, e.required_reviews, e.approver_distinct,
                       (SELECT count(DISTINCT r.reviewer_id) FROM question_review r WHERE r.version_id = v.id AND r.decision = 'REVIEWED' AND r.invalidated_at IS NULL
                          AND r.reviewed_hash = v.review_hash AND r.reviewer_id <> v.author_id) AS valid_reviews,
                       EXISTS (SELECT 1 FROM question_review r WHERE r.version_id = v.id AND r.reviewer_id = :me AND r.decision = 'REVIEWED' AND r.invalidated_at IS NULL) AS mine
                FROM question q JOIN question_version v ON v.id = q.current_version_id JOIN question_bank b ON b.id = q.bank_id
                CROSS JOIN LATERAL question_effective_policy(b.id) e LEFT JOIN user_account u ON u.id = v.author_id
                WHERE q.status = 'ACTIVE' AND v.author_id <> :me AND v.state = ANY (:states)
                ORDER BY v.submitted_at""").param("me", me).param("states", approval ? new String[] {"REVIEWED"} : new String[] {"IN_REVIEW"})
                .query((rs, n) -> {
                    UUID unit = rs.getObject("org_unit_id", UUID.class);
                    boolean mine = rs.getBoolean("mine");
                    boolean ok = approval
                            ? access.can(QuestionAccess.APPROVE, unit) && !(rs.getBoolean("approver_distinct") && mine)
                            : access.can(QuestionAccess.REVIEW, unit) && !mine;
                    return ok ? new QueueItem(rs.getObject("id", UUID.class), rs.getObject("vid", UUID.class), rs.getInt("version_no"), rs.getString("bank"),
                            rs.getString("state"), rs.getString("type_code"), rs.getString("excerpt"), rs.getString("author"), ts(rs, "submitted_at"),
                            rs.getInt("valid_reviews"), rs.getInt("required_reviews")) : null;
                }).list().stream().filter(java.util.Objects::nonNull).toList();
    }

    @Transactional(readOnly = true)
    public List<Transition> transitions(UUID questionId) {
        access.bankOfQuestion(questionId);
        return jdbc.sql("""
                SELECT v.version_no, t.from_state, t.to_state, u.display_name, t.reason, t.at FROM question_transition t
                JOIN question_version v ON v.id = t.version_id LEFT JOIN user_account u ON u.id = t.actor_id
                WHERE v.question_id = ? ORDER BY t.at, t.id""").param(questionId)
                .query((rs, n) -> new Transition(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), ts(rs, "at"))).list();
    }

    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true)
    public List<HistoryEvent> history(UUID questionId) {
        access.bankOfQuestion(questionId);
        List<String> ids = new ArrayList<>(jdbc.sql("SELECT id::text FROM question_version WHERE question_id = ?").param(questionId).query(String.class).list());
        ids.add(questionId.toString());
        return jdbc.sql("""
                SELECT e.action, coalesce(u.display_name, e.actor_subject) AS actor, e.occurred_at, e.details::text FROM audit_event e
                LEFT JOIN user_account u ON u.id = e.actor_id
                WHERE e.resource_type IN ('question', 'question_version') AND e.resource_id = ANY (?) ORDER BY e.seq""").param(ids.toArray(String[]::new))
                .query((rs, n) -> new HistoryEvent(rs.getString(1), rs.getString(2), ts(rs, "occurred_at"), json.readValue(rs.getString(4), Map.class))).list();
    }

    @Transactional
    public List<MarkingResult> markingPreview(UUID questionId, int versionNo, List<Object> responses) {
        Version v = versionOf(questionId, versionNo);
        Optional<AnswerKeyStore.Plain> key = keys.read(v.id(), AnswerKeyStore.Purpose.MARKING_PREVIEW);
        if (key.isEmpty()) throw ApiException.conflict("answer_key_missing", "This version has no answer key.");
        QuestionType t = type(v.type());
        List<MarkingResult> out = new ArrayList<>();
        for (Object r : responses == null ? List.of() : responses.subList(0, Math.min(responses.size(), 50))) {
            QuestionType.Grade g = t.mark(v.body(), key.get().key(), v.marking(), v.marks(), v.negativeMarks(), r);
            out.add(new MarkingResult(r, g.outcome().name(), g.awarded(), g.detail()));
        }
        return out;
    }

    // ================================================================================================ helpers

    void transition(UUID versionId, String from, String to, String reason) {
        jdbc.sql("INSERT INTO question_transition (id, tenant_id, version_id, from_state, to_state, actor_id, reason) VALUES (?, ?, ?, ?, ?, ?, ?)")
                .params(Ids.newId(), TenantContext.requireTenantId(), versionId, from, to, access.me(),
                        reason == null || reason.isBlank() ? null : (reason.length() > 500 ? reason.substring(0, 500) : reason.strip())).update();
    }

    private void touch(UUID questionId) {
        jdbc.sql("UPDATE question SET updated_at = now(), version = version + 1 WHERE id = ?").param(questionId).update();
    }

    static String pgArray(List<String> values) {
        return "{" + String.join(",", values.stream().map(s -> "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"").toList()) + "}";
    }

    private static List<UUID> uuids(java.sql.Array a) throws SQLException {
        return a == null ? List.of() : Arrays.asList((UUID[]) a.getArray());
    }

    static Instant ts(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }
}
