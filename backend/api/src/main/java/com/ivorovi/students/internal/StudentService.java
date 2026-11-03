package com.ivorovi.students.internal;

import static com.ivorovi.students.internal.StudentAccess.actor;
import static com.ivorovi.students.internal.StudentAccess.conflict;
import static com.ivorovi.students.internal.StudentAccess.invalid;
import static com.ivorovi.students.internal.StudentAccess.tenant;
import static com.ivorovi.students.internal.StudentAccess.version;

import com.ivorovi.identity.StudentAccounts;
import com.ivorovi.platform.ids.Ids;
import com.ivorovi.platform.security.Permission;
import com.ivorovi.platform.web.ApiException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Student profiles, search, lifecycle status, history and the configurable option lists. Admission records,
 * enrolments, guardians and documents have their own services; all of them change status only through
 * {@link #transition}, which enforces the lifecycle model below and records every change.
 *
 * <pre>
 * PROSPECTIVE → ADMITTED (admission confirmed) | CANCELLED
 * ADMITTED    → ENROLLED (initial enrolment)   | WITHDRAWN | CANCELLED
 * ENROLLED    → ACTIVE | SUSPENDED | WITHDRAWN
 * ACTIVE      → SUSPENDED | WITHDRAWN | GRADUATED
 * SUSPENDED   → ACTIVE (reinstated) | WITHDRAWN
 * GRADUATED   → ALUMNI
 * WITHDRAWN, CANCELLED, ALUMNI: final
 * </pre>
 */
@Service
public class StudentService {

    public enum Status { PROSPECTIVE, ADMITTED, ENROLLED, ACTIVE, SUSPENDED, WITHDRAWN, GRADUATED, ALUMNI, CANCELLED }
    public enum Gender { FEMALE, MALE, OTHER, UNDISCLOSED }
    public enum OptionKind { ADMISSION_TYPE, ADMISSION_CATEGORY, DOCUMENT_CATEGORY }

    static final Map<Status, Set<Status>> TRANSITIONS = StudentLifecycle.TRANSITIONS;
    /** Reached only through an admission (ADMITTED) or an enrolment (ENROLLED), never set directly. */
    static final Set<Status> DERIVED = EnumSet.of(Status.ADMITTED, Status.ENROLLED);

    @Schema(name = "StudentSummary")
    public record Summary(UUID id, String studentNo, String registrationNo, String rollNo, String firstName, String middleName, String lastName,
                          String fullName, Status status, boolean active, UUID institutionId, String institutionName, String departmentName,
                          UUID programId, String programName, UUID courseId, String courseName, UUID batchId, String batchCode,
                          String academicYearName, Integer termNumber, Instant updatedAt) {}

    @Schema(name = "StudentPersonal")
    public record Personal(LocalDate dateOfBirth, Gender gender, String nationality, String personalEmail, String mobile, String alternatePhone,
                           String currentAddress, String permanentAddress) {}

    @Schema(name = "StudentAccount")
    public record AccountView(UUID userId, String email, String status, String invitationStatus) {}

    @Schema(name = "StudentPermissions")
    public record Abilities(boolean manage, boolean readPersonal, boolean readDocuments, boolean manageDocuments, boolean manageAccount) {}

    @Schema(name = "StudentDetail")
    public record Detail(Summary student, String previousInstitutionRef, String institutionalEmail, Personal personal, boolean personalVisible,
                         LocalDate statusEffectiveOn, List<Status> allowedTransitions, AdmissionService.AdmissionView admission,
                         EnrolmentService.EnrolmentView currentEnrolment, AccountView account, long guardianCount, long documentCount,
                         long pendingScanCount, Instant createdAt, long version, Abilities can) {}

    @Schema(name = "StudentHistoryEntry")
    public record HistoryEntry(Instant at, String kind, String type, String summary, String fromStatus, String toStatus, LocalDate effectiveOn,
                               String reason, String actorName) {}

    @Schema(name = "StudentOption")
    public record Option(OptionKind kind, String code, String label, boolean builtIn, boolean active) {}

    public record Page<T>(List<T> items, long total) {}

    public record PersonalCommand(LocalDate dateOfBirth, Gender gender, String nationality, String personalEmail, String mobile, String alternatePhone,
                                  String currentAddress, String permanentAddress) {}

    public record Command(UUID institutionId, String studentNo, Integer admissionYear, String registrationNo, String rollNo, String previousInstitutionRef,
                          String firstName, String middleName, String lastName, String institutionalEmail, PersonalCommand personal, Long version) {}

    public record Query(String q, UUID institutionId, UUID campusId, UUID departmentId, UUID programId, UUID courseId, UUID batchId,
                        UUID academicYearId, Integer termNumber, Status status, Boolean active) {}

    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]{1,64}@[^\\s@]+\\.[^\\s@]{2,}$");
    private static final Pattern PHONE = Pattern.compile("^\\+?[0-9][0-9 -]{5,18}[0-9]$");

    private final JdbcClient jdbc;
    private final StudentAccess access;
    private final StudentNumbering numbering;
    private final StudentAccounts accounts;
    private final AdmissionService admissions;
    private final EnrolmentService enrolments;
    private final StudentLifecycle lifecycle;
    private final StudentOptions options;

    StudentService(JdbcClient jdbc, StudentAccess access, StudentNumbering numbering, StudentAccounts accounts, AdmissionService admissions,
                   EnrolmentService enrolments, StudentLifecycle lifecycle, StudentOptions options) {
        this.jdbc = jdbc;
        this.access = access;
        this.numbering = numbering;
        this.accounts = accounts;
        this.admissions = admissions;
        this.enrolments = enrolments;
        this.lifecycle = lifecycle;
        this.options = options;
    }

    // ================================================================================================ search

    static final String FROM = """
            student s JOIN org_unit su ON su.id = s.scope_unit_id JOIN org_unit inst ON inst.id = s.institution_id
            LEFT JOIN enrolment e ON e.student_id = s.id AND e.is_current
            LEFT JOIN program p ON p.id = e.program_id LEFT JOIN course c ON c.id = e.course_id LEFT JOIN batch b ON b.id = e.batch_id
            LEFT JOIN academic_year y ON y.id = e.academic_year_id LEFT JOIN org_unit d ON d.id = coalesce(e.department_id, s.scope_unit_id)""";
    static final String SELECT = """
            SELECT s.id, s.student_no, s.registration_no, s.roll_no, s.first_name, s.middle_name, s.last_name, s.status, s.active,
                   s.institution_id, inst.name AS institution_name, d.name AS department_name, e.program_id, p.name AS program_name, e.course_id,
                   c.name AS course_name, e.batch_id, b.code AS batch_code, y.name AS year_name, e.term_number, s.updated_at
            FROM\s""" + FROM;
    private static final Map<String, String> SORTS = Map.of("name", "lower(s.last_name || ' ' || s.first_name)", "studentNo", "lower(s.student_no)",
            "status", "s.status", "updatedAt", "s.updated_at", "batch", "b.code");

    @Transactional(readOnly = true)
    public Page<Summary> search(Query q, int page, int size, String sort, boolean asc) {
        access.requireAnyRead();
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (q.status() != null) { where.append(" AND s.status = ?"); params.add(q.status().name()); }
        if (q.active() != null) { where.append(" AND s.active = ?"); params.add(q.active()); }
        for (UUID unit : new UUID[] {q.institutionId(), q.campusId(), q.departmentId()}) {
            if (unit != null) {
                where.append(" AND (s.institution_id = ? OR su.path <@ (SELECT path FROM org_unit WHERE id = ?))");
                params.add(unit);
                params.add(unit);
            }
        }
        if (q.programId() != null) { where.append(" AND e.program_id = ?"); params.add(q.programId()); }
        if (q.courseId() != null) { where.append(" AND e.course_id = ?"); params.add(q.courseId()); }
        if (q.batchId() != null) { where.append(" AND e.batch_id = ?"); params.add(q.batchId()); }
        if (q.academicYearId() != null) { where.append(" AND e.academic_year_id = ?"); params.add(q.academicYearId()); }
        if (q.termNumber() != null) { where.append(" AND e.term_number = ?"); params.add(q.termNumber()); }
        if (q.q() != null && !q.q().isBlank()) {
            String like = "%" + q.q().trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            where.append(" AND (concat_ws(' ', s.first_name, s.middle_name, s.last_name) ILIKE ? OR concat_ws(' ', s.first_name, s.last_name) ILIKE ?"
                    + " OR s.student_no ILIKE ? OR s.registration_no ILIKE ? OR s.roll_no ILIKE ?)");
            for (int i = 0; i < 5; i++) params.add(like);
        }
        if (!access.appendVisibility("su.path", where, params)) return new Page<>(List.of(), 0);
        long total = jdbc.sql("SELECT count(*) FROM " + FROM + where).params(params).query(Long.class).single();
        List<Object> p = new ArrayList<>(params);
        p.add(size);
        p.add((long) page * size);
        List<Summary> items = jdbc.sql(SELECT + where + " ORDER BY " + SORTS.getOrDefault(sort, SORTS.get("name")) + (asc ? " ASC" : " DESC")
                + " NULLS LAST, s.id LIMIT ? OFFSET ?").params(p).query(StudentService::mapSummary).list();
        return new Page<>(items, total);
    }

    static Summary mapSummary(ResultSet rs, int n) throws SQLException {
        String first = rs.getString("first_name"), middle = rs.getString("middle_name"), last = rs.getString("last_name");
        return new Summary(rs.getObject("id", UUID.class), rs.getString("student_no"), rs.getString("registration_no"), rs.getString("roll_no"),
                first, middle, last, fullName(first, middle, last), Status.valueOf(rs.getString("status")), rs.getBoolean("active"),
                rs.getObject("institution_id", UUID.class), rs.getString("institution_name"), rs.getString("department_name"),
                rs.getObject("program_id", UUID.class), rs.getString("program_name"), rs.getObject("course_id", UUID.class), rs.getString("course_name"),
                rs.getObject("batch_id", UUID.class), rs.getString("batch_code"), rs.getString("year_name"), (Integer) rs.getObject("term_number"),
                instant(rs, "updated_at"));
    }

    static String fullName(String first, String middle, String last) {
        return String.join(" ", java.util.stream.Stream.of(first, middle, last).filter(x -> x != null && !x.isBlank()).toList());
    }

    // ================================================================================================ detail

    @Transactional(readOnly = true)
    public Detail detail(UUID id) {
        StudentAccess.StudentRef ref = access.readable(id);
        record Row(Summary summary, String previous, String instEmail, String dob, String gender, String nationality, String personalEmail,
                   String mobile, String alt, String current, String permanent, LocalDate effective, Instant created, long version) {}
        Row r = jdbc.sql(SELECT.replace("SELECT s.id,", "SELECT s.previous_institution_ref, s.institutional_email, s.date_of_birth_enc, s.gender, s.nationality, "
                        + "s.personal_email_enc, s.mobile_enc, s.alternate_phone_enc, s.current_address_enc, s.permanent_address_enc, s.status_effective_on, "
                        + "s.created_at, s.version, s.id,") + " WHERE s.id = ?")
                .param(id).query((rs, n) -> new Row(mapSummary(rs, n), rs.getString("previous_institution_ref"), rs.getString("institutional_email"),
                        rs.getString("date_of_birth_enc"), rs.getString("gender"), rs.getString("nationality"), rs.getString("personal_email_enc"),
                        rs.getString("mobile_enc"), rs.getString("alternate_phone_enc"), rs.getString("current_address_enc"),
                        rs.getString("permanent_address_enc"), rs.getObject("status_effective_on", LocalDate.class), instant(rs, "created_at"),
                        rs.getLong("version"))).single();
        UUID scope = ref.scopeUnitId();
        Abilities can = new Abilities(access.can(StudentAccess.MANAGE, scope), access.can(StudentAccess.PERSONAL, scope),
                access.can(StudentAccess.DOC_READ, scope), access.can(StudentAccess.DOC_MANAGE, scope), access.can(StudentAccess.ACCOUNT, scope));
        Personal personal = null;
        if (can.readPersonal()) {
            String dob = access.open(r.dob(), "student.date_of_birth");
            personal = new Personal(dob == null ? null : LocalDate.parse(dob), r.gender() == null ? null : Gender.valueOf(r.gender()), r.nationality(),
                    access.open(r.personalEmail(), "student.personal_email"), access.open(r.mobile(), "student.mobile"),
                    access.open(r.alt(), "student.alternate_phone"), access.open(r.current(), "student.current_address"),
                    access.open(r.permanent(), "student.permanent_address"));
        }
        Status status = r.summary().status();
        List<Status> allowed = TRANSITIONS.get(status).stream().filter(s -> !DERIVED.contains(s)).sorted().toList();
        AccountView account = accounts.find(ref.userAccountId())
                .map(a -> new AccountView(a.userId(), a.email(), a.status(), a.invitationStatus())).orElse(null);
        long guardians = jdbc.sql("SELECT count(*) FROM guardian WHERE student_id = ?").param(id).query(Long.class).single();
        long documents = jdbc.sql("SELECT count(*) FROM student_document WHERE student_id = ? AND status = 'ACTIVE'").param(id).query(Long.class).single();
        long pending = jdbc.sql("SELECT count(*) FROM student_document WHERE student_id = ? AND status = 'ACTIVE' AND scan_status = 'PENDING'").param(id).query(Long.class).single();
        return new Detail(r.summary(), r.previous(), r.instEmail(), personal, can.readPersonal(), r.effective(), allowed,
                admissions.currentFor(id, can.readPersonal()).orElse(null), enrolments.currentFor(id).orElse(null), account, guardians, documents, pending,
                r.created(), r.version(), can);
    }

    // ================================================================================================ create / update

    @Transactional
    public UUID create(Command c) {
        if (c.institutionId() == null) throw ApiException.badRequest("invalid_institutionId", "Choose the university or college.");
        StudentAccess.Unit inst = access.unit(c.institutionId())
                .filter(u -> Set.of("UNIVERSITY", "COLLEGE").contains(u.type()))
                .orElseThrow(() -> invalid("invalid_institutionId", "Choose a university or college of your organization."));
        if (!"ACTIVE".equals(inst.status())) throw invalid("inactive_institutionId", inst.name() + " is inactive.");
        access.requireAt(StudentAccess.MANAGE, inst.id());
        if (c.personal() != null && !access.can(StudentAccess.PERSONAL, inst.id())) {
            throw new org.springframework.security.access.AccessDeniedException("Missing permission " + StudentAccess.PERSONAL);
        }
        Fields f = fields(c);
        if (c.admissionYear() != null && (c.admissionYear() < 1990 || c.admissionYear() > 2200)) {
            throw ApiException.badRequest("invalid_admission_year", "Use a year between 1990 and 2200.");
        }
        String studentNo = numbering.assign(inst.id(), c.studentNo(), c.admissionYear());
        numbering.validateRegistration(inst.id(), f.registrationNo);
        UUID id = Ids.newId();
        try {
            jdbc.sql("""
                    INSERT INTO student (id, tenant_id, institution_id, scope_unit_id, student_no, registration_no, roll_no, previous_institution_ref,
                        first_name, middle_name, last_name, gender, nationality, date_of_birth_enc, personal_email_enc, institutional_email, mobile_enc,
                        alternate_phone_enc, current_address_enc, permanent_address_enc, status, status_effective_on, created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PROSPECTIVE', current_date, ?, ?)""")
                    .params(id, tenant(), inst.id(), inst.id(), studentNo, f.registrationNo, f.rollNo, f.previous, f.first, f.middle, f.last,
                            f.p.gender, f.p.nationality, f.p.dob, f.p.email, f.institutionalEmail, f.p.mobile, f.p.alt, f.p.current, f.p.permanent,
                            actor(), actor()).update();
        } catch (DuplicateKeyException e) {
            throw duplicate(e);
        }
        jdbc.sql("INSERT INTO student_status_history (id, tenant_id, student_id, from_status, to_status, effective_on, reason, actor_id) VALUES (?, ?, ?, NULL, 'PROSPECTIVE', current_date, 'Profile created', ?)")
                .params(Ids.newId(), tenant(), id, actor()).update();
        StudentAccess.StudentRef ref = access.student(id);
        access.changed("student.created", ref, "Profile created (" + studentNo + ")", Map.of("institutionId", inst.id().toString()));
        return id;
    }

    @Transactional
    public void update(UUID id, Command c) {
        StudentAccess.StudentRef ref = access.manageable(id, true);
        version(c.version(), ref.version());
        if (c.institutionId() != null && !c.institutionId().equals(ref.institutionId())) {
            throw ApiException.badRequest("institution_immutable", "The home institution of a student cannot change; use a transfer admission instead.");
        }
        if (c.studentNo() != null && !c.studentNo().trim().equalsIgnoreCase(ref.studentNo())) {
            throw ApiException.badRequest("student_no_immutable", "The student ID cannot change once assigned.");
        }
        boolean personalAllowed = access.can(StudentAccess.PERSONAL, ref.scopeUnitId());
        if (c.personal() != null && !personalAllowed) {
            throw new org.springframework.security.access.AccessDeniedException("Missing permission " + StudentAccess.PERSONAL);
        }
        Fields f = fields(c);
        numbering.validateRegistration(ref.institutionId(), f.registrationNo);
        List<String> changed = changedFields(id, f, c.personal() != null);
        try {
            if (c.personal() != null) {
                jdbc.sql("""
                        UPDATE student SET registration_no = ?, roll_no = ?, previous_institution_ref = ?, first_name = ?, middle_name = ?, last_name = ?,
                            institutional_email = ?, gender = ?, nationality = ?, date_of_birth_enc = ?, personal_email_enc = ?, mobile_enc = ?,
                            alternate_phone_enc = ?, current_address_enc = ?, permanent_address_enc = ?, updated_at = now(), updated_by = ?, version = version + 1
                        WHERE id = ?""")
                        .params(f.registrationNo, f.rollNo, f.previous, f.first, f.middle, f.last, f.institutionalEmail, f.p.gender, f.p.nationality,
                                f.p.dob, f.p.email, f.p.mobile, f.p.alt, f.p.current, f.p.permanent, actor(), id).update();
            } else {
                jdbc.sql("""
                        UPDATE student SET registration_no = ?, roll_no = ?, previous_institution_ref = ?, first_name = ?, middle_name = ?, last_name = ?,
                            institutional_email = ?, updated_at = now(), updated_by = ?, version = version + 1 WHERE id = ?""")
                        .params(f.registrationNo, f.rollNo, f.previous, f.first, f.middle, f.last, f.institutionalEmail, actor(), id).update();
            }
        } catch (DuplicateKeyException e) {
            throw duplicate(e);
        }
        access.changed("student.updated", ref, changed.isEmpty() ? "Profile saved (no changes)" : "Profile updated: " + String.join(", ", changed),
                Map.of("fields", String.join(",", changed)));
    }

    /** Names of changed fields (never their values). Encrypted fields are compared after decryption. */
    private List<String> changedFields(UUID id, Fields f, boolean personal) {
        Map<String, Object> before = jdbc.sql("""
                SELECT registration_no, roll_no, previous_institution_ref, first_name, middle_name, last_name, institutional_email, gender, nationality,
                       date_of_birth_enc, personal_email_enc, mobile_enc, alternate_phone_enc, current_address_enc, permanent_address_enc FROM student WHERE id = ?""")
                .param(id).query().singleRow();
        List<String> out = new ArrayList<>();
        cmp(out, "registration number", before.get("registration_no"), f.registrationNo);
        cmp(out, "roll number", before.get("roll_no"), f.rollNo);
        cmp(out, "previous institution ID", before.get("previous_institution_ref"), f.previous);
        cmp(out, "name", before.get("first_name") + "|" + before.get("middle_name") + "|" + before.get("last_name"), f.first + "|" + f.middle + "|" + f.last);
        cmp(out, "institutional email", before.get("institutional_email"), f.institutionalEmail);
        if (personal) {
            cmp(out, "gender", before.get("gender"), f.p.gender);
            cmp(out, "nationality", before.get("nationality"), f.p.nationality);
            cmp(out, "date of birth", access.open((String) before.get("date_of_birth_enc"), "student.date_of_birth"), f.p.plainDob);
            cmp(out, "personal email", access.open((String) before.get("personal_email_enc"), "student.personal_email"), f.p.plainEmail);
            cmp(out, "mobile", access.open((String) before.get("mobile_enc"), "student.mobile"), f.p.plainMobile);
            cmp(out, "alternate phone", access.open((String) before.get("alternate_phone_enc"), "student.alternate_phone"), f.p.plainAlt);
            cmp(out, "current address", access.open((String) before.get("current_address_enc"), "student.current_address"), f.p.plainCurrent);
            cmp(out, "permanent address", access.open((String) before.get("permanent_address_enc"), "student.permanent_address"), f.p.plainPermanent);
        }
        return out;
    }

    private static void cmp(List<String> out, String name, Object before, Object after) {
        if (!Objects.equals(before == null ? null : String.valueOf(before), after == null ? null : String.valueOf(after))) out.add(name);
    }

    private static class PersonalFields {
        String gender, nationality, dob, email, mobile, alt, current, permanent;
        String plainDob, plainEmail, plainMobile, plainAlt, plainCurrent, plainPermanent;
    }

    private record Fields(String registrationNo, String rollNo, String previous, String first, String middle, String last, String institutionalEmail,
                          PersonalFields p) {}

    private Fields fields(Command c) {
        String first = required("firstName", c.firstName(), 100);
        PersonalFields p = new PersonalFields();
        PersonalCommand pc = c.personal();
        if (pc != null) {
            if (pc.dateOfBirth() != null) {
                if (pc.dateOfBirth().isAfter(LocalDate.now().minusYears(10)) || pc.dateOfBirth().isBefore(LocalDate.of(1900, 1, 1))) {
                    throw ApiException.badRequest("invalid_dateOfBirth", "Enter a date of birth at least 10 years ago.");
                }
                p.plainDob = pc.dateOfBirth().toString();
            }
            p.gender = pc.gender() == null ? null : pc.gender().name();
            p.nationality = optional(pc.nationality(), 60);
            p.plainEmail = email("personalEmail", pc.personalEmail());
            p.plainMobile = phone("mobile", pc.mobile());
            p.plainAlt = phone("alternatePhone", pc.alternatePhone());
            p.plainCurrent = optional(pc.currentAddress(), 500);
            p.plainPermanent = optional(pc.permanentAddress(), 500);
            p.dob = access.seal(p.plainDob, "student.date_of_birth");
            p.email = access.seal(p.plainEmail, "student.personal_email");
            p.mobile = access.seal(p.plainMobile, "student.mobile");
            p.alt = access.seal(p.plainAlt, "student.alternate_phone");
            p.current = access.seal(p.plainCurrent, "student.current_address");
            p.permanent = access.seal(p.plainPermanent, "student.permanent_address");
        }
        String registration = optional(c.registrationNo(), 40);
        String roll = optional(c.rollNo(), 40);
        if (roll != null && !StudentNumbering.DEFAULT_NUMBER.matcher(roll).matches() && !roll.matches("^[0-9]{1,3}$")) {
            throw ApiException.badRequest("invalid_rollNo", "Use letters, digits and / - . _ for the roll number.");
        }
        return new Fields(registration, roll, optional(c.previousInstitutionRef(), 100), first, optional(c.middleName(), 100), optional(c.lastName(), 100),
                email("institutionalEmail", c.institutionalEmail()), p);
    }

    private static ApiException duplicate(DuplicateKeyException e) {
        String m = String.valueOf(e.getMessage());
        if (m.contains("student_registration_uq")) return conflict("registration_no_in_use", "Another student already has this registration number.");
        return conflict("student_no_in_use", "Another student already has this student ID.");
    }

    // ================================================================================================ lifecycle

    @Transactional
    public void changeStatus(UUID id, Status to, LocalDate effectiveOn, String reason, long version) {
        StudentAccess.StudentRef ref = access.manageable(id, true);
        version(version, ref.version());
        if (to == null) throw ApiException.badRequest("invalid_status", "Choose a status.");
        if (DERIVED.contains(to)) {
            throw invalid("status_requires_record", to == Status.ADMITTED ? "A student becomes admitted when an admission is confirmed."
                    : "A student becomes enrolled through an enrolment.");
        }
        String why = required("reason", reason, 500);
        Status from = Status.valueOf(ref.status());
        LocalDate effective = effectiveOn == null ? LocalDate.now() : effectiveOn;
        lifecycle.transition(ref, to, effective, why);
        switch (to) {
            case SUSPENDED -> enrolments.markCurrent(id, "SUSPENDED", null, why);
            case ACTIVE -> { if (from == Status.SUSPENDED) enrolments.markCurrent(id, "ACTIVE", null, why); }
            case WITHDRAWN -> enrolments.markCurrent(id, "WITHDRAWN", effective, why);
            case GRADUATED -> enrolments.markCurrent(id, "GRADUATED", effective, why);
            case CANCELLED -> admissions.cancelOpen(id, why);
            default -> { }
        }
        access.refreshScope(id);
    }

    @Transactional
    public void setActive(UUID id, boolean active, String reason, long version) {
        StudentAccess.StudentRef ref = access.lock(id);
        access.require(StudentAccess.MANAGE, ref);
        version(version, ref.version());
        if (ref.active() == active) throw conflict("invalid_status_transition", "The profile is already " + (active ? "active" : "deactivated") + ".");
        String why = required("reason", reason, 500);
        jdbc.sql("UPDATE student SET active = ?, updated_at = now(), updated_by = ?, version = version + 1 WHERE id = ?").params(active, actor(), id).update();
        access.changed(active ? "student.activated" : "student.deactivated", ref, (active ? "Profile activated" : "Profile deactivated") + ": " + why,
                Map.of("reason", why));
    }

    /** Physically deletes a profile created by mistake: only prospective/cancelled students without any academic record. */
    @Transactional
    public void delete(UUID id, long version) {
        StudentAccess.StudentRef ref = access.lock(id);
        access.require(StudentAccess.MANAGE, ref);
        version(version, ref.version());
        if (!Set.of("PROSPECTIVE", "CANCELLED").contains(ref.status())) {
            throw conflict("student_has_records", "Only prospective or cancelled profiles can be deleted. Withdraw or deactivate this student instead.");
        }
        long records = jdbc.sql("""
                SELECT (SELECT count(*) FROM enrolment WHERE student_id = :id) + (SELECT count(*) FROM admission_record WHERE student_id = :id AND status = 'CONFIRMED')
                     + (SELECT count(*) FROM student_document WHERE student_id = :id)""").param("id", id).query(Long.class).single();
        if (records > 0 || ref.userAccountId() != null) {
            throw conflict("student_has_records", "This profile has academic records, documents or a sign-in account and cannot be deleted. Deactivate it instead.");
        }
        access.auditOnly("student.deleted", ref, Map.of("reason", "created_in_error"));
        for (String t : List.of("guardian", "admission_record", "student_event", "student_status_history")) {
            jdbc.sql("DELETE FROM " + t + " WHERE student_id = ?").param(id).update();
        }
        jdbc.sql("DELETE FROM student WHERE id = ?").param(id).update();
    }

    // ================================================================================================ history

    @Transactional(readOnly = true)
    public List<HistoryEntry> history(UUID id) {
        access.readable(id);
        List<HistoryEntry> out = new ArrayList<>(jdbc.sql("""
                SELECT h.created_at, h.from_status, h.to_status, h.effective_on, h.reason, u.display_name
                FROM student_status_history h LEFT JOIN user_account u ON u.id = h.actor_id WHERE h.student_id = ?""").param(id)
                .query((rs, n) -> new HistoryEntry(instant(rs, "created_at"), "STATUS", "student.status_changed",
                        "Status " + (rs.getString("from_status") == null ? "" : rs.getString("from_status").toLowerCase() + " → ") + rs.getString("to_status").toLowerCase(),
                        rs.getString("from_status"), rs.getString("to_status"), rs.getObject("effective_on", LocalDate.class), rs.getString("reason"),
                        rs.getString("display_name"))).list());
        out.addAll(jdbc.sql("""
                SELECT ev.created_at, ev.event_type, ev.summary, u.display_name FROM student_event ev LEFT JOIN user_account u ON u.id = ev.actor_id
                WHERE ev.student_id = ? AND ev.event_type <> 'student.status_changed'""").param(id)
                .query((rs, n) -> new HistoryEntry(instant(rs, "created_at"), "EVENT", rs.getString("event_type"), rs.getString("summary"), null, null, null,
                        null, rs.getString("display_name"))).list());
        out.sort((a, b) -> b.at().compareTo(a.at()));
        return out;
    }

    // ================================================================================================ options

    @Transactional(readOnly = true)
    public List<Option> options() {
        access.requireAnyRead();
        return options.all();
    }

    @Transactional
    public Option addOption(OptionKind kind, String code, String label) {
        return options.add(kind, code, label);
    }

    @Transactional
    public void setOptionActive(OptionKind kind, String code, boolean active) {
        options.setActive(kind, code, active);
    }

    // ================================================================================================ helpers

    static String required(String field, String raw, int max) {
        String v = optional(raw, max);
        if (v == null) throw ApiException.badRequest("invalid_" + field, "This field is required.");
        return v;
    }

    static String optional(String raw, int max) {
        if (raw == null || raw.isBlank()) return null;
        String v = raw.trim();
        if (v.length() > max) throw ApiException.badRequest("too_long", "Use at most " + max + " characters.");
        return v;
    }

    static String email(String field, String raw) {
        String v = optional(raw, 320);
        if (v == null) return null;
        v = v.toLowerCase();
        if (!EMAIL.matcher(v).matches()) throw ApiException.badRequest("invalid_" + field, "Enter a valid email address.");
        return v;
    }

    static String phone(String field, String raw) {
        String v = optional(raw, 20);
        if (v == null) return null;
        if (!PHONE.matcher(v).matches()) throw ApiException.badRequest("invalid_" + field, "Enter a phone number of 7–20 digits (spaces, - and a leading + allowed).");
        return v;
    }

    static Instant instant(ResultSet rs, String col) throws SQLException {
        java.sql.Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    Optional<StudentAccess.StudentRef> find(UUID id) {
        try {
            return Optional.of(access.student(id));
        } catch (ApiException e) {
            return Optional.empty();
        }
    }
}
