package com.ivorovi.questions.internal;

import com.ivorovi.approvals.ApprovalHandler;
import com.ivorovi.approvals.ApprovalService;
import com.ivorovi.platform.audit.AuditLog;
import com.ivorovi.platform.ids.Ids;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.platform.web.ApiException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Question banks and approval policies. Strengthening a policy applies at once; weakening a bank policy or an
 * institution floor needs a second person through the four-eyes framework (type {@code question.policy}) and is refused
 * while questions of the affected banks are in review. ADMISSION_EXAM banks are always HIGH_STAKES (database CHECK).
 */
@Service
public class BankService implements ApprovalHandler {

    public static final String POLICY_APPROVAL = "question.policy";
    static final Set<String> USES = Set.of("ADMISSION_EXAM", "ADMISSION_SCREENING", "ACADEMIC", "PLACEMENT", "PRACTICE");

    @Schema(name = "QuestionBankPolicy")
    public record Policy(String approvalPolicy, int requiredReviews, boolean approverDistinct) {}

    @Schema(name = "QuestionBank")
    public record Bank(UUID id, UUID orgUnitId, String orgUnitName, String code, String name, String description, List<String> intendedUses,
                       Policy policy, Policy effectivePolicy, boolean freeTags, BigDecimal defaultMarks, BigDecimal defaultNegativeMarks, String status,
                       Map<String, Long> counts, Instant createdAt, long version, boolean canManage, boolean canAuthor, UUID pendingPolicyApprovalId) {}

    @Schema(name = "QuestionPolicyFloor")
    public record Floor(UUID id, UUID orgUnitId, String orgUnitName, Policy policy, long version) {}

    public record BankInput(UUID orgUnitId, String code, String name, String description, List<String> intendedUses, Policy policy, Boolean freeTags,
                            BigDecimal defaultMarks, BigDecimal defaultNegativeMarks, String status, Long version) {}

    private final JdbcClient jdbc;
    private final QuestionAccess access;
    private final AuditLog audit;
    private final ApprovalService approvals;

    BankService(JdbcClient jdbc, QuestionAccess access, AuditLog audit, @Lazy ApprovalService approvals) {
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
        this.approvals = approvals;
    }

    // ================================================================================================ reading

    private static final String SELECT = """
            SELECT b.*, ou.name AS unit_name, e.required_reviews AS e_reviews, e.approver_distinct AS e_distinct, e.high_stakes AS e_high,
                   (SELECT count(*) FROM question q WHERE q.bank_id = b.id AND q.status = 'ACTIVE') AS active_questions,
                   (SELECT count(*) FROM question q JOIN question_version v ON v.id = q.current_version_id WHERE q.bank_id = b.id AND v.state = 'DRAFT') AS drafts,
                   (SELECT count(*) FROM question q JOIN question_version v ON v.id = q.current_version_id WHERE q.bank_id = b.id AND v.state IN ('IN_REVIEW', 'REVIEWED')) AS in_review,
                   (SELECT count(*) FROM question q WHERE q.bank_id = b.id AND q.approved_version_id IS NOT NULL AND q.status = 'ACTIVE') AS approved,
                   (SELECT a.id FROM approval_request a WHERE a.type = 'question.policy' AND a.subject_type = 'question_bank' AND a.subject_id = b.id::text AND a.status = 'PENDING' LIMIT 1) AS pending_approval
            FROM question_bank b LEFT JOIN org_unit ou ON ou.id = b.org_unit_id
            CROSS JOIN LATERAL question_effective_policy(b.id) e""";

    @Transactional(readOnly = true)
    public List<Bank> list() {
        return jdbc.sql(SELECT + " ORDER BY b.name").query(this::row).list().stream()
                .filter(b -> access.can(QuestionAccess.READ, b.orgUnitId())).toList();
    }

    @Transactional(readOnly = true)
    public Bank get(UUID id) {
        access.bank(id);
        return find(id);
    }

    Bank find(UUID id) {
        return jdbc.sql(SELECT + " WHERE b.id = ?").param(id).query(this::row).optional().orElseThrow(() -> ApiException.notFound("Question bank"));
    }

