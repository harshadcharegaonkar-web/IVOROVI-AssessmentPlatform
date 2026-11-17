package com.ivorovi.admissions.internal;

import static com.ivorovi.admissions.internal.AdmissionAccess.MANAGE;
import static com.ivorovi.admissions.internal.AdmissionAccess.READ;
import static com.ivorovi.admissions.internal.AdmissionAccess.actor;
import static com.ivorovi.admissions.internal.AdmissionAccess.tenant;

import com.ivorovi.platform.crypto.FieldCipher;
import com.ivorovi.platform.ids.Ids;
import com.ivorovi.platform.web.ApiException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Staff side of applications: lists, detail, documents, screening by the offering's typed rules and the staff decision.
 * Screening is advice: every rule's inputs and threshold are recorded and shown; ELIGIBLE and INELIGIBLE become
 * SHORTLISTED or REJECTED only through a person's decision (an override of the rules needs a reason). Personal details
 * are shown only to holders of {@code admission.manage}.
 */
@Service
public class ApplicationService {

    public enum Decision { SHORTLIST, REJECT }

    static final Set<String> SCREENABLE = Set.of("SUBMITTED", "ELIGIBLE", "INELIGIBLE");

    @Schema(name = "AdmissionApplicationSummary")
    public record Summary(UUID id, String applicationNo, UUID cycleId, UUID offeringId, String courseName, String applicantName, String status,
                          BigDecimal qualifyingPercent, BigDecimal testPercent, BigDecimal compositeScore, Instant submittedAt, int documents, int documentsClean,
                          int documentsVerified, UUID studentId) {}

    @Schema(name = "AdmissionApplicantPersonal")
    public record Personal(LocalDate dateOfBirth, String gender, String nationality, String mobile, String address, String category) {}

    @Schema(name = "AdmissionScreeningResult")
    public record Screening(UUID ruleId, String kind, String result, String detail, Instant evaluatedAt) {}

    @Schema(name = "AdmissionApplicationEvent")
    public record Event(String fromStatus, String toStatus, String reason, String actorKind, String actorName, Instant at) {}

    @Schema(name = "AdmissionApplicationDetail")
    public record Detail(Summary application, String qualifyingBoard, Personal personal, boolean personalVisible, List<Screening> screening,
                         String screeningOutcome, List<AdmissionDocuments.Document> documents, List<Event> history, List<String> allowedDecisions, long version) {}

    private final JdbcClient jdbc;
    private final AdmissionAccess access;
    private final AdmissionDocuments documents;
    private final CycleService cycles;
    private final FieldCipher cipher;

    ApplicationService(JdbcClient jdbc, AdmissionAccess access, AdmissionDocuments documents, CycleService cycles, FieldCipher cipher) {
        this.jdbc = jdbc;
        this.access = access;
        this.documents = documents;
        this.cycles = cycles;
        this.cipher = cipher;
    }

    private static final String SUMMARY = """
            SELECT a.id, a.application_no, a.cycle_id, a.offering_id, co.name AS course_name,
                   concat_ws(' ', p.first_name, p.middle_name, p.last_name) AS applicant_name, a.status, a.qualifying_percent, a.test_percent,
                   a.composite_score, a.submitted_at, a.student_id,
                   (SELECT count(*) FROM admission_document d WHERE d.application_id = a.id AND d.status = 'ACTIVE') AS docs,
                   (SELECT count(*) FROM admission_document d WHERE d.application_id = a.id AND d.status = 'ACTIVE' AND d.scan_status = 'CLEAN') AS docs_clean,
                   (SELECT count(*) FROM admission_document d WHERE d.application_id = a.id AND d.status = 'ACTIVE' AND d.verification_status = 'VERIFIED') AS docs_verified
            FROM admission_application a JOIN applicant p ON p.id = a.applicant_id JOIN admission_offering o ON o.id = a.offering_id
            JOIN course co ON co.id = o.course_id""";

    static Summary mapSummary(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        java.sql.Timestamp sub = rs.getTimestamp("submitted_at");
        return new Summary(rs.getObject("id", UUID.class), rs.getString("application_no"), rs.getObject("cycle_id", UUID.class), rs.getObject("offering_id", UUID.class),
                rs.getString("course_name"), rs.getString("applicant_name"), rs.getString("status"), rs.getBigDecimal("qualifying_percent"),
                rs.getBigDecimal("test_percent"), rs.getBigDecimal("composite_score"), sub == null ? null : sub.toInstant(), rs.getInt("docs"), rs.getInt("docs_clean"),
                rs.getInt("docs_verified"), rs.getObject("student_id", UUID.class));
    }

