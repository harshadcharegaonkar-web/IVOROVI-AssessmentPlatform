package com.ivorovi.admissions.internal;

import static com.ivorovi.admissions.internal.AdmissionAccess.MANAGE;
import static com.ivorovi.admissions.internal.AdmissionAccess.READ;
import static com.ivorovi.admissions.internal.AdmissionAccess.actor;
import static com.ivorovi.admissions.internal.AdmissionAccess.tenant;
import static com.ivorovi.admissions.internal.AdmissionAccess.text;

import com.ivorovi.academics.AcademicCatalogue;
import com.ivorovi.platform.ids.Ids;
import com.ivorovi.platform.web.ApiException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Admission cycles, the offerings of a cycle and their typed eligibility rules. */
@Service
public class CycleService {

    public enum CycleStatus { DRAFT, OPEN, CLOSED, COMPLETED }

    public enum RuleKind { MIN_QUALIFYING_PERCENT, AGE_RANGE, REQUIRED_DOCUMENT, MIN_TEST_PERCENT }

    static final Map<CycleStatus, Set<CycleStatus>> TRANSITIONS = Map.of(
            CycleStatus.DRAFT, Set.of(CycleStatus.OPEN),
            CycleStatus.OPEN, Set.of(CycleStatus.CLOSED),
            CycleStatus.CLOSED, Set.of(CycleStatus.OPEN, CycleStatus.COMPLETED),
            CycleStatus.COMPLETED, Set.of());

    /** Built-in document categories applicants can upload (codes, labels). */
    public static final Map<String, String> DOCUMENT_CATEGORIES = orderedCategories();

    @Schema(name = "AdmissionCycle")
    public record Cycle(UUID id, UUID institutionId, String institutionName, UUID academicYearId, String academicYearName, UUID portalId, String portalKey,
                        String code, String name, Instant opensAt, Instant closesAt, CycleStatus status, int offerings, int applications, long version,
                        boolean canManage) {}

    @Schema(name = "AdmissionRule")
    public record Rule(UUID id, RuleKind kind, Map<String, Object> parameters, String description) {}

    @Schema(name = "AdmissionOffering")
    public record Offering(UUID id, UUID cycleId, UUID programId, String programName, UUID courseId, String courseName, UUID batchId, String batchCode,
                           int seats, Map<String, Integer> categoryQuotas, int qualifyingWeight, int testWeight, UUID entranceScheduleId,
                           List<String> requiredDocuments, String status, List<Rule> rules, int applications, long version) {}

    public record CycleCommand(UUID institutionId, UUID academicYearId, UUID portalId, String code, String name, Instant opensAt, Instant closesAt, Long version) {}

    public record OfferingCommand(UUID courseId, UUID batchId, Integer seats, Map<String, Integer> categoryQuotas, Integer qualifyingWeight, Integer testWeight,
                                  List<String> requiredDocuments, Long version) {}

    public record RuleCommand(RuleKind kind, Map<String, Object> parameters) {}

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcClient jdbc;
    private final AdmissionAccess access;
    private final AcademicCatalogue academics;

    CycleService(JdbcClient jdbc, AdmissionAccess access, AcademicCatalogue academics) {
        this.jdbc = jdbc;
        this.access = access;
        this.academics = academics;
    }

    // ================================================================================================ cycles

    private static final String CYCLE_SELECT = """
            SELECT c.*, u.name AS institution_name, y.name AS year_name, p.portal_key,
                   (SELECT count(*) FROM admission_offering o WHERE o.cycle_id = c.id AND o.status = 'ACTIVE') AS offerings,
                   (SELECT count(*) FROM admission_application a WHERE a.cycle_id = c.id AND a.status <> 'DRAFT') AS applications
            FROM admission_cycle c JOIN org_unit u ON u.id = c.institution_id JOIN academic_year y ON y.id = c.academic_year_id
            JOIN exam_portal p ON p.id = c.portal_id""";