    private Bank row(ResultSet rs, int n) throws SQLException {
        UUID unit = rs.getObject("org_unit_id", UUID.class);
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("active", rs.getLong("active_questions"));
        counts.put("draft", rs.getLong("drafts"));
        counts.put("inReview", rs.getLong("in_review"));
        counts.put("approved", rs.getLong("approved"));
        return new Bank(rs.getObject("id", UUID.class), unit, rs.getString("unit_name"), rs.getString("code"), rs.getString("name"), rs.getString("description"),
                strings(rs.getArray("intended_uses")),
                new Policy(rs.getString("approval_policy"), rs.getInt("required_reviews"), rs.getBoolean("approver_distinct")),
                new Policy(rs.getBoolean("e_high") ? "HIGH_STAKES" : "STANDARD", rs.getInt("e_reviews"), rs.getBoolean("e_distinct")),
                rs.getBoolean("free_tags"), rs.getBigDecimal("default_marks"), rs.getBigDecimal("default_negative_marks"), rs.getString("status"), counts,
                rs.getTimestamp("created_at").toInstant(), rs.getLong("version"), access.can(QuestionAccess.MANAGE, unit), access.can(QuestionAccess.AUTHOR, unit),
                rs.getObject("pending_approval", UUID.class));
    }

    static List<String> strings(Array a) throws SQLException {
        return a == null ? List.of() : Arrays.asList((String[]) a.getArray());
    }

    // ================================================================================================ writing

    @Transactional
    public Bank create(BankInput in) {
        if (!access.can(QuestionAccess.MANAGE, in.orgUnitId())) throw new AccessDeniedException("Missing permission " + QuestionAccess.MANAGE);
        List<String> uses = uses(in.intendedUses());
        Policy p = normalize(in.policy(), uses);
        UUID id = Ids.newId();
        try {
            jdbc.sql("""
                    INSERT INTO question_bank (id, tenant_id, org_unit_id, code, name, description, intended_uses, approval_policy, required_reviews,
                        approver_distinct, free_tags, default_marks, default_negative_marks, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?::text[], ?, ?, ?, ?, ?, ?, ?)""")
                    .params(id, TenantContext.requireTenantId(), in.orgUnitId(), in.code().strip(), in.name().strip(), blank(in.description()),
                            "{" + String.join(",", uses) + "}", p.approvalPolicy(), p.requiredReviews(), p.approverDistinct(),
                            in.freeTags() == null || in.freeTags(), marks(in.defaultMarks(), BigDecimal.ONE), marks(in.defaultNegativeMarks(), BigDecimal.ZERO),
                            access.me()).update();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("bank_code_taken", "A question bank with this code already exists.");
        }
        audit.recordSuccess("question.bank.created", "question_bank", id.toString(), Map.of("code", in.code().strip(), "policy", p.approvalPolicy(),
                "requiredReviews", String.valueOf(p.requiredReviews()), "uses", String.join(",", uses)));
        return find(id);
    }

    /** Updates details; policy changes that weaken need four-eyes approval (returned bank shows the pending request). */
    @Transactional
    public Bank update(UUID id, BankInput in) {
        QuestionAccess.BankRef ref = access.bank(id);
        access.require(QuestionAccess.MANAGE, ref);
        Bank before = find(id);
        if (in.version() == null || in.version() != before.version()) throw ApiException.conflict("concurrent_modification", "This bank was changed. Reload and try again.");
        List<String> uses = uses(in.intendedUses());
        Policy p = normalize(in.policy(), uses);
        boolean weaker = weaker(before.policy(), p);
        int n = jdbc.sql("""
                UPDATE question_bank SET name = ?, description = ?, intended_uses = ?::text[], free_tags = ?, default_marks = ?, default_negative_marks = ?,
                    status = ?, updated_at = now(), version = version + 1""" + (weaker ? " " : ", approval_policy = ?, required_reviews = ?, approver_distinct = ? ") + "WHERE id = ? AND version = ?")
                .params(weaker
                        ? new Object[] {in.name().strip(), blank(in.description()), "{" + String.join(",", uses) + "}", in.freeTags() == null || in.freeTags(),
                                marks(in.defaultMarks(), BigDecimal.ONE), marks(in.defaultNegativeMarks(), BigDecimal.ZERO), status(in.status()), id, in.version()}
                        : new Object[] {in.name().strip(), blank(in.description()), "{" + String.join(",", uses) + "}", in.freeTags() == null || in.freeTags(),
                                marks(in.defaultMarks(), BigDecimal.ONE), marks(in.defaultNegativeMarks(), BigDecimal.ZERO), status(in.status()),
                                p.approvalPolicy(), p.requiredReviews(), p.approverDistinct(), id, in.version()})
                .update();
        if (n == 0) throw ApiException.conflict("concurrent_modification", "This bank was changed. Reload and try again.");
        audit.recordSuccess("question.bank.updated", "question_bank", id.toString(), Map.of("uses", String.join(",", uses), "status", status(in.status())));
        if (!before.policy().equals(p)) {
            if (weaker) {
                requireNothingInReview("b.id = ?", id);
                Map<String, Object> payload = Map.of("bankId", id.toString(), "approvalPolicy", p.approvalPolicy(), "requiredReviews", p.requiredReviews(),
                        "approverDistinct", p.approverDistinct(), "from", before.policy().approvalPolicy() + "/" + before.policy().requiredReviews() + "/"
                                + before.policy().approverDistinct());
                approvals.request(POLICY_APPROVAL, "question_bank", id.toString(), "Weaken approval policy of question bank " + before.code(), payload,
                        QuestionAccess.MANAGE, ref.orgUnitId(), Duration.ofDays(7));
                audit.recordSuccess("question.bank.policy_weakening_requested", "question_bank", id.toString(), Map.of("to", describe(p)));
            } else {
                audit.recordSuccess("question.bank.policy_changed", "question_bank", id.toString(), Map.of("from", describe(before.policy()), "to", describe(p)));
            }
        }
        return find(id);
    }