    record Ref(UUID id, UUID cycle, UUID offering, UUID institution, String status, long version) {}

    Ref ref(UUID applicationId, boolean lock) {
        Ref r = jdbc.sql("""
                SELECT a.id, a.cycle_id, a.offering_id, c.institution_id, a.status, a.version FROM admission_application a
                JOIN admission_cycle c ON c.id = a.cycle_id WHERE a.id = ?""" + (lock ? " FOR UPDATE OF a" : "")).param(applicationId)
                .query((rs, n) -> new Ref(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getObject(4, UUID.class),
                        rs.getString(5), rs.getLong(6))).optional().orElseThrow(() -> ApiException.notFound("Application"));
        if (!access.can(READ, r.institution()) || "DRAFT".equals(r.status())) throw ApiException.notFound("Application");   // drafts stay private
        return r;
    }

    @Transactional(readOnly = true)
    public List<Summary> list(UUID cycleId, UUID offeringId, String status) {
        access.cycleInstitution(cycleId);
        List<Object> p = new ArrayList<>(List.of(cycleId));
        StringBuilder where = new StringBuilder(" WHERE a.cycle_id = ? AND a.status <> 'DRAFT'");
        if (offeringId != null) { where.append(" AND a.offering_id = ?"); p.add(offeringId); }
        if (status != null && !status.isBlank()) { where.append(" AND a.status = ?"); p.add(status); }
        return jdbc.sql(SUMMARY + where + " ORDER BY a.composite_score DESC NULLS LAST, a.submitted_at, a.id LIMIT 5000").params(p)
                .query(ApplicationService::mapSummary).list();
    }

    @Transactional(readOnly = true)
    public Detail detail(UUID id) {
        Ref r = ref(id, false);
        boolean personal = access.can(MANAGE, r.institution());
        Summary s = jdbc.sql(SUMMARY + " WHERE a.id = ?").param(id).query(ApplicationService::mapSummary).single();
        String board = jdbc.sql("SELECT qualifying_board FROM admission_application WHERE id = ?").param(id).query(String.class).optional().orElse(null);
        Personal pd = personal ? personal(id) : null;
        List<Screening> screening = latestScreening(id);
        return new Detail(s, board, pd, personal, screening, outcome(screening), documents.list(id), history(id),
                personal ? allowed(r.status(), !screening.isEmpty()) : List.of(), r.version());
    }

    private Personal personal(UUID applicationId) {
        UUID t = tenant();
        return jdbc.sql("""
                SELECT p.date_of_birth_enc, p.gender, p.nationality, p.mobile_enc, p.address_enc, p.category_enc
                FROM admission_application a JOIN applicant p ON p.id = a.applicant_id WHERE a.id = ?""").param(applicationId)
                .query((rs, n) -> {
                    String dob = dec(rs.getString(1), ApplicantService.DOB, t);
                    return new Personal(dob == null ? null : LocalDate.parse(dob), rs.getString(2), rs.getString(3), dec(rs.getString(4), ApplicantService.MOBILE, t),
                            dec(rs.getString(5), ApplicantService.ADDRESS, t), dec(rs.getString(6), ApplicantService.CATEGORY, t));
                }).single();
    }

    private String dec(String v, String field, UUID t) {
        return v == null ? null : cipher.decrypt(v, field, t);
    }

    List<Screening> latestScreening(UUID applicationId) {
        return jdbc.sql("""
                SELECT rule_id, kind, result, detail, evaluated_at FROM admission_screening WHERE application_id = ? AND evaluation_id =
                  (SELECT evaluation_id FROM admission_screening WHERE application_id = ? ORDER BY evaluated_at DESC LIMIT 1) ORDER BY kind, rule_id""")
                .params(applicationId, applicationId)
                .query((rs, n) -> new Screening(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getTimestamp(5).toInstant())).list();
    }

    static String outcome(List<Screening> s) {
        if (s.isEmpty()) return "NOT_SCREENED";
        if (s.stream().anyMatch(x -> "FAIL".equals(x.result()))) return "INELIGIBLE";
        if (s.stream().anyMatch(x -> "MANUAL".equals(x.result()))) return "NEEDS_REVIEW";
        return "ELIGIBLE";
    }

    private List<Event> history(UUID applicationId) {
        return jdbc.sql("""
                SELECT e.from_status, e.to_status, e.reason, e.actor_kind, u.display_name, e.occurred_at FROM admission_application_event e
                LEFT JOIN user_account u ON u.id = e.actor_id WHERE e.application_id = ? ORDER BY e.occurred_at, e.id""").param(applicationId)
                .query((rs, n) -> new Event(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getTimestamp(6).toInstant())).list();
    }

