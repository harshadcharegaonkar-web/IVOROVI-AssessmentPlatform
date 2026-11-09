package com.ivorovi.assessments.internal;

import com.ivorovi.platform.audit.AuditLog;
import com.ivorovi.platform.ids.Ids;
import com.ivorovi.platform.outbox.Outbox;
import com.ivorovi.platform.security.Authz;
import com.ivorovi.platform.security.CurrentUser;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.platform.web.ApiException;
import com.ivorovi.questions.ApprovedQuestions;
import com.ivorovi.questions.QuestionReferences;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Assessment definitions and their lifecycle: DRAFT → IN_REVIEW → PUBLISHED (→ SUPERSEDED). Governance follows the
 * purpose (Q-2.2-2): high-stakes purposes need three different people (author, reviewer, publisher). Every rule is
 * enforced here and again by the database triggers of migration V202611130010.
 */
@Service
public class AssessmentService {

    public static final String READ = "assessment.read";
    public static final String AUTHOR = "assessment.author";
    public static final String AUTHOR_INTERNAL = "assessment.author.internal";
    public static final String REVIEW = "assessment.review";
    public static final String PUBLISH = "assessment.publish";
    static final Set<String> PURPOSES = Set.of("ADMISSION", "INTERNAL", "SEMESTER", "PLACEMENT", "APTITUDE", "PRACTICE");
    static final Set<String> INTERNAL_PURPOSES = Set.of("INTERNAL", "PRACTICE");
    static final int MAX_QUESTIONS = 500;

    // ------------------------------------------------------------------------------------------------ API types

    public record CreateInput(UUID orgUnitId, String code, String purpose, String title, String description, Integer durationMinutes) {}

    public record ItemInput(UUID questionVersionId, BigDecimal marks, BigDecimal negativeMarks) {}

    public record RuleInput(String label, UUID bankId, UUID subjectId, UUID topicId, UUID difficultyId, String questionType, int count, BigDecimal marks,
                            BigDecimal negativeMarks) {}

    public record DraftInput(String title, String description, String instructions, Integer durationMinutes, BigDecimal declaredTotalMarks,
                             String negativeMarking, Boolean shuffleQuestions, Boolean shuffleOptions, List<ItemInput> items, List<RuleInput> rules, Long version) {}

    @Schema(name = "AssessmentItem")
    public record Item(UUID id, int ordinal, UUID questionVersionId, UUID questionId, UUID bankId, String type, String excerpt, String subjectName,
                       String topicName, String difficultyLabel, BigDecimal marks, BigDecimal negativeMarks, String questionState, boolean usable) {}

    @Schema(name = "AssessmentRule")
    public record Rule(UUID id, int ordinal, String label, UUID bankId, String bankName, UUID subjectId, UUID topicId, UUID difficultyId, String questionType,
                       int count, BigDecimal marks, BigDecimal negativeMarks, int poolSize, boolean poolFrozen) {}

    @Schema(name = "AssessmentReview")
    public record Review(UUID id, UUID reviewerId, String reviewerName, String decision, String comment, Instant at, boolean valid, String invalidatedReason) {}

    @Schema(name = "AssessmentVersion")
    public record Version(UUID id, int versionNo, String state, String governance, String title, String description, String instructions, int durationMinutes,
                          BigDecimal declaredTotalMarks, BigDecimal totalMarks, String negativeMarking, boolean shuffleQuestions, boolean shuffleOptions,
                          List<Item> items, List<Rule> rules, List<Review> reviews, UUID authorId, String authorName, UUID publishedBy, String publishedByName,
                          Instant publishedAt, Instant createdAt, long version) {}

    @Schema(name = "AssessmentVersionSummary")
    public record VersionSummary(UUID id, int versionNo, String state, String authorName, Instant createdAt, Instant publishedAt) {}

    @Schema(name = "AssessmentActions")
    public record Actions(boolean edit, boolean submit, boolean review, boolean publish, boolean newVersion, String blockedReason) {}

    @Schema(name = "AssessmentIssue")
    public record Issue(String code, String message) {}

    @Schema(name = "AssessmentValidation")
    public record Validation(List<Issue> errors, List<Issue> warnings, int questionCount, BigDecimal totalMarks, Map<String, Integer> byDifficulty) {
        public boolean ok() {
            return errors.isEmpty();
        }
    }

    @Schema(name = "AssessmentDetail")
    public record Detail(UUID id, String code, String purpose, UUID orgUnitId, String orgUnitName, String status, Version current, Version published,
                         List<VersionSummary> versions, Actions actions, List<String> warnings, long version) {}

    @Schema(name = "AssessmentSummary")
    public record Summary(UUID id, String code, String purpose, String title, UUID orgUnitId, String orgUnitName, int versionNo, String state,
                         Integer publishedVersionNo, BigDecimal totalMarks, int durationMinutes, String authorName, Instant updatedAt) {}

    @Schema(name = "AssessmentTransition")
    public record Transition(int versionNo, String fromState, String toState, String actorName, String reason, Instant at) {}

    @Schema(name = "AssessmentHistoryEvent")
    public record HistoryEvent(String action, String actorName, Instant at, Map<String, Object> details) {}

    @Schema(name = "AssessmentQueueItem")
    public record QueueItem(UUID assessmentId, UUID versionId, int versionNo, String code, String title, String purpose, String state, String authorName,
                            Instant submittedAt, int validReviews) {}

    record Head(UUID id, UUID orgUnitId, String purpose, String code, String status, UUID currentId, UUID publishedId, long version) {}

    record Cur(UUID id, int versionNo, String state, String governance, UUID authorId, String reviewHash, long version) {}

    private final JdbcClient jdbc;
    private final Authz authz;
    private final AuditLog audit;
    private final Outbox outbox;
    private final ApprovedQuestions questions;
    private final QuestionReferences references;
    private final JsonMapper json;

    AssessmentService(JdbcClient jdbc, Authz authz, AuditLog audit, Outbox outbox, ApprovedQuestions questions, QuestionReferences references, JsonMapper json) {
        this.jdbc = jdbc;
        this.authz = authz;
        this.audit = audit;
        this.outbox = outbox;
        this.questions = questions;
        this.references = references;
        this.json = json;
    }

    // ================================================================================================ access

    UUID me() {
        return CurrentUser.require().userId();
    }

    boolean can(String permission, UUID unit) {
        return authz.hasInOrgUnit(permission, unit);
    }

    /** Q-2.2-6: full authors any purpose; holders of assessment.author.internal only INTERNAL and PRACTICE. */
    boolean canAuthor(String purpose, UUID unit) {
        return can(AUTHOR, unit) || (INTERNAL_PURPOSES.contains(purpose) && can(AUTHOR_INTERNAL, unit));
    }