    private Cycle mapCycle(ResultSet rs, int n) throws SQLException {
        UUID inst = rs.getObject("institution_id", UUID.class);
        return new Cycle(rs.getObject("id", UUID.class), inst, rs.getString("institution_name"), rs.getObject("academic_year_id", UUID.class),
                rs.getString("year_name"), rs.getObject("portal_id", UUID.class), rs.getString("portal_key"), rs.getString("code"), rs.getString("name"),
                rs.getTimestamp("opens_at").toInstant(), rs.getTimestamp("closes_at").toInstant(), CycleStatus.valueOf(rs.getString("status")),
                rs.getInt("offerings"), rs.getInt("applications"), rs.getLong("version"), access.can(MANAGE, inst));
    }

    @Transactional(readOnly = true)
    public List<Cycle> list() {
        if (!access.anywhere(READ)) throw new org.springframework.security.access.AccessDeniedException("Missing permission " + READ);
        return jdbc.sql(CYCLE_SELECT + " ORDER BY c.opens_at DESC, c.id").query(this::mapCycle).list().stream()
                .filter(c -> access.can(READ, c.institutionId())).toList();
    }

    @Transactional(readOnly = true)
    public Cycle get(UUID id) {
        access.cycleInstitution(id);
        return jdbc.sql(CYCLE_SELECT + " WHERE c.id = ?").param(id).query(this::mapCycle).single();
    }