    static List<String> allowed(String status, boolean screened) {
        if (!screened) return List.of();
        return switch (status) {
            case "ELIGIBLE", "INELIGIBLE", "SUBMITTED" -> List.of(Decision.SHORTLIST.name(), Decision.REJECT.name());
            default -> List.of();
        };
    }

    // ================================================================================================ screening

    /** Screens one application by its offering's rules; records every rule's inputs. Returns the screening outcome. */
    @Transactional
    public Detail screen(UUID id) {
        Ref r = ref(id, true);
        access.require(MANAGE, r.institution(), "Application", id);
        if (!SCREENABLE.contains(r.status())) throw ApiException.conflict("not_screenable", "Only submitted applications awaiting a decision are screened.");
        screenOne(r);
        return detail(id);
    }

    /** Screens every submitted application of an offering awaiting a decision. */
    @Transactional
    public Map<String, Integer> screenOffering(UUID offeringId) {
        UUID cycle = cycles.cycleOf(offeringId);
        UUID inst = access.cycleInstitution(cycle);
        access.require(MANAGE, inst, "Offering", offeringId);
        List<UUID> ids = jdbc.sql("SELECT id FROM admission_application WHERE offering_id = ? AND status IN ('SUBMITTED', 'ELIGIBLE', 'INELIGIBLE') ORDER BY id FOR UPDATE")
                .param(offeringId).query(UUID.class).list();
        Map<String, Integer> counts = new java.util.TreeMap<>();
        for (UUID id : ids) counts.merge(screenOne(ref(id, false)), 1, Integer::sum);
        access.audit("admission.offering.screened", "admission_offering", offeringId, Map.of("applications", String.valueOf(ids.size())));
        return counts;
    }