    Head head(UUID id, boolean lock) {
        Head h = jdbc.sql("SELECT id, org_unit_id, purpose, code, status, current_version_id, published_version_id, version FROM assessment WHERE id = ?"
                        + (lock ? " FOR UPDATE" : "")).param(id)
                .query((rs, n) -> new Head(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getObject(6, UUID.class), rs.getObject(7, UUID.class), rs.getLong(8))).optional()
                .orElseThrow(() -> ApiException.notFound("Assessment"));
        if (!can(READ, h.orgUnitId())) throw ApiException.notFound("Assessment");
        return h;
    }

    Cur cur(UUID versionId, boolean lock) {
        return jdbc.sql("SELECT id, version_no, state, governance, author_id, review_hash, version FROM assessment_version WHERE id = ?" + (lock ? " FOR UPDATE" : ""))
                .param(versionId).query((rs, n) -> new Cur(rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3), rs.getString(4),
                        rs.getObject(5, UUID.class), rs.getString(6), rs.getLong(7))).single();
    }

    static String governanceFor(String purpose) {
        return Set.of("ADMISSION", "SEMESTER", "PLACEMENT", "APTITUDE").contains(purpose) ? "HIGH_STAKES" : "STANDARD";
    }

    // ================================================================================================ create / edit

    @Transactional
    public Detail create(CreateInput in) {
        String purpose = in.purpose() == null ? "" : in.purpose().strip().toUpperCase();
        if (!PURPOSES.contains(purpose)) throw ApiException.badRequest("invalid_purpose", "Choose a purpose: admission, internal, semester or placement.");
        if (in.orgUnitId() != null && jdbc.sql("SELECT count(*) FROM org_unit WHERE id = ?").param(in.orgUnitId()).query(Long.class).single() == 0) {
            throw ApiException.badRequest("invalid_org_unit", "Choose an institution of your organization.");
        }
        if (!canAuthor(purpose, in.orgUnitId())) {
            throw new AccessDeniedException(INTERNAL_PURPOSES.contains(purpose) ? "Missing permission " + AUTHOR_INTERNAL
                    : "Missing permission " + AUTHOR + " (faculty may author internal and practice assessments only)");
        }
        String code = in.code() == null ? "" : in.code().strip();
        if (!code.matches("^[A-Za-z0-9][A-Za-z0-9._-]{1,39}$")) throw ApiException.badRequest("invalid_code", "Code: 2–40 letters, digits, dots, dashes or underscores.");
        String title = required(in.title(), "title", 200);
        int duration = in.durationMinutes() == null ? 60 : in.durationMinutes();
        if (duration < 1 || duration > 600) throw ApiException.badRequest("invalid_duration", "Duration must be 1–600 minutes.");
        UUID tenant = TenantContext.requireTenantId();
        UUID id = Ids.newId();
        UUID vid = Ids.newId();
        try {
            jdbc.sql("INSERT INTO assessment (id, tenant_id, org_unit_id, code, purpose, created_by) VALUES (?, ?, ?, ?, ?, ?)")
                    .params(id, tenant, in.orgUnitId(), code, purpose, me()).update();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("assessment_code_taken", "An assessment with this code already exists.");
        }
        jdbc.sql("""
                INSERT INTO assessment_version (id, tenant_id, assessment_id, version_no, governance, title, description, duration_minutes, review_hash, author_id)
                VALUES (?, ?, ?, 1, ?, ?, ?, ?, ?, ?)""")
                .params(vid, tenant, id, governanceFor(purpose), title, optional(in.description(), 2000), duration, "0".repeat(64), me()).update();
        jdbc.sql("UPDATE assessment SET current_version_id = ? WHERE id = ?").params(vid, id).update();
        refreshHash(vid);
        transition(vid, null, "DRAFT", "created");
        audit.recordSuccess("assessment.created", "assessment", id.toString(), Map.of("code", code, "purpose", purpose, "governance", governanceFor(purpose)));
        return detail(id);
    }

    /** Replaces the open version's definition. Editing a version in review returns it to draft and invalidates its reviews. */
    @Transactional
    public Detail update(UUID id, DraftInput in) {
        Head h = head(id, true);
        if (!"ACTIVE".equals(h.status())) throw ApiException.conflict("assessment_archived", "This assessment is archived.");
        Cur c = cur(h.currentId(), true);
        if (!List.of("DRAFT", "IN_REVIEW").contains(c.state())) throw ApiException.conflict("not_editable", "Published versions cannot change. Create a new version.");
        if (!canAuthor(h.purpose(), h.orgUnitId())) throw new AccessDeniedException("Missing permission " + AUTHOR);
        if (!me().equals(c.authorId())) throw new AccessDeniedException("Only the author of this version can edit it");
        if (in.version() == null || in.version() != c.version()) throw ApiException.conflict("concurrent_modification", "This assessment was changed. Reload and try again.");
        String negative = in.negativeMarking() == null ? "QUESTION" : in.negativeMarking().strip().toUpperCase();
        if (!Set.of("OFF", "QUESTION", "CUSTOM").contains(negative)) throw ApiException.badRequest("invalid_negative_marking", "Negative marking: OFF, QUESTION or CUSTOM.");
        int duration = in.durationMinutes() == null ? 60 : in.durationMinutes();
        if (duration < 1 || duration > 600) throw ApiException.badRequest("invalid_duration", "Duration must be 1–600 minutes.");
        if (in.declaredTotalMarks() != null && (in.declaredTotalMarks().signum() <= 0 || in.declaredTotalMarks().scale() > 2)) {
            throw ApiException.badRequest("invalid_total", "Total marks must be positive, with at most two decimals.");
        }
        List<ItemInput> items = in.items() == null ? List.of() : in.items();
        List<RuleInput> rules = in.rules() == null ? List.of() : in.rules();
        if (items.size() + rules.stream().mapToInt(RuleInput::count).sum() > MAX_QUESTIONS) throw ApiException.badRequest("too_many_questions", "At most " + MAX_QUESTIONS + " questions.");

        // fixed questions: approved versions of readable, eligible banks
        Map<UUID, ApprovedQuestions.State> states = questions.states(items.stream().map(ItemInput::questionVersionId).toList());
        Map<UUID, UUID> banks = questions.bankOf(items.stream().map(ItemInput::questionVersionId).toList());
        Map<UUID, ApprovedQuestions.Approved> versions = questions.versions(items.stream().map(ItemInput::questionVersionId).toList());
        Set<UUID> seenQuestions = new HashSet<>();
        for (ItemInput it : items) {
            ApprovedQuestions.State s = states.get(it.questionVersionId());
            if (s == null) throw ApiException.badRequest("unknown_question", "A selected question does not exist.");
            if (!s.usable()) throw ApiException.badRequest("question_not_approved", "Only approved questions can be added (a selected question is " + s.state().toLowerCase() + ").");
            if (!seenQuestions.add(s.questionId())) throw ApiException.badRequest("duplicate_question", "A question was added twice.");
            requireEligibleBank(h, banks.get(it.questionVersionId()));
        }
        for (RuleInput r : rules) {
            if (r.bankId() == null) throw ApiException.badRequest("invalid_rule", "Each random rule needs a question bank.");
            if (r.count() < 1 || r.count() > MAX_QUESTIONS) throw ApiException.badRequest("invalid_rule", "Each random rule draws 1–500 questions.");
            if (r.marks() == null || r.marks().signum() <= 0 || r.marks().compareTo(BigDecimal.valueOf(1000)) > 0) throw ApiException.badRequest("invalid_marks", "Each random rule needs marks above 0.");
            requireEligibleBank(h, r.bankId());
        }

        String title = required(in.title(), "title", 200);
        jdbc.sql("""
                UPDATE assessment_version SET state = 'DRAFT', submitted_at = NULL, title = ?, description = ?, instructions = ?, duration_minutes = ?,
                    declared_total_marks = ?, negative_marking = ?, shuffle_questions = ?, shuffle_options = ?, updated_at = now(), version = version + 1
                WHERE id = ?""")
                .params(title, optional(in.description(), 2000), optional(in.instructions(), 10000), duration, in.declaredTotalMarks(), negative,
                        Boolean.TRUE.equals(in.shuffleQuestions()), !Boolean.FALSE.equals(in.shuffleOptions()), c.id()).update();
        jdbc.sql("DELETE FROM assessment_item WHERE version_id = ?").param(c.id()).update();
        jdbc.sql("DELETE FROM assessment_rule WHERE version_id = ?").param(c.id()).update();
        UUID tenant = TenantContext.requireTenantId();
        int ordinal = 0;
        for (ItemInput it : items) {
            ApprovedQuestions.Approved q = versions.get(it.questionVersionId());
            BigDecimal marks = it.marks() == null ? q.marks() : it.marks();
            BigDecimal neg = switch (negative) {
                case "OFF" -> BigDecimal.ZERO;
                case "QUESTION" -> it.negativeMarks() == null ? q.negativeMarks() : it.negativeMarks();
                default -> it.negativeMarks() == null ? BigDecimal.ZERO : it.negativeMarks();
            };
            checkMarks(marks, neg);
            jdbc.sql("INSERT INTO assessment_item (id, tenant_id, version_id, ordinal, question_version_id, marks, negative_marks) VALUES (?, ?, ?, ?, ?, ?, ?)")
                    .params(Ids.newId(), tenant, c.id(), ++ordinal, it.questionVersionId(), marks, neg).update();
        }
        ordinal = 0;
        for (RuleInput r : rules) {
            BigDecimal neg = "OFF".equals(negative) || r.negativeMarks() == null ? BigDecimal.ZERO : r.negativeMarks();
            checkMarks(r.marks(), neg);
            jdbc.sql("""
                    INSERT INTO assessment_rule (id, tenant_id, version_id, ordinal, label, bank_id, subject_id, topic_id, difficulty_id, question_type, draw_count, marks, negative_marks)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(Ids.newId(), tenant, c.id(), ++ordinal, optional(r.label(), 150), r.bankId(), r.subjectId(), r.topicId(), r.difficultyId(),
                            r.questionType() == null || r.questionType().isBlank() ? null : r.questionType().strip(), r.count(), r.marks(), neg).update();
        }
        refreshHash(c.id());
        touch(id);
        if ("IN_REVIEW".equals(c.state())) {
            transition(c.id(), "IN_REVIEW", "DRAFT", "modified after submission: reviews invalidated");
            audit.recordSuccess("assessment.reviews_invalidated", "assessment_version", c.id().toString(), Map.of("assessment", id.toString()));
        }
        audit.recordSuccess("assessment.draft_updated", "assessment_version", c.id().toString(), Map.of("assessment", id.toString(),
                "questions", String.valueOf(items.size()), "rules", String.valueOf(rules.size())));
        return detail(id);
    }

    private static void checkMarks(BigDecimal marks, BigDecimal neg) {
        if (marks.signum() <= 0 || marks.scale() > 2 || marks.compareTo(BigDecimal.valueOf(1000)) > 0) throw ApiException.badRequest("invalid_marks", "Marks must be above 0 and at most 1000, two decimals.");
        if (neg.signum() < 0 || neg.compareTo(marks) > 0 || neg.scale() > 2) throw ApiException.badRequest("invalid_marks", "Negative marks must be between 0 and the question's marks.");
    }

    /**
     * A bank may feed an assessment when the caller can read it and it belongs to the assessment's institution, one of its
     * ancestors, or the whole organization. ADMISSION assessments use admission (high-stakes) banks only.
     */
    void requireEligibleBank(Head h, UUID bankId) {
        if (bankId == null) throw ApiException.badRequest("unknown_bank", "Unknown question bank.");
        ApprovedQuestions.Bank b = questions.bank(bankId);
        if (b.orgUnitId() != null && h.orgUnitId() != null) {
            boolean ancestor = jdbc.sql("SELECT count(*) FROM org_unit a, org_unit u WHERE a.id = ? AND u.id = ? AND u.path <@ a.path")
                    .params(b.orgUnitId(), h.orgUnitId()).query(Long.class).single() > 0;
            if (!ancestor) throw ApiException.badRequest("bank_not_eligible", "The bank " + b.name() + " belongs to another institution.");
        }
        if ("ADMISSION".equals(h.purpose()) && !b.intendedUses().contains("ADMISSION_EXAM")) {
            throw ApiException.badRequest("bank_not_eligible", "Admission assessments use admission-examination (high-stakes) banks only; " + b.name() + " is not one.");
        }
    }

    // ================================================================================================ validation

    @Transactional(readOnly = true)
    public Validation validate(UUID id) {
        Head h = head(id, false);
        return validateVersion(h, h.currentId());
    }

    Validation validateVersion(Head h, UUID versionId) {
        Version v = version(versionId);
        List<Issue> errors = new ArrayList<>();
        List<Issue> warnings = new ArrayList<>();
        Map<String, Integer> byDifficulty = new TreeMap<>();
        boolean frozen = List.of("PUBLISHED", "SUPERSEDED", "WITHDRAWN").contains(v.state());
        Set<UUID> fixedQuestions = new HashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        int count = 0;
        for (Item it : v.items()) {
            fixedQuestions.add(it.questionId());
            total = total.add(it.marks());
            count++;
            byDifficulty.merge(it.difficultyLabel() == null ? "Unclassified" : it.difficultyLabel(), 1, Integer::sum);
            if (!it.usable()) {
                Issue issue = new Issue("question_not_approved", "Question \"" + abbreviate(it.excerpt()) + "\" is " + it.questionState().toLowerCase()
                        + "; replace it with the current approved version.");
                if (frozen) warnings.add(issue); else errors.add(issue);
            }
        }
        Map<UUID, Set<UUID>> poolQuestions = new LinkedHashMap<>();
        for (Rule r : v.rules()) {
            Set<UUID> qs = new LinkedHashSet<>();
            for (var e : poolOf(r, frozen)) if (!fixedQuestions.contains(e.questionId())) qs.add(e.questionId());
            poolQuestions.put(r.id(), qs);
            total = total.add(r.marks().multiply(BigDecimal.valueOf(r.count())));
            count += r.count();
            byDifficulty.merge(r.difficultyId() == null ? "Any difficulty" : difficultyLabel(r.difficultyId()), r.count(), Integer::sum);
            String name = r.label() != null ? r.label() : "Random rule " + r.ordinal();
            if (qs.size() < r.count()) {
                errors.add(new Issue("pool_too_small", name + " draws " + r.count() + " but only " + qs.size() + " approved questions match."));
            } else if (qs.size() < 3L * r.count()) {
                Issue issue = new Issue("pool_low_exposure", name + ": the pool (" + qs.size() + ") is smaller than three times the draw (" + r.count() + ").");
                if ("ADMISSION".equals(h.purpose())) errors.add(new Issue("pool_low_exposure", issue.message() + " Admission assessments need at least 3× (Q-2.2-5).")); else warnings.add(issue);
            }
        }
        // rules must draw from disjoint pools (complex feasibility proofs are deferred)
        List<UUID> ruleIds = new ArrayList<>(poolQuestions.keySet());
        for (int i = 0; i < ruleIds.size(); i++) {
            for (int j = i + 1; j < ruleIds.size(); j++) {
                Set<UUID> overlap = new HashSet<>(poolQuestions.get(ruleIds.get(i)));
                overlap.retainAll(poolQuestions.get(ruleIds.get(j)));
                if (!overlap.isEmpty()) errors.add(new Issue("rules_overlap", "Random rules " + (i + 1) + " and " + (j + 1) + " can pick the same "
                        + overlap.size() + " question(s); make their filters distinct (for example by difficulty or topic)."));
            }
        }
        if (count == 0) errors.add(new Issue("empty", "Add questions or random rules."));
        if (count > MAX_QUESTIONS) errors.add(new Issue("too_many_questions", "At most " + MAX_QUESTIONS + " questions."));
        if (v.declaredTotalMarks() != null && v.declaredTotalMarks().compareTo(total) != 0) {
            errors.add(new Issue("total_mismatch", "Declared total " + v.declaredTotalMarks().toPlainString() + " differs from the computed total " + total.toPlainString() + "."));
        }
        return new Validation(errors, warnings, count, total, byDifficulty);
    }

    List<ApprovedQuestions.PoolEntry> poolOf(Rule r, boolean frozen) {
        if (frozen) {
            return jdbc.sql("SELECT p.question_version_id, v.question_id FROM assessment_rule_pool p JOIN question_version v ON v.id = p.question_version_id WHERE p.rule_id = ? ORDER BY 1")
                    .param(r.id()).query((rs, n) -> new ApprovedQuestions.PoolEntry(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class))).list();
        }
        return questions.pool(r.bankId(), r.subjectId(), r.topicId(), r.difficultyId(), r.questionType());
    }

    private String difficultyLabel(UUID id) {
        return jdbc.sql("SELECT label FROM difficulty_level WHERE id = ?").param(id).query(String.class).optional().orElse("Unknown");
    }

    // ================================================================================================ workflow

    @Transactional
    public Detail submit(UUID id, String reason) {
        Head h = head(id, true);
        Cur c = cur(h.currentId(), true);
        if (!canAuthor(h.purpose(), h.orgUnitId())) throw new AccessDeniedException("Missing permission " + AUTHOR);
        if (!"DRAFT".equals(c.state())) throw ApiException.conflict("not_draft", "Only a draft can be submitted.");
        if (!me().equals(c.authorId())) throw new AccessDeniedException("Only the author can submit this draft");
        Validation val = validateVersion(h, c.id());
        if (!val.ok()) throw ApiException.conflict("validation_failed", "Fix the validation errors first: " + val.errors().getFirst().message());
        jdbc.sql("UPDATE assessment_version SET state = 'IN_REVIEW', submitted_at = now(), first_submitted_at = coalesce(first_submitted_at, now()), total_marks = ?, version = version + 1 WHERE id = ?")
                .params(val.totalMarks(), c.id()).update();
        // total_marks is part of the version, not of the reviewed definition; keep the hash stable
        touch(id);
        transition(c.id(), "DRAFT", "IN_REVIEW", reason);
        audit.recordSuccess("assessment.submitted", "assessment_version", c.id().toString(), Map.of("assessment", id.toString(), "governance", c.governance(),
                "questions", String.valueOf(val.questionCount()), "totalMarks", val.totalMarks().toPlainString()));
        return detail(id);
    }

    @Transactional
    public Detail review(UUID id, boolean approveContent, String comment) {
        Head h = head(id, true);
        if (!can(REVIEW, h.orgUnitId())) throw new AccessDeniedException("Missing permission " + REVIEW);
        Cur c = cur(h.currentId(), true);
        if (me().equals(c.authorId())) {
            audit.recordDenied("assessment.self_review_denied", Map.of("assessmentId", id.toString()));
            throw ApiException.conflict("self_review", "You wrote this version, so someone else must review it.");
        }
        if (!"IN_REVIEW".equals(c.state())) throw ApiException.conflict("not_in_review", "This version is not in review.");
        String text = optional(comment, 2000);
        if (!approveContent && text == null) throw ApiException.badRequest("reason_required", "Explain which changes are needed.");
        boolean already = jdbc.sql("SELECT EXISTS (SELECT 1 FROM assessment_review WHERE version_id = ? AND reviewer_id = ? AND invalidated_at IS NULL AND decision = 'REVIEWED')")
                .params(c.id(), me()).query(Boolean.class).single();
        if (already && approveContent) throw ApiException.conflict("already_reviewed", "You have already reviewed this version.");
        UUID rid = Ids.newId();
        try {
            jdbc.sql("INSERT INTO assessment_review (id, tenant_id, version_id, reviewer_id, decision, reviewed_hash, comment) VALUES (?, ?, ?, ?, ?, ?, ?)")
                    .params(rid, TenantContext.requireTenantId(), c.id(), me(), approveContent ? "REVIEWED" : "CHANGES_REQUESTED", c.reviewHash(), text).update();
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("review_refused", "This review is not allowed for this version.");
        }
        if (!approveContent) {
            jdbc.sql("UPDATE assessment_version SET state = 'DRAFT', submitted_at = NULL, version = version + 1 WHERE id = ?").param(c.id()).update();
            transition(c.id(), "IN_REVIEW", "DRAFT", "changes requested: " + truncate(text, 400));
        }
        touch(id);
        audit.recordSuccess(approveContent ? "assessment.reviewed" : "assessment.changes_requested", "assessment_version", c.id().toString(),
                text == null ? Map.of("review", rid.toString()) : Map.of("review", rid.toString(), "comment", truncate(text, 300)));
        return detail(id);
    }

    /**
     * Approval and publication by an independent holder of assessment.publish: validation re-run live, pools frozen,
     * question references recorded, previous published version superseded. The database re-checks the policy.
     */
    @Transactional
    public Detail publish(UUID id, String reason) {
        Head h = head(id, true);
        if (!can(PUBLISH, h.orgUnitId())) throw new AccessDeniedException("Missing permission " + PUBLISH);
        Cur c = cur(h.currentId(), true);
        UUID me = me();
        if (me.equals(c.authorId())) {
            audit.recordDenied("assessment.self_publication_denied", Map.of("assessmentId", id.toString()));
            throw ApiException.conflict("self_publication", "You wrote this version, so someone else must publish it.");
        }
        if (!"IN_REVIEW".equals(c.state())) throw ApiException.conflict("not_in_review", "Only a reviewed version in review can be published.");
        List<UUID> reviewers = jdbc.sql("""
                SELECT DISTINCT reviewer_id FROM assessment_review WHERE version_id = ? AND decision = 'REVIEWED' AND invalidated_at IS NULL AND reviewed_hash = ? AND reviewer_id <> ?""")
                .params(c.id(), c.reviewHash(), c.authorId()).query(UUID.class).list();
        if (reviewers.isEmpty()) throw ApiException.conflict("review_missing", "An independent review of the current content is needed first.");
        if ("HIGH_STAKES".equals(c.governance()) && reviewers.contains(me)) {
            audit.recordDenied("assessment.publication_policy_denied", Map.of("assessmentId", id.toString(), "rule", "publisher_reviewed"));
            throw ApiException.conflict("publisher_reviewed", "This assessment needs three different people: you reviewed it, so someone else must publish it.");
        }
        Validation val = validateVersion(h, c.id());
        if (!val.ok()) throw ApiException.conflict("validation_failed", "Publication blocked: " + val.errors().getFirst().message());

        // freeze pools (the version is still in review, as the pool guard requires)
        UUID tenant = TenantContext.requireTenantId();
        Version v = version(c.id());
        Set<UUID> fixedQuestions = new HashSet<>();
        v.items().forEach(it -> fixedQuestions.add(it.questionId()));
        Set<UUID> referenced = new LinkedHashSet<>();
        v.items().forEach(it -> referenced.add(it.questionVersionId()));
        for (Rule r : v.rules()) {
            for (var e : questions.pool(r.bankId(), r.subjectId(), r.topicId(), r.difficultyId(), r.questionType())) {
                if (fixedQuestions.contains(e.questionId())) continue;
                jdbc.sql("INSERT INTO assessment_rule_pool (tenant_id, rule_id, question_version_id) VALUES (?, ?, ?)").params(tenant, r.id(), e.versionId()).update();
                referenced.add(e.versionId());
            }
        }
        try {
            jdbc.sql("UPDATE assessment_version SET state = 'PUBLISHED', published_by = ?, published_at = now(), total_marks = ?, version = version + 1 WHERE id = ?")
                    .params(me, val.totalMarks(), c.id()).update();
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("publication_policy_not_met", "The publication policy is not met.");
        }
        transition(c.id(), "IN_REVIEW", "PUBLISHED", reason);
        if (h.publishedId() != null) {
            jdbc.sql("UPDATE assessment_version SET state = 'SUPERSEDED', superseded_at = now(), version = version + 1 WHERE id = ? AND state = 'PUBLISHED'")
                    .param(h.publishedId()).update();
            transition(h.publishedId(), "PUBLISHED", "SUPERSEDED", "superseded by version " + c.versionNo());
        }
        jdbc.sql("UPDATE assessment SET published_version_id = ?, updated_at = now(), version = version + 1 WHERE id = ?").params(c.id(), id).update();
        int refs = references.referenceAll(referenced, "assessment_version", c.id().toString());
        Map<String, String> d = new LinkedHashMap<>();
        d.put("assessment", id.toString());
        d.put("versionNo", String.valueOf(c.versionNo()));
        d.put("governance", c.governance());
        d.put("questions", String.valueOf(val.questionCount()));
        d.put("totalMarks", val.totalMarks().toPlainString());
        d.put("questionVersionsReferenced", String.valueOf(refs));
        audit.recordSuccess("assessment.published", "assessment_version", c.id().toString(), d);
        outbox.append("assessment", id.toString(), "assessment.published", Map.of("assessmentId", id.toString(), "versionId", c.id().toString(),
                "versionNo", String.valueOf(c.versionNo()), "purpose", h.purpose()));
        return detail(id);
    }

    /** New draft from the published version; questions that are no longer approved are replaced by their current approved version or dropped. */
    @Transactional
    public Detail newVersion(UUID id) {
        Head h = head(id, true);
        if (!canAuthor(h.purpose(), h.orgUnitId())) throw new AccessDeniedException("Missing permission " + AUTHOR);
        if (h.publishedId() == null) throw ApiException.conflict("not_published", "Only a published assessment gets new versions.");
        Cur c = cur(h.currentId(), false);
        if (!List.of("PUBLISHED", "SUPERSEDED", "WITHDRAWN").contains(c.state())) throw ApiException.conflict("open_version_exists", "A draft or version in review already exists.");
        UUID tenant = TenantContext.requireTenantId();
        UUID vid = Ids.newId();
        int no = jdbc.sql("SELECT max(version_no) FROM assessment_version WHERE assessment_id = ?").param(id).query(Integer.class).single() + 1;
        jdbc.sql("""
                INSERT INTO assessment_version (id, tenant_id, assessment_id, version_no, governance, title, description, instructions, duration_minutes,
                    declared_total_marks, negative_marking, shuffle_questions, shuffle_options, review_hash, author_id)
                SELECT ?, tenant_id, assessment_id, ?, governance, title, description, instructions, duration_minutes, declared_total_marks, negative_marking,
                       shuffle_questions, shuffle_options, review_hash, ? FROM assessment_version WHERE id = ?""").params(vid, no, me(), h.publishedId()).update();
        List<String> notes = new ArrayList<>();
        Version pub = version(h.publishedId());
        int ordinal = 0;
        for (Item it : pub.items()) {
            UUID use = it.questionVersionId();
            if (!it.usable()) {
                ApprovedQuestions.State s = questions.states(List.of(use)).get(use);
                if (s != null && s.currentApprovedVersionId() != null && "ACTIVE".equals(s.questionStatus())) {
                    use = s.currentApprovedVersionId();
                    notes.add("replaced \"" + abbreviate(it.excerpt()) + "\" by its current approved version");
                } else {
                    notes.add("removed \"" + abbreviate(it.excerpt()) + "\" (retired)");
                    continue;
                }
            }
            jdbc.sql("INSERT INTO assessment_item (id, tenant_id, version_id, ordinal, question_version_id, marks, negative_marks) VALUES (?, ?, ?, ?, ?, ?, ?)")
                    .params(Ids.newId(), tenant, vid, ++ordinal, use, it.marks(), it.negativeMarks()).update();
        }
        jdbc.sql("""
                INSERT INTO assessment_rule (id, tenant_id, version_id, section_no, ordinal, label, bank_id, subject_id, topic_id, difficulty_id, question_type, draw_count, marks, negative_marks)
                SELECT gen_random_uuid(), tenant_id, ?, section_no, ordinal, label, bank_id, subject_id, topic_id, difficulty_id, question_type, draw_count, marks, negative_marks
                FROM assessment_rule WHERE version_id = ?""").params(vid, h.publishedId()).update();
        jdbc.sql("UPDATE assessment SET current_version_id = ?, updated_at = now(), version = version + 1 WHERE id = ?").params(vid, id).update();
        refreshHash(vid);
        transition(vid, null, "DRAFT", notes.isEmpty() ? "new version from version " + pub.versionNo() : truncate("new version; " + String.join("; ", notes), 500));
        audit.recordSuccess("assessment.version_created", "assessment_version", vid.toString(), Map.of("assessment", id.toString(), "versionNo", String.valueOf(no),
                "from", h.publishedId().toString(), "changes", String.valueOf(notes.size())));
        return detail(id);
    }

    // ================================================================================================ reading

    @Transactional(readOnly = true)
    public List<Summary> list(String purpose, String state, String text) {
        List<Summary> all = jdbc.sql("""
                SELECT a.id, a.code, a.purpose, v.title, a.org_unit_id, ou.name AS unit, v.version_no, v.state, pv.version_no AS pub_no,
                       coalesce(pv.total_marks, v.total_marks) AS total, v.duration_minutes, u.display_name AS author, a.updated_at
                FROM assessment a JOIN assessment_version v ON v.id = a.current_version_id LEFT JOIN assessment_version pv ON pv.id = a.published_version_id
                LEFT JOIN org_unit ou ON ou.id = a.org_unit_id LEFT JOIN user_account u ON u.id = v.author_id
                WHERE a.status = 'ACTIVE' AND (CAST(? AS text) IS NULL OR a.purpose = CAST(? AS text)) AND (CAST(? AS text) IS NULL OR v.state = CAST(? AS text))
                  AND (CAST(? AS text) IS NULL OR v.title ILIKE '%' || CAST(? AS text) || '%' OR a.code ILIKE '%' || CAST(? AS text) || '%')
                ORDER BY a.updated_at DESC LIMIT 500""")
                .params(purpose, purpose, state, state, text, text, text)
                .query((rs, n) -> new Summary(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("purpose"), rs.getString("title"),
                        rs.getObject("org_unit_id", UUID.class), rs.getString("unit"), rs.getInt("version_no"), rs.getString("state"),
                        (Integer) rs.getObject("pub_no"), rs.getBigDecimal("total"), rs.getInt("duration_minutes"), rs.getString("author"), ts(rs, "updated_at"))).list();
        return all.stream().filter(s -> can(READ, s.orgUnitId())).toList();
    }

    @Transactional(readOnly = true)
    public Detail get(UUID id) {
        head(id, false);
        return detail(id);
    }

    Detail detail(UUID id) {
        Head h = jdbc.sql("SELECT id, org_unit_id, purpose, code, status, current_version_id, published_version_id, version FROM assessment WHERE id = ?").param(id)
                .query((rs, n) -> new Head(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getObject(6, UUID.class), rs.getObject(7, UUID.class), rs.getLong(8))).single();
        Version current = version(h.currentId());
        Version published = h.publishedId() == null || h.publishedId().equals(h.currentId()) ? null : version(h.publishedId());
        String unitName = h.orgUnitId() == null ? "Whole organization" : jdbc.sql("SELECT name FROM org_unit WHERE id = ?").param(h.orgUnitId()).query(String.class).single();
        List<VersionSummary> versions = jdbc.sql("""
                SELECT v.id, v.version_no, v.state, u.display_name, v.created_at, v.published_at FROM assessment_version v
                LEFT JOIN user_account u ON u.id = v.author_id WHERE v.assessment_id = ? ORDER BY v.version_no DESC""").param(id)
                .query((rs, n) -> new VersionSummary(rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3), rs.getString(4), ts(rs, "created_at"), ts(rs, "published_at"))).list();
        UUID me = me();
        boolean author = me.equals(current.authorId());
        boolean active = "ACTIVE".equals(h.status());
        boolean reviewedByMe = current.reviews().stream().anyMatch(r -> r.valid() && "REVIEWED".equals(r.decision()) && me.equals(r.reviewerId()));
        boolean hasValidReview = current.reviews().stream().anyMatch(r -> r.valid() && "REVIEWED".equals(r.decision()));
        String blocked = null;
        boolean canPublish = active && "IN_REVIEW".equals(current.state()) && !author && can(PUBLISH, h.orgUnitId()) && hasValidReview;
        if (canPublish && "HIGH_STAKES".equals(current.governance()) && reviewedByMe) {
            canPublish = false;
            blocked = "This assessment needs three different people: you reviewed it, so someone else must publish it.";
        }
        if ("IN_REVIEW".equals(current.state()) && author) blocked = "You wrote this version: someone else must review and publish it.";
        Actions actions = new Actions(
                active && List.of("DRAFT", "IN_REVIEW").contains(current.state()) && author && canAuthor(h.purpose(), h.orgUnitId()),
                active && "DRAFT".equals(current.state()) && author && canAuthor(h.purpose(), h.orgUnitId()),
                active && "IN_REVIEW".equals(current.state()) && !author && !reviewedByMe && can(REVIEW, h.orgUnitId()),
                canPublish,
                active && List.of("PUBLISHED", "SUPERSEDED", "WITHDRAWN").contains(current.state()) && canAuthor(h.purpose(), h.orgUnitId()),
                blocked);
        // Q-2.2-3: questions retired or replaced after publication never change the published version; warn administrators
        List<String> warnings = new ArrayList<>();
        Version pub = h.publishedId() == null ? null : h.publishedId().equals(h.currentId()) ? current : published;
        if (pub != null) {
            long stale = pub.items().stream().filter(it -> !it.usable()).count();
            long stalePool = jdbc.sql("""
                    SELECT count(*) FROM assessment_rule_pool p JOIN assessment_rule r ON r.id = p.rule_id JOIN question_version qv ON qv.id = p.question_version_id
                    JOIN question q ON q.id = qv.question_id WHERE r.version_id = ? AND (qv.state <> 'APPROVED' OR q.status <> 'ACTIVE')""").param(pub.id()).query(Long.class).single();
            if (stale + stalePool > 0) {
                warnings.add((stale + stalePool) + " question(s) used by published version " + pub.versionNo() + " were retired or replaced after publication. "
                        + "The published version is unchanged (papers stay as approved); create a new version to change it.");
            }
        }
        return new Detail(id, h.code(), h.purpose(), h.orgUnitId(), unitName, h.status(), current, published, versions, actions, warnings, h.version());
    }

    @Transactional(readOnly = true)
    public Version versionOf(UUID id, int no) {
        head(id, false);
        UUID vid = jdbc.sql("SELECT id FROM assessment_version WHERE assessment_id = ? AND version_no = ?").params(id, no).query(UUID.class).optional()
                .orElseThrow(() -> ApiException.notFound("Assessment version"));
        return version(vid);
    }

    Version version(UUID vid) {
        Map<UUID, Integer> poolSizes = new HashMap<>();
        boolean frozen = jdbc.sql("SELECT state IN ('PUBLISHED', 'SUPERSEDED', 'WITHDRAWN') FROM assessment_version WHERE id = ?").param(vid).query(Boolean.class).single();
        List<UUID> itemVersions = jdbc.sql("SELECT question_version_id FROM assessment_item WHERE version_id = ? ORDER BY ordinal").param(vid).query(UUID.class).list();
        Map<UUID, ApprovedQuestions.Approved> qv = questions.versions(itemVersions);
        Map<UUID, ApprovedQuestions.State> st = questions.states(itemVersions);
        List<Item> items = jdbc.sql("SELECT id, ordinal, question_version_id, marks, negative_marks FROM assessment_item WHERE version_id = ? ORDER BY ordinal").param(vid)
                .query((rs, n) -> {
                    UUID q = rs.getObject(3, UUID.class);
                    ApprovedQuestions.Approved a = qv.get(q);
                    ApprovedQuestions.State s = st.get(q);
                    return new Item(rs.getObject(1, UUID.class), rs.getInt(2), q, a == null ? null : a.questionId(), a == null ? null : a.bankId(),
                            a == null ? null : a.type(), a == null ? null : a.excerpt(), a == null ? null : a.subjectName(), a == null ? null : a.topicName(),
                            a == null ? null : a.difficultyLabel(), rs.getBigDecimal(4), rs.getBigDecimal(5), s == null ? "UNKNOWN" : s.state(), s != null && s.usable());
                }).list();
        if (frozen) {
            jdbc.sql("SELECT r.id, count(p.question_version_id) FROM assessment_rule r LEFT JOIN assessment_rule_pool p ON p.rule_id = r.id WHERE r.version_id = ? GROUP BY r.id")
                    .param(vid).query((rs, n) -> { poolSizes.put(rs.getObject(1, UUID.class), rs.getInt(2)); return null; }).list();
        }
        List<Rule> rules = jdbc.sql("""
                SELECT r.id, r.ordinal, r.label, r.bank_id, b.name, r.subject_id, r.topic_id, r.difficulty_id, r.question_type, r.draw_count, r.marks, r.negative_marks
                FROM assessment_rule r JOIN question_bank b ON b.id = r.bank_id WHERE r.version_id = ? ORDER BY r.ordinal""").param(vid)
                .query((rs, n) -> new Rule(rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3), rs.getObject(4, UUID.class), rs.getString(5),
                        rs.getObject(6, UUID.class), rs.getObject(7, UUID.class), rs.getObject(8, UUID.class), rs.getString(9), rs.getInt(10), rs.getBigDecimal(11),
                        rs.getBigDecimal(12), -1, frozen)).list();
        List<Rule> sized = new ArrayList<>();
        for (Rule r : rules) {
            int size;
            if (frozen) size = poolSizes.getOrDefault(r.id(), 0);
            else {
                try {
                    size = questions.pool(r.bankId(), r.subjectId(), r.topicId(), r.difficultyId(), r.questionType()).size();
                } catch (ApiException e) {
                    size = 0;                                      // bank not readable by this caller
                }
            }
            sized.add(new Rule(r.id(), r.ordinal(), r.label(), r.bankId(), r.bankName(), r.subjectId(), r.topicId(), r.difficultyId(), r.questionType(),
                    r.count(), r.marks(), r.negativeMarks(), size, frozen));
        }
        List<Review> reviews = jdbc.sql("""
                SELECT r.id, r.reviewer_id, u.display_name, r.decision, r.comment, r.created_at, r.invalidated_at, r.invalidated_reason, r.reviewed_hash, v.review_hash
                FROM assessment_review r JOIN assessment_version v ON v.id = r.version_id LEFT JOIN user_account u ON u.id = r.reviewer_id
                WHERE r.version_id = ? ORDER BY r.created_at""").param(vid)
                .query((rs, n) -> new Review(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getString(5),
                        ts(rs, "created_at"), rs.getTimestamp("invalidated_at") == null && rs.getString(9).equals(rs.getString(10)), rs.getString(8))).list();
        return jdbc.sql("""
                SELECT v.*, au.display_name AS author_name, pu.display_name AS publisher_name FROM assessment_version v
                LEFT JOIN user_account au ON au.id = v.author_id LEFT JOIN user_account pu ON pu.id = v.published_by WHERE v.id = ?""").param(vid)
                .query((rs, n) -> new Version(rs.getObject("id", UUID.class), rs.getInt("version_no"), rs.getString("state"), rs.getString("governance"),
                        rs.getString("title"), rs.getString("description"), rs.getString("instructions"), rs.getInt("duration_minutes"),
                        rs.getBigDecimal("declared_total_marks"), rs.getBigDecimal("total_marks"), rs.getString("negative_marking"), rs.getBoolean("shuffle_questions"),
                        rs.getBoolean("shuffle_options"), items, sized, reviews, rs.getObject("author_id", UUID.class), rs.getString("author_name"),
                        rs.getObject("published_by", UUID.class), rs.getString("publisher_name"), ts(rs, "published_at"), ts(rs, "created_at"), rs.getLong("version")))
                .single();
    }

    @Transactional(readOnly = true)
    public List<Transition> transitions(UUID id) {
        head(id, false);
        return jdbc.sql("""
                SELECT v.version_no, t.from_state, t.to_state, u.display_name, t.reason, t.at FROM assessment_transition t
                JOIN assessment_version v ON v.id = t.version_id LEFT JOIN user_account u ON u.id = t.actor_id
                WHERE v.assessment_id = ? ORDER BY t.at""").param(id)
                .query((rs, n) -> new Transition(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), ts(rs, "at"))).list();
    }

    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<HistoryEvent> history(UUID id) {
        head(id, false);
        List<String> ids = new ArrayList<>(jdbc.sql("SELECT id::text FROM assessment_version WHERE assessment_id = ?").param(id).query(String.class).list());
        ids.add(id.toString());
        return jdbc.sql("""
                SELECT e.action, coalesce(u.display_name, e.actor_subject) AS actor, e.occurred_at, e.details::text FROM audit_event e
                LEFT JOIN user_account u ON u.id = e.actor_id
                WHERE e.resource_type IN ('assessment', 'assessment_version', 'assessment_paper') AND (e.resource_id = ANY (?) OR e.details->>'assessment' = ?)
                ORDER BY e.seq""").params(ids.toArray(String[]::new), id.toString())
                .query((rs, n) -> new HistoryEvent(rs.getString(1), rs.getString(2), ts(rs, "occurred_at"), json.readValue(rs.getString(4), Map.class))).list();
    }

    @Transactional(readOnly = true)
    public List<QueueItem> queue(String stage) {
        UUID me = me();
        List<QueueItem> out = new ArrayList<>();
        jdbc.sql("""
                SELECT a.id, v.id AS vid, v.version_no, a.code, v.title, a.purpose, v.state, u.display_name AS author, v.submitted_at, a.org_unit_id, v.author_id, v.governance,
                       (SELECT count(DISTINCT r.reviewer_id) FROM assessment_review r WHERE r.version_id = v.id AND r.decision = 'REVIEWED' AND r.invalidated_at IS NULL
                          AND r.reviewed_hash = v.review_hash) AS valid,
                       EXISTS (SELECT 1 FROM assessment_review r WHERE r.version_id = v.id AND r.reviewer_id = ? AND r.decision = 'REVIEWED' AND r.invalidated_at IS NULL
                          AND r.reviewed_hash = v.review_hash) AS mine
                FROM assessment a JOIN assessment_version v ON v.id = a.current_version_id LEFT JOIN user_account u ON u.id = v.author_id
                WHERE a.status = 'ACTIVE' AND v.state = 'IN_REVIEW' AND v.author_id <> ? ORDER BY v.submitted_at""").params(me, me)
                .query((rs, n) -> {
                    UUID unit = rs.getObject("org_unit_id", UUID.class);
                    int valid = rs.getInt("valid");
                    boolean mine = rs.getBoolean("mine");
                    boolean ok = "publication".equals(stage)
                            ? valid > 0 && can(PUBLISH, unit) && !("HIGH_STAKES".equals(rs.getString("governance")) && mine)
                            : !mine && can(REVIEW, unit);
                    if (ok) out.add(new QueueItem(rs.getObject("id", UUID.class), rs.getObject("vid", UUID.class), rs.getInt("version_no"), rs.getString("code"),
                            rs.getString("title"), rs.getString("purpose"), rs.getString("state"), rs.getString("author"), ts(rs, "submitted_at"), valid));
                    return null;
                }).list();
        return out;
    }

    // ================================================================================================ helpers

    /** Hash of everything a reviewer approves: configuration, questions (version ids, marks) and rules. */
    void refreshHash(UUID vid) {
        StringBuilder sb = new StringBuilder();
        jdbc.sql("""
                SELECT title, coalesce(description, ''), coalesce(instructions, ''), duration_minutes, coalesce(declared_total_marks::text, ''), negative_marking,
                       shuffle_questions, shuffle_options, governance FROM assessment_version WHERE id = ?""").param(vid)
                .query((rs, n) -> { for (int i = 1; i <= 9; i++) sb.append(rs.getString(i)).append('\u001f'); return null; }).list();
        jdbc.sql("SELECT ordinal, question_version_id, marks, negative_marks FROM assessment_item WHERE version_id = ? ORDER BY ordinal").param(vid)
                .query((rs, n) -> { sb.append("I|").append(rs.getInt(1)).append('|').append(rs.getString(2)).append('|').append(rs.getBigDecimal(3).toPlainString())
                        .append('|').append(rs.getBigDecimal(4).toPlainString()).append('\u001e'); return null; }).list();
        jdbc.sql("""
                SELECT ordinal, coalesce(label, ''), bank_id, coalesce(subject_id::text, ''), coalesce(topic_id::text, ''), coalesce(difficulty_id::text, ''),
                       coalesce(question_type, ''), draw_count, marks, negative_marks FROM assessment_rule WHERE version_id = ? ORDER BY ordinal""").param(vid)
                .query((rs, n) -> { sb.append("R"); for (int i = 1; i <= 10; i++) sb.append('|').append(rs.getString(i)); sb.append('\u001e'); return null; }).list();
        String hash;
        try {
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        jdbc.sql("UPDATE assessment_version SET review_hash = ? WHERE id = ? AND review_hash <> ?").params(hash, vid, hash).update();
    }

    void transition(UUID versionId, String from, String to, String reason) {
        jdbc.sql("INSERT INTO assessment_transition (id, tenant_id, version_id, from_state, to_state, actor_id, reason) VALUES (?, ?, ?, ?, ?, ?, ?)")
                .params(Ids.newId(), TenantContext.requireTenantId(), versionId, from, to, me(), reason == null || reason.isBlank() ? null : truncate(reason.strip(), 500)).update();
    }

    private void touch(UUID id) {
        jdbc.sql("UPDATE assessment SET updated_at = now(), version = version + 1 WHERE id = ?").param(id).update();
    }

    static String required(String v, String field, int max) {
        if (v == null || v.isBlank()) throw ApiException.badRequest("invalid_" + field, "Enter a " + field + ".");
        String s = v.strip();
        if (s.length() > max) throw ApiException.badRequest("invalid_" + field, "The " + field + " is longer than " + max + " characters.");
        return s;
    }

    static String optional(String v, int max) {
        if (v == null || v.isBlank()) return null;
        String s = v.strip();
        if (s.length() > max) throw ApiException.badRequest("too_long", "Text longer than " + max + " characters.");
        return s;
    }

    static String truncate(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }

    static String abbreviate(String s) {
        return s == null ? "(question)" : truncate(s, 60);
    }

    static Instant ts(ResultSet rs, String col) throws SQLException {
        var t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }
}