    @Transactional
    public Cycle create(CycleCommand c) {
        if (c.institutionId() == null) throw ApiException.badRequest("invalid_institutionId", "Choose the university or college.");
        String type = jdbc.sql("SELECT unit_type FROM org_unit WHERE id = ?").param(c.institutionId()).query(String.class).optional().orElse(null);
        if (!"UNIVERSITY".equals(type) && !"COLLEGE".equals(type)) throw ApiException.badRequest("invalid_institutionId", "Choose a university or college.");
        access.require(MANAGE, c.institutionId(), "Institution", c.institutionId());
        validateCycle(c);
        UUID id = Ids.newId();
        try {
            jdbc.sql("""
                    INSERT INTO admission_cycle (id, tenant_id, institution_id, academic_year_id, portal_id, code, name, opens_at, closes_at, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(id, tenant(), c.institutionId(), c.academicYearId(), c.portalId(), c.code().strip(), c.name().strip(),
                            java.sql.Timestamp.from(c.opensAt()), java.sql.Timestamp.from(c.closesAt()), actor()).update();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("code_in_use", "Another admission cycle of this institution uses this code.");
        }
        access.audit("admission.cycle.created", "admission_cycle", id, Map.of("institutionId", c.institutionId().toString(), "code", c.code().strip()));
        return get(id);
    }

    private void validateCycle(CycleCommand c) {
        String code = c.code() == null ? "" : c.code().strip();
        if (!code.matches("^[A-Za-z0-9][A-Za-z0-9._/-]{0,29}$")) throw ApiException.badRequest("invalid_code", "Use up to 30 letters, digits and . _ / -");
        text(c.name(), 150, "name", true);
        if (c.opensAt() == null || c.closesAt() == null || !c.closesAt().isAfter(c.opensAt())) {
            throw ApiException.badRequest("invalid_window", "The cycle must close after it opens.");
        }
        AcademicCatalogue.Year y = academics.year(c.academicYearId() == null ? UUID.randomUUID() : c.academicYearId());
        if ("COMPLETED".equals(y.status())) throw ApiException.badRequest("invalid_academicYearId", "This academic year is completed.");
        if (c.portalId() == null || jdbc.sql("SELECT count(*) FROM exam_portal WHERE id = ? AND status = 'ACTIVE' AND self_registration")
                .param(c.portalId()).query(Long.class).single() == 0) {
            throw ApiException.badRequest("invalid_portalId", "Choose an active exam portal that accepts self-registration (applicants sign up there).");
        }
    }

    @Transactional
    public Cycle update(UUID id, CycleCommand c) {
        UUID inst = access.cycleInstitution(id);
        access.require(MANAGE, inst, "Admission cycle", id);
        record Row(String status, long version, UUID institution) {}
        Row r = jdbc.sql("SELECT status, version, institution_id FROM admission_cycle WHERE id = ? FOR UPDATE").param(id)
                .query((rs, n) -> new Row(rs.getString(1), rs.getLong(2), rs.getObject(3, UUID.class))).single();
        AdmissionAccess.version(c.version(), r.version());
        if ("COMPLETED".equals(r.status())) throw ApiException.conflict("cycle_completed", "A completed cycle never changes.");
        CycleCommand merged = new CycleCommand(r.institution(), c.academicYearId(), c.portalId(), c.code(), c.name(), c.opensAt(), c.closesAt(), c.version());
        validateCycle(merged);
        jdbc.sql("""
                UPDATE admission_cycle SET academic_year_id = ?, portal_id = ?, code = ?, name = ?, opens_at = ?, closes_at = ?, updated_at = now(), version = version + 1
                WHERE id = ?""").params(c.academicYearId(), c.portalId(), c.code().strip(), c.name().strip(), java.sql.Timestamp.from(c.opensAt()),
                java.sql.Timestamp.from(c.closesAt()), id).update();
        access.audit("admission.cycle.updated", "admission_cycle", id, Map.of("code", c.code().strip()));
        return get(id);
    }

    @Transactional
    public Cycle setStatus(UUID id, CycleStatus to, Long version) {
        UUID inst = access.cycleInstitution(id);
        access.require(MANAGE, inst, "Admission cycle", id);
        record Row(CycleStatus status, long version) {}
        Row r = jdbc.sql("SELECT status, version FROM admission_cycle WHERE id = ? FOR UPDATE").param(id)
                .query((rs, n) -> new Row(CycleStatus.valueOf(rs.getString(1)), rs.getLong(2))).single();
        AdmissionAccess.version(version, r.version());
        if (to == null || !TRANSITIONS.get(r.status()).contains(to)) {
            throw ApiException.conflict("invalid_status_transition", "A " + r.status().name().toLowerCase() + " cycle cannot become " + (to == null ? "?" : to.name().toLowerCase()) + ".");
        }
        if (to == CycleStatus.OPEN && jdbc.sql("SELECT count(*) FROM admission_offering WHERE cycle_id = ? AND status = 'ACTIVE'").param(id).query(Long.class).single() == 0) {
            throw ApiException.conflict("no_offerings", "Add at least one programme on offer before opening the cycle.");
        }
        jdbc.sql("UPDATE admission_cycle SET status = ?, updated_at = now(), version = version + 1 WHERE id = ?").params(to.name(), id).update();
        access.audit("admission.cycle.status_changed", "admission_cycle", id, Map.of("from", r.status().name(), "to", to.name()));
        return get(id);
    }

    // ================================================================================================ offerings

    private static final String OFFERING_SELECT = """
            SELECT o.*, p.name AS program_name, c.name AS course_name, b.code AS batch_code,
                   (SELECT count(*) FROM admission_application a WHERE a.offering_id = o.id AND a.status <> 'DRAFT') AS applications
            FROM admission_offering o JOIN program p ON p.id = o.program_id JOIN course c ON c.id = o.course_id LEFT JOIN batch b ON b.id = o.batch_id""";

    @Transactional(readOnly = true)
    public List<Offering> offerings(UUID cycleId) {
        access.cycleInstitution(cycleId);
        return jdbc.sql(OFFERING_SELECT + " WHERE o.cycle_id = ? ORDER BY c.name, o.id").param(cycleId).query(this::mapOffering).list();
    }

    @Transactional(readOnly = true)
    public Offering offering(UUID id) {
        access.cycleInstitution(cycleOf(id));
        return jdbc.sql(OFFERING_SELECT + " WHERE o.id = ?").param(id).query(this::mapOffering).single();
    }

    UUID cycleOf(UUID offeringId) {
        return jdbc.sql("SELECT cycle_id FROM admission_offering WHERE id = ?").param(offeringId).query(UUID.class).optional()
                .orElseThrow(() -> ApiException.notFound("Offering"));
    }

    @SuppressWarnings("unchecked")
    private Offering mapOffering(ResultSet rs, int n) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        Map<String, Integer> quotas = new LinkedHashMap<>();
        ((Map<String, Object>) JSON.readValue(rs.getString("category_quotas"), Map.class)).forEach((k, v) -> quotas.put(k, ((Number) v).intValue()));
        java.sql.Array docs = rs.getArray("required_documents");
        return new Offering(id, rs.getObject("cycle_id", UUID.class), rs.getObject("program_id", UUID.class), rs.getString("program_name"),
                rs.getObject("course_id", UUID.class), rs.getString("course_name"), rs.getObject("batch_id", UUID.class), rs.getString("batch_code"),
                rs.getInt("seats"), quotas, rs.getInt("qualifying_weight"), rs.getInt("test_weight"), rs.getObject("entrance_schedule_id", UUID.class),
                docs == null ? List.of() : List.of((String[]) docs.getArray()), rs.getString("status"), rules(id), rs.getInt("applications"), rs.getLong("version"));
    }

    @Transactional
    public Offering createOffering(UUID cycleId, OfferingCommand c) {
        UUID inst = access.cycleInstitution(cycleId);
        access.require(MANAGE, inst, "Admission cycle", cycleId);
        requireEditableCycle(cycleId);
        Validated v = validateOffering(inst, c);
        UUID id = Ids.newId();
        try {
            jdbc.sql("""
                    INSERT INTO admission_offering (id, tenant_id, cycle_id, program_id, course_id, batch_id, seats, category_quotas, qualifying_weight, test_weight,
                        required_documents) VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?)""")
                    .params(id, tenant(), cycleId, v.course.programId(), v.course.id(), c.batchId(), v.seats, v.quotas, v.qualifying, v.test, v.documents).update();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("offering_exists", "This course is already on offer in this cycle.");
        }
        access.audit("admission.offering.created", "admission_offering", id, Map.of("cycleId", cycleId.toString(), "courseId", v.course.id().toString(),
                "seats", String.valueOf(v.seats)));
        return offering(id);
    }

    @Transactional
    public Offering updateOffering(UUID id, OfferingCommand c) {
        UUID cycleId = cycleOf(id);
        UUID inst = access.cycleInstitution(cycleId);
        access.require(MANAGE, inst, "Offering", id);
        requireEditableCycle(cycleId);
        long current = jdbc.sql("SELECT version FROM admission_offering WHERE id = ? FOR UPDATE").param(id).query(Long.class).single();
        AdmissionAccess.version(c.version(), current);
        UUID course = jdbc.sql("SELECT course_id FROM admission_offering WHERE id = ?").param(id).query(UUID.class).single();
        Validated v = validateOffering(inst, new OfferingCommand(course, c.batchId(), c.seats(), c.categoryQuotas(), c.qualifyingWeight(), c.testWeight(),
                c.requiredDocuments(), c.version()));
        jdbc.sql("""
                UPDATE admission_offering SET batch_id = ?, seats = ?, category_quotas = CAST(? AS jsonb), qualifying_weight = ?, test_weight = ?, required_documents = ?,
                    updated_at = now(), version = version + 1 WHERE id = ?""").params(c.batchId(), v.seats, v.quotas, v.qualifying, v.test, v.documents, id).update();
        access.audit("admission.offering.updated", "admission_offering", id, Map.of("seats", String.valueOf(v.seats)));
        return offering(id);
    }

    private void requireEditableCycle(UUID cycleId) {
        String status = jdbc.sql("SELECT status FROM admission_cycle WHERE id = ? FOR UPDATE").param(cycleId).query(String.class).single();
        if (!Set.of("DRAFT", "OPEN").contains(status)) throw ApiException.conflict("cycle_not_editable", "Offerings change only while the cycle is a draft or open.");
    }

    private record Validated(AcademicCatalogue.Course course, int seats, String quotas, int qualifying, int test, String[] documents) {}

    private Validated validateOffering(UUID institution, OfferingCommand c) {
        if (c.courseId() == null) throw ApiException.badRequest("invalid_courseId", "Choose the course on offer.");
        AcademicCatalogue.Course course = academics.course(c.courseId());
        AcademicCatalogue.Program program = academics.program(course.programId());
        if (!"ACTIVE".equals(course.status()) || !"ACTIVE".equals(program.status())) throw ApiException.badRequest("course_inactive", "The course or its programme is inactive.");
        boolean within = jdbc.sql("SELECT count(*) FROM org_unit o, org_unit i WHERE o.id = ? AND i.id = ? AND o.path <@ i.path")
                .params(program.ownerOrgUnitId(), institution).query(Long.class).single() > 0;
        if (!within) throw ApiException.badRequest("program_outside_institution", "This programme is not offered by the cycle's institution.");
        if (c.batchId() != null) {
            AcademicCatalogue.Batch b = academics.batch(c.batchId());
            if (!b.courseId().equals(course.id())) throw ApiException.badRequest("invalid_batchId", "The batch belongs to another course.");
            if (!Set.of("ACTIVE", "PLANNED").contains(b.status())) throw ApiException.badRequest("invalid_batchId", "The batch is not open for enrolment.");
        }
        int seats = c.seats() == null ? 0 : c.seats();
        if (seats < 1 || seats > 10000) throw ApiException.badRequest("invalid_seats", "Seats: 1 to 10,000.");
        int q = c.qualifyingWeight() == null ? 100 : c.qualifyingWeight(), t = c.testWeight() == null ? 100 - q : c.testWeight();
        if (q < 0 || t < 0 || q + t != 100) throw ApiException.badRequest("invalid_weights", "The qualifying-marks and entrance-test weights must add up to 100.");
        Map<String, Integer> quotas = c.categoryQuotas() == null ? Map.of() : c.categoryQuotas();
        int sum = 0;
        for (Map.Entry<String, Integer> e : quotas.entrySet()) {
            if (e.getKey() == null || !e.getKey().matches("^[A-Z0-9_]{1,40}$") || e.getValue() == null || e.getValue() < 0) {
                throw ApiException.badRequest("invalid_quota", "Quotas map category codes to a number of seats.");
            }
            sum += e.getValue();
        }
        if (sum > seats) throw ApiException.badRequest("invalid_quota", "The category quotas exceed the seats.");
        List<String> docs = c.requiredDocuments() == null ? List.of() : c.requiredDocuments();
        for (String d : docs) {
            if (!DOCUMENT_CATEGORIES.containsKey(d)) throw ApiException.badRequest("invalid_requiredDocuments", "Unknown document category " + d + ".");
        }
        return new Validated(course, seats, JSON.writeValueAsString(quotas), q, t, docs.stream().distinct().toArray(String[]::new));
    }

    // ================================================================================================ rules

    List<Rule> rules(UUID offeringId) {
        return jdbc.sql("SELECT id, kind, parameters::text FROM admission_eligibility_rule WHERE offering_id = ? ORDER BY position, id").param(offeringId)
                .query((rs, n) -> {
                    RuleKind kind = RuleKind.valueOf(rs.getString(2));
                    @SuppressWarnings("unchecked")
                    Map<String, Object> p = JSON.readValue(rs.getString(3), Map.class);
                    return new Rule(rs.getObject(1, UUID.class), kind, p, describe(kind, p));
                }).list();
    }

    static String describe(RuleKind kind, Map<String, Object> p) {
        return switch (kind) {
            case MIN_QUALIFYING_PERCENT -> "Qualifying marks at least " + p.get("min") + "%";
            case MIN_TEST_PERCENT -> "Entrance test at least " + p.get("min") + "%";
            case AGE_RANGE -> "Age " + p.get("minYears") + "–" + p.get("maxYears") + " years on " + p.get("onDate");
            case REQUIRED_DOCUMENT -> "Verified document: " + DOCUMENT_CATEGORIES.getOrDefault(String.valueOf(p.get("category")), String.valueOf(p.get("category")));
        };
    }

    /** Replaces the rules of an offering (only before any application was screened). */
    @Transactional
    public Offering replaceRules(UUID offeringId, List<RuleCommand> rules) {
        UUID cycleId = cycleOf(offeringId);
        UUID inst = access.cycleInstitution(cycleId);
        access.require(MANAGE, inst, "Offering", offeringId);
        requireEditableCycle(cycleId);
        if (jdbc.sql("SELECT count(*) FROM admission_screening s JOIN admission_application a ON a.id = s.application_id WHERE a.offering_id = ?")
                .param(offeringId).query(Long.class).single() > 0) {
            throw ApiException.conflict("rules_in_use", "Applications of this offering were already screened; the rules can no longer change.");
        }
        List<RuleCommand> list = rules == null ? List.of() : rules;
        if (list.size() > 20) throw ApiException.badRequest("too_many_rules", "At most 20 rules.");
        List<Object[]> rows = new ArrayList<>();
        for (RuleCommand r : list) rows.add(new Object[] {r.kind(), validateRule(r)});
        jdbc.sql("DELETE FROM admission_eligibility_rule WHERE offering_id = ?").param(offeringId).update();
        int pos = 1;
        for (Object[] r : rows) {
            jdbc.sql("INSERT INTO admission_eligibility_rule (id, tenant_id, offering_id, kind, parameters, position) VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?)")
                    .params(Ids.newId(), tenant(), offeringId, ((RuleKind) r[0]).name(), r[1], pos++).update();
        }
        access.audit("admission.rules.replaced", "admission_offering", offeringId, Map.of("rules", String.valueOf(rows.size())));
        return offering(offeringId);
    }

    private static String validateRule(RuleCommand r) {
        if (r.kind() == null) throw ApiException.badRequest("invalid_rule", "Choose the kind of rule.");
        Map<String, Object> p = r.parameters() == null ? Map.of() : r.parameters();
        Map<String, Object> clean = new LinkedHashMap<>();
        switch (r.kind()) {
            case MIN_QUALIFYING_PERCENT, MIN_TEST_PERCENT -> clean.put("min", percent(p.get("min")));
            case AGE_RANGE -> {
                int min = whole(p.get("minYears"), 10, 80), max = whole(p.get("maxYears"), 10, 80);
                if (min > max) throw ApiException.badRequest("invalid_rule", "The minimum age is above the maximum.");
                java.time.LocalDate on;
                try {
                    on = java.time.LocalDate.parse(String.valueOf(p.get("onDate")));
                } catch (RuntimeException e) {
                    throw ApiException.badRequest("invalid_rule", "Give the date the age is computed on (YYYY-MM-DD).");
                }
                clean.put("minYears", min);
                clean.put("maxYears", max);
                clean.put("onDate", on.toString());
            }
            case REQUIRED_DOCUMENT -> {
                String cat = String.valueOf(p.get("category"));
                if (!DOCUMENT_CATEGORIES.containsKey(cat)) throw ApiException.badRequest("invalid_rule", "Unknown document category.");
                clean.put("category", cat);
            }
        }
        return JSON.writeValueAsString(clean);
    }

    private static BigDecimal percent(Object v) {
        try {
            BigDecimal b = new BigDecimal(String.valueOf(v));
            if (b.signum() < 0 || b.compareTo(BigDecimal.valueOf(100)) > 0 || b.scale() > 2) throw new NumberFormatException();
            return b;
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("invalid_rule", "A percentage from 0 to 100 with at most two decimals.");
        }
    }

    private static int whole(Object v, int min, int max) {
        try {
            int i = Integer.parseInt(String.valueOf(v));
            if (i < min || i > max) throw new NumberFormatException();
            return i;
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("invalid_rule", "Ages from " + min + " to " + max + " years.");
        }
    }

    private static Map<String, String> orderedCategories() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("PHOTOGRAPH", "Photograph");
        m.put("MARK_SHEET", "Qualifying mark sheet");
        m.put("ID_PROOF", "Identity proof");
        m.put("CATEGORY_CERTIFICATE", "Category certificate");
        m.put("OTHER", "Other document");
        return java.util.Collections.unmodifiableMap(m);
    }
}