    private void requireNothingInReview(String bankCondition, Object param) {
        long inReview = jdbc.sql("SELECT count(*) FROM question_version v JOIN question q ON q.id = v.question_id JOIN question_bank b ON b.id = q.bank_id "
                + "WHERE v.state IN ('IN_REVIEW', 'REVIEWED') AND " + bankCondition).param(param).query(Long.class).single();
        if (inReview > 0) throw ApiException.conflict("questions_in_review", "Questions are in review under this policy. A weaker policy can be requested once no question is in review.");
    }

    // ================================================================================================ floors

    @Transactional(readOnly = true)
    public List<Floor> floors() {
        return jdbc.sql("SELECT f.*, ou.name AS unit_name FROM question_policy_floor f LEFT JOIN org_unit ou ON ou.id = f.org_unit_id ORDER BY ou.name NULLS FIRST")
                .query((rs, n) -> new Floor(rs.getObject("id", UUID.class), rs.getObject("org_unit_id", UUID.class), rs.getString("unit_name"),
                        new Policy(rs.getString("minimum_policy"), rs.getInt("minimum_reviews"), rs.getBoolean("approver_distinct")), rs.getLong("version")))
                .list().stream().filter(f -> access.can(QuestionAccess.READ, f.orgUnitId())).toList();
    }

    @Transactional
    public Floor setFloor(UUID unit, Policy requested) {
        if (!access.can(QuestionAccess.MANAGE, unit)) throw new AccessDeniedException("Missing permission " + QuestionAccess.MANAGE);
        Policy p = normalize(requested, List.of());
        record Cur(UUID id, Policy p) {}
        Cur cur = jdbc.sql("SELECT id, minimum_policy, minimum_reviews, approver_distinct FROM question_policy_floor WHERE org_unit_id IS NOT DISTINCT FROM ?")
                .param(unit).query((rs, n) -> new Cur(rs.getObject(1, UUID.class), new Policy(rs.getString(2), rs.getInt(3), rs.getBoolean(4)))).optional().orElse(null);
        String unitKey = unit == null ? "organization" : unit.toString();
        if (cur != null && weaker(cur.p(), p)) {
            requireNothingInReview(unit == null ? "TRUE AND ?::text IS NOT NULL" : "b.org_unit_id IN (SELECT d.id FROM org_unit d, org_unit f WHERE f.id = ? AND d.path <@ f.path)",
                    unit == null ? "x" : unit);
            approvals.request(POLICY_APPROVAL, "question_policy_floor", cur.id().toString(), "Weaken minimum question approval policy for " + unitKey,
                    Map.of("floorId", cur.id().toString(), "approvalPolicy", p.approvalPolicy(), "requiredReviews", p.requiredReviews(),
                            "approverDistinct", p.approverDistinct()), QuestionAccess.MANAGE, unit, Duration.ofDays(7));
            audit.recordSuccess("question.policy_floor.weakening_requested", "question_policy_floor", cur.id().toString(), Map.of("to", describe(p)));
            return floors().stream().filter(f -> f.id().equals(cur.id())).findFirst().orElseThrow();
        }
        UUID id = cur == null ? Ids.newId() : cur.id();
        if (cur == null) {
            jdbc.sql("INSERT INTO question_policy_floor (id, tenant_id, org_unit_id, minimum_policy, minimum_reviews, approver_distinct, updated_by) VALUES (?, ?, ?, ?, ?, ?, ?)")
                    .params(id, TenantContext.requireTenantId(), unit, p.approvalPolicy(), p.requiredReviews(), p.approverDistinct(), access.me()).update();
        } else {
            jdbc.sql("UPDATE question_policy_floor SET minimum_policy = ?, minimum_reviews = ?, approver_distinct = ?, updated_by = ?, updated_at = now(), version = version + 1 WHERE id = ?")
                    .params(p.approvalPolicy(), p.requiredReviews(), p.approverDistinct(), access.me(), id).update();
        }
        audit.recordSuccess("question.policy_floor.set", "question_policy_floor", id.toString(), Map.of("unit", unitKey, "policy", describe(p)));
        return floors().stream().filter(f -> f.id().equals(id)).findFirst().orElseThrow();
    }