    private String screenOne(Ref r) {
        UUID evaluation = Ids.newId();
        List<CycleService.Rule> rules = cycles.rules(r.offering());
        record A(BigDecimal qualifying, String dobEnc) {}
        A a = jdbc.sql("SELECT a.qualifying_percent, p.date_of_birth_enc FROM admission_application a JOIN applicant p ON p.id = a.applicant_id WHERE a.id = ?")
                .param(r.id()).query((rs, n) -> new A(rs.getBigDecimal(1), rs.getString(2))).single();
        List<String[]> rows = new ArrayList<>();
        for (CycleService.Rule rule : rules) {
            if (rule.kind() == CycleService.RuleKind.MIN_TEST_PERCENT) continue;       // applied when the entrance-test results are imported
            rows.add(evaluate(rule, r.id(), a.qualifying(), a.dobEnc()));
        }
        if (rows.isEmpty()) rows.add(new String[] {null, "NO_RULES", "MANUAL", "No eligibility rules are defined: decide manually"});
        for (String[] row : rows) {
            jdbc.sql("INSERT INTO admission_screening (id, tenant_id, application_id, rule_id, kind, result, detail, evaluation_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(Ids.newId(), tenant(), r.id(), row[0] == null ? null : UUID.fromString(row[0]), row[1], row[2], row[3], evaluation).update();
        }
        String outcome = outcome(rows.stream().map(x -> new Screening(null, x[1], x[2], x[3], null)).toList());
        String to = switch (outcome) { case "ELIGIBLE" -> "ELIGIBLE"; case "INELIGIBLE" -> "INELIGIBLE"; default -> "SUBMITTED"; };
        if (!to.equals(r.status())) {
            jdbc.sql("UPDATE admission_application SET status = ?, updated_at = now(), version = version + 1 WHERE id = ?").params(to, r.id()).update();
            access.event(r.id(), r.status(), to, "Screening: " + outcome.toLowerCase().replace('_', ' ') + " (awaiting a staff decision)", "SYSTEM");
        }
        access.audit("admission.application.screened", "admission_application", r.id(), Map.of("outcome", outcome, "rules", String.valueOf(rows.size())));
        return outcome;
    }

    private String[] evaluate(CycleService.Rule rule, UUID applicationId, BigDecimal qualifying, String dobEnc) {
        String id = rule.id().toString(), kind = rule.kind().name();
        Map<String, Object> p = rule.parameters();
        return switch (rule.kind()) {
            case MIN_QUALIFYING_PERCENT -> {
                BigDecimal min = new BigDecimal(String.valueOf(p.get("min")));
                if (qualifying == null) yield new String[] {id, kind, "MANUAL", "Qualifying marks not given (required at least " + min + "%)"};
                boolean ok = qualifying.compareTo(min) >= 0;
                yield new String[] {id, kind, ok ? "PASS" : "FAIL", "Qualifying marks " + qualifying + "% " + (ok ? ">=" : "<") + " " + min + "%"};
            }
            case AGE_RANGE -> {
                int min = ((Number) p.get("minYears")).intValue(), max = ((Number) p.get("maxYears")).intValue();
                LocalDate on = LocalDate.parse(String.valueOf(p.get("onDate")));
                String dob = dobEnc == null ? null : cipher.decrypt(dobEnc, ApplicantService.DOB, tenant());
                if (dob == null) yield new String[] {id, kind, "MANUAL", "Date of birth not given (age " + min + "–" + max + " on " + on + ")"};
                int age = Period.between(LocalDate.parse(dob), on).getYears();
                boolean ok = age >= min && age <= max;
                // the detail shows the age, not the date of birth
                yield new String[] {id, kind, ok ? "PASS" : "FAIL", "Age " + age + " on " + on + " " + (ok ? "within" : "outside") + " " + min + "–" + max};
            }
            case REQUIRED_DOCUMENT -> {
                String cat = String.valueOf(p.get("category"));
                String label = CycleService.DOCUMENT_CATEGORIES.getOrDefault(cat, cat);
                List<String[]> docs = jdbc.sql("SELECT scan_status, verification_status FROM admission_document WHERE application_id = ? AND category = ? AND status = 'ACTIVE'")
                        .params(applicationId, cat).query((rs, n) -> new String[] {rs.getString(1), rs.getString(2)}).list();
                if (docs.isEmpty()) yield new String[] {id, kind, "FAIL", label + ": not uploaded"};
                String scan = docs.getFirst()[0], verification = docs.getFirst()[1];
                if ("INFECTED".equals(scan)) yield new String[] {id, kind, "FAIL", label + ": failed the malware scan"};
                // D-19: pending or failed scans are never treated as clean
                if (!"CLEAN".equals(scan)) yield new String[] {id, kind, "MANUAL", label + ": malware scan " + scan.toLowerCase() + " (not accepted yet)"};
                if ("REJECTED".equals(verification)) yield new String[] {id, kind, "FAIL", label + ": rejected by staff"};
                if (!"VERIFIED".equals(verification)) yield new String[] {id, kind, "MANUAL", label + ": scanned clean, awaiting verification"};
                yield new String[] {id, kind, "PASS", label + ": scanned clean and verified"};
            }
            case MIN_TEST_PERCENT -> throw new IllegalStateException();
        };
    }

    // ================================================================================================ decisions

    /** A person's decision after screening. Overriding the screening outcome (or deciding a NEEDS_REVIEW case) needs a reason. */
    @Transactional
    public Detail decide(UUID id, Decision decision, String reason, Long version) {
        Ref r = ref(id, true);
        access.require(MANAGE, r.institution(), "Application", id);
        AdmissionAccess.version(version, r.version());
        List<Screening> screening = latestScreening(id);
        if (decision == null || !allowed(r.status(), !screening.isEmpty()).contains(decision.name())) {
            throw ApiException.conflict("invalid_decision", screening.isEmpty() ? "Screen the application first." : "This decision is not possible now.");
        }
        String outcome = outcome(screening);
        boolean follows = (decision == Decision.SHORTLIST && "ELIGIBLE".equals(outcome)) || (decision == Decision.REJECT && "INELIGIBLE".equals(outcome));
        String why = AdmissionAccess.text(reason, 500, "reason", !follows);
        String to = decision == Decision.SHORTLIST ? "SHORTLISTED" : "REJECTED";
        jdbc.sql("UPDATE admission_application SET status = ?, updated_at = now(), version = version + 1 WHERE id = ?").params(to, id).update();
        access.event(id, r.status(), to, (follows ? "Decision follows screening" : "Decision overrides screening (" + outcome.toLowerCase().replace('_', ' ') + ")")
                + (why == null ? "" : ": " + why), "STAFF");
        access.audit("admission.application.decided", "admission_application", id, Map.of("decision", decision.name(), "screening", outcome,
                "override", String.valueOf(!follows)));
        return detail(id);
    }

    // ================================================================================================ documents

    @Transactional
    public AdmissionDocuments.Download download(UUID id, UUID documentId) {
        Ref r = ref(id, false);
        return documents.download(r.id(), documentId);
    }

    @Transactional
    public Detail verifyDocument(UUID id, UUID documentId, boolean verified, String note) {
        Ref r = ref(id, true);
        access.require(MANAGE, r.institution(), "Application", id);
        documents.verify(id, documentId, verified, note, actor());
        return detail(id);
    }

    @Transactional
    public Detail rescanDocument(UUID id, UUID documentId) {
        Ref r = ref(id, true);
        access.require(MANAGE, r.institution(), "Application", id);
        documents.rescan(id, documentId);
        return detail(id);
    }
}