    // ================================================================================================ four-eyes callback

    @Override
    public String type() {
        return POLICY_APPROVAL;
    }

    @Override
    public String label() {
        return "Weaker question approval policy";
    }

    @Override
    public void onApproved(ApprovalService.Request r) {
        Map<String, Object> p = r.payload();
        String policy = String.valueOf(p.get("approvalPolicy"));
        int reviews = ((Number) p.get("requiredReviews")).intValue();
        boolean distinct = Boolean.TRUE.equals(p.get("approverDistinct"));
        try {
            if ("question_bank".equals(r.subjectType())) {
                jdbc.sql("UPDATE question_bank SET approval_policy = ?, required_reviews = ?, approver_distinct = ?, updated_at = now(), version = version + 1 WHERE id = CAST(? AS uuid)")
                        .params(policy, reviews, distinct, r.subjectId()).update();
            } else {
                jdbc.sql("UPDATE question_policy_floor SET minimum_policy = ?, minimum_reviews = ?, approver_distinct = ?, updated_at = now(), version = version + 1 WHERE id = CAST(? AS uuid)")
                        .params(policy, reviews, distinct, r.subjectId()).update();
            }
        } catch (DataIntegrityViolationException e) {
            // e.g. an ADMISSION_EXAM bank can never become STANDARD
            throw ApiException.conflict("policy_not_allowed", "This policy is not allowed for the bank's intended uses.");
        }
        audit.recordSuccess("question.policy.weakened", r.subjectType(), r.subjectId(), Map.of("approval", r.id().toString(), "policy", policy + "/" + reviews + "/" + distinct));
    }

    // ================================================================================================ helpers

    static Policy normalize(Policy p, List<String> uses) {
        String policy = p == null || p.approvalPolicy() == null ? "STANDARD" : p.approvalPolicy();
        if (!Set.of("STANDARD", "HIGH_STAKES").contains(policy)) throw ApiException.badRequest("invalid_policy", "Approval policy must be STANDARD or HIGH_STAKES.");
        if (uses.contains("ADMISSION_EXAM") && !"HIGH_STAKES".equals(policy)) {
            throw ApiException.badRequest("high_stakes_required", "Admission examination banks must use the high-stakes policy (author, reviewer and approver are three different people).");
        }
        int reviews = p == null || p.requiredReviews() < 1 ? 1 : p.requiredReviews();
        if (reviews > 3) throw ApiException.badRequest("invalid_policy", "At most three reviewers can be required.");
        boolean distinct = "HIGH_STAKES".equals(policy) || (p != null && p.approverDistinct());
        return new Policy(policy, reviews, distinct);
    }

    static boolean weaker(Policy before, Policy after) {
        return ("HIGH_STAKES".equals(before.approvalPolicy()) && "STANDARD".equals(after.approvalPolicy()))
                || after.requiredReviews() < before.requiredReviews()
                || (before.approverDistinct() && !after.approverDistinct());
    }

    private static List<String> uses(List<String> in) {
        List<String> uses = in == null ? List.of() : in.stream().distinct().toList();
        for (String u : uses) if (!USES.contains(u)) throw ApiException.badRequest("invalid_use", "Unknown intended use " + u + ".");
        return uses;
    }

    private static String status(String s) {
        String st = s == null ? "ACTIVE" : s;
        if (!Set.of("ACTIVE", "ARCHIVED").contains(st)) throw ApiException.badRequest("invalid_status", "Status must be ACTIVE or ARCHIVED.");
        return st;
    }

    private static BigDecimal marks(BigDecimal v, BigDecimal dflt) {
        BigDecimal m = v == null ? dflt : v;
        if (m.signum() < 0 || m.compareTo(BigDecimal.valueOf(1000)) > 0) throw ApiException.badRequest("invalid_marks", "Marks must be between 0 and 1000.");
        return m;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String describe(Policy p) {
        return p.approvalPolicy() + ", " + p.requiredReviews() + " review(s)" + (p.approverDistinct() ? ", approver distinct" : "");
    }
}
