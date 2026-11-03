package com.ivorovi.academics.internal;

import static com.ivorovi.academics.internal.StructureService.actor;
import static com.ivorovi.academics.internal.StructureService.details;
import static com.ivorovi.academics.internal.StructureService.diff;
import static com.ivorovi.academics.internal.StructureService.tenant;
import static com.ivorovi.academics.internal.StructureService.version;

import com.ivorovi.academics.internal.CalendarService.TermView;
import com.ivorovi.academics.internal.CalendarService.YearView;
import com.ivorovi.academics.internal.StructureService.CourseView;
import com.ivorovi.academics.internal.StructureService.Page;
import com.ivorovi.academics.internal.StructureService.ProgramView;
import com.ivorovi.academics.internal.StructureService.SubjectView;
import com.ivorovi.platform.ids.Ids;
import com.ivorovi.platform.security.Permission;
import com.ivorovi.platform.web.ApiException;
import com.ivorovi.tenancy.AcademicCalendarPolicy;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Batches (intakes of a course), subject offerings (a subject master delivered to a batch in a term) and faculty
 * allocation (existing staff profiles teaching an offering, with history). All three are managed at the course's
 * department: {@code batch.manage} for batches and offerings, {@code faculty.assign} for allocation.
 */
@Service
public class DeliveryService {

    public enum BatchStatus { PLANNED, ACTIVE, GRADUATED, INACTIVE }
    public enum OfferingStatus { PLANNED, ACTIVE, COMPLETED, CANCELLED }

    @Schema(name = "Batch")
    public record BatchView(UUID id, String code, String name, UUID courseId, String courseCode, String courseName, UUID programId,
                            String programName, UUID departmentId, String departmentName, UUID academicYearId, String academicYearName,
                            int admissionYear, int expectedGraduationYear, String section, Integer capacity, BatchStatus status,
                            long offeringCount, Instant updatedAt, long version, boolean canManage) {}

    @Schema(name = "SubjectOffering")
    public record OfferingView(UUID id, UUID subjectId, String subjectCode, String subjectName, UUID courseId, String courseCode,
                               UUID batchId, String batchCode, String batchName, UUID academicYearId, String academicYearName, UUID termId,
                               String termName, int termNumber, String section, BigDecimal credits, OfferingStatus status, UUID departmentId,
                               String primaryFaculty, long facultyCount, Instant updatedAt, long version, boolean canManage, boolean canAssignFaculty) {}

    @Schema(name = "FacultyAssignment")
    public record FacultyView(UUID id, UUID offeringId, UUID staffProfileId, String staffNo, String staffName, String designation, boolean primary,
                              LocalDate startsOn, LocalDate endsOn, Instant endedAt, String endReason, boolean current, Instant createdAt) {}

    @Schema(name = "FacultyCandidate")
    public record Candidate(UUID staffProfileId, String staffNo, String name, String designation, String unitName, boolean alreadyAssigned) {}

    @Schema(name = "StaffTeachingAssignment")
    public record TeachingView(UUID id, UUID offeringId, String subjectCode, String subjectName, String batchCode, String termName,
                               String academicYearName, boolean primary, LocalDate startsOn, LocalDate endsOn, boolean current) {}

    public record BatchCommand(String code, String name, UUID courseId, UUID academicYearId, Integer admissionYear, Integer expectedGraduationYear,
                               String section, Integer capacity, BatchStatus status, Long version) {}

    public record OfferingCommand(UUID subjectId, UUID batchId, UUID termId, Integer termNumber, String section, BigDecimal credits) {}

    public record AssignCommand(UUID staffProfileId, Boolean primary, LocalDate startsOn, LocalDate endsOn) {}

    static final String BATCH = Permission.BATCH_MANAGE.key();
    static final String FACULTY = Permission.FACULTY_ASSIGN.key();
    /** Staff categories that may teach; other staff (exam controllers, administrative staff…) cannot be allocated. */
    static final Set<String> TEACHING = Set.of("FACULTY", "ACADEMIC_ADMIN", "DEPARTMENT_ADMIN");

    private final JdbcClient jdbc;
    private final AcademicScope scope;
    private final StructureService structure;
    private final CalendarService calendar;
    private final AcademicCalendarPolicy policy;

    DeliveryService(JdbcClient jdbc, AcademicScope scope, StructureService structure, CalendarService calendar, AcademicCalendarPolicy policy) {
        this.jdbc = jdbc;
        this.scope = scope;
        this.structure = structure;
        this.calendar = calendar;
        this.policy = policy;
    }

    // ================================================================================================ batches

    private static final String BATCH_FROM = """
            batch b JOIN course c ON c.id = b.course_id JOIN program p ON p.id = c.program_id JOIN org_unit d ON d.id = c.department_id
            JOIN academic_year y ON y.id = b.academic_year_id""";
    private static final String BATCH_SELECT = """
            SELECT b.*, c.code AS course_code, c.name AS course_name, c.program_id, p.name AS program_name, c.department_id, d.name AS department_name,
                   y.name AS year_name, (SELECT count(*) FROM subject_offering o WHERE o.batch_id = b.id AND o.status <> 'CANCELLED') AS offering_count
            FROM\s""" + BATCH_FROM;
    private static final Map<String, String> BATCH_SORTS = Map.of("name", "b.name", "code", "b.code", "admissionYear", "b.admission_year",
            "course", "c.name", "status", "b.status", "updatedAt", "b.updated_at");

    @Transactional(readOnly = true)
    public Page<BatchView> batches(String q, BatchStatus status, UUID courseId, UUID programId, UUID academicYearId, Integer admissionYear,
                                   int page, int size, String sort, boolean asc) {
        scope.requireAnyRead();
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (status != null) { where.append(" AND b.status = ?"); params.add(status.name()); }
        if (courseId != null) { where.append(" AND b.course_id = ?"); params.add(courseId); }
        if (programId != null) { where.append(" AND c.program_id = ?"); params.add(programId); }
        if (academicYearId != null) { where.append(" AND b.academic_year_id = ?"); params.add(academicYearId); }
        if (admissionYear != null) { where.append(" AND b.admission_year = ?"); params.add(admissionYear); }
        StructureService.search(q, where, params, "b.name", "b.code", "b.section", "c.name");
        if (!scope.appendVisibility("d.path", where, params)) return new Page<>(List.of(), 0);
        return structure.page(BATCH_SELECT, BATCH_FROM, where, params, page, size, BATCH_SORTS.getOrDefault(sort, "b.admission_year"), asc, "b.id",
                this::mapBatch);
    }

    @Transactional(readOnly = true)
    public BatchView batch(UUID id) {
        BatchView b = loadBatch(id);
        scope.requireRead(b.departmentId());
        return b;
    }

    @Transactional
    public UUID createBatch(BatchCommand c) {
        if (c.courseId() == null) throw ApiException.badRequest("invalid_courseId", "Choose a course.");
        CourseView course = structure.loadCourse(c.courseId());
        scope.requireManage(BATCH, course.departmentId());
        if (course.status() != StructureService.Status.ACTIVE) throw AcademicScope.invalid("course_inactive", course.name() + " is inactive.");
        ProgramView program = structure.loadProgram(course.programId());
        YearView year = applicableYear(c.academicYearId(), program);
        BatchFields f = batchFields(c, program);
        BatchStatus status = c.status() == null ? BatchStatus.ACTIVE : c.status();
        if (status != BatchStatus.PLANNED && status != BatchStatus.ACTIVE) {
            throw ApiException.badRequest("invalid_status", "A new batch is planned or active.");
        }
        UUID id = Ids.newId();
        try {
            jdbc.sql("""
                    INSERT INTO batch (id, tenant_id, course_id, academic_year_id, code, name, admission_year, expected_graduation_year, section, capacity,
                        status, created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(id, tenant(), course.id(), year.id(), f.code, f.name, f.admission, f.graduation, f.section, f.capacity, status.name(),
                            actor(), actor()).update();
        } catch (DuplicateKeyException e) {
            throw AcademicScope.conflict("code_in_use", course.name() + " already has a batch with the code " + f.code + ".");
        }
        scope.changed("academics.batch.created", "batch", id, details("code", f.code, "course", course.code(), "year", year.code(),
                "admissionYear", String.valueOf(f.admission), "section", f.section));
        return id;
    }

    @Transactional
    public void updateBatch(UUID id, BatchCommand c) {
        scope.lock("batch", id);
        BatchView before = loadBatch(id);
        scope.requireManage(BATCH, before.departmentId());
        version(c.version(), before.version());
        if (c.courseId() != null && !c.courseId().equals(before.courseId())) {
            throw ApiException.badRequest("course_immutable", "A batch cannot move to another course.");
        }
        CourseView course = structure.loadCourse(before.courseId());
        ProgramView program = structure.loadProgram(course.programId());
        UUID yearId = c.academicYearId() == null ? before.academicYearId() : c.academicYearId();
        YearView year = yearId.equals(before.academicYearId()) ? calendar.loadYear(yearId) : applicableYear(yearId, program);
        BatchFields f = batchFields(c, program);
        try {
            jdbc.sql("""
                    UPDATE batch SET code = ?, name = ?, academic_year_id = ?, admission_year = ?, expected_graduation_year = ?, section = ?, capacity = ?,
                        updated_at = now(), updated_by = ?, version = version + 1 WHERE id = ?""")
                    .params(f.code, f.name, year.id(), f.admission, f.graduation, f.section, f.capacity, actor(), id).update();
        } catch (DuplicateKeyException e) {
            throw AcademicScope.conflict("code_in_use", course.name() + " already has a batch with the code " + f.code + ".");
        }
        Map<String, String> d = details("code", f.code);
        diff(d, "name", before.name(), f.name);
        diff(d, "year", before.academicYearName(), year.name());
        diff(d, "section", before.section(), f.section);
        diff(d, "capacity", String.valueOf(before.capacity()), String.valueOf(f.capacity));
        diff(d, "expectedGraduationYear", String.valueOf(before.expectedGraduationYear()), String.valueOf(f.graduation));
        scope.changed("academics.batch.updated", "batch", id, d);
    }

    @Transactional
    public void setBatchStatus(UUID id, BatchStatus status, long version, String reason) {
        scope.lock("batch", id);
        BatchView b = loadBatch(id);
        scope.requireManage(BATCH, b.departmentId());
        version(version, b.version());
        if (status == null) throw ApiException.badRequest("invalid_status", "Choose a status.");
        if (b.status() == status) throw AcademicScope.conflict("invalid_status_transition", "The batch is already " + status.name().toLowerCase() + ".");
        if (status == BatchStatus.ACTIVE && structure.loadCourse(b.courseId()).status() != StructureService.Status.ACTIVE) {
            throw AcademicScope.invalid("course_inactive", "Activate the course first.");
        }
        if (status == BatchStatus.PLANNED && b.status() != BatchStatus.ACTIVE) {
            throw AcademicScope.conflict("invalid_status_transition", "Only an active batch can return to planned.");
        }
        if ((status == BatchStatus.INACTIVE || b.status() == BatchStatus.GRADUATED) && (reason == null || reason.isBlank())) {
            throw ApiException.badRequest("reason_required", "Give a reason.");
        }
        jdbc.sql("UPDATE batch SET status = ?, updated_at = now(), updated_by = ?, version = version + 1 WHERE id = ?").params(status.name(), actor(), id).update();
        scope.changed("academics.batch.status_changed", "batch", id, details("code", b.code(), "from", b.status().name(), "to", status.name(), "reason", reason));
    }

    /** Years apply to a program when they belong to the organization or to its institution (or an ancestor such as the university). */
    private YearView applicableYear(UUID yearId, ProgramView program) {
        if (yearId == null) throw ApiException.badRequest("invalid_academicYearId", "Choose an academic year.");
        YearView year;
        try {
            year = calendar.loadYear(yearId);
        } catch (ApiException e) {
            throw AcademicScope.invalid("invalid_academicYearId", "Choose an academic year of your organization.");
        }
        if (year.ownerOrgUnitId() != null) {
            String yearOwner = scope.find(year.ownerOrgUnitId()).orElseThrow().path();
            String institution = scope.find(program.ownerOrgUnitId()).orElseThrow().path();
            if (!AcademicScope.within(institution, yearOwner)) {
                throw AcademicScope.invalid("year_not_applicable", year.name() + " belongs to " + year.ownerName() + " and does not apply to " + program.ownerName() + ".");
            }
        }
        if (year.status() == CalendarService.Status.COMPLETED) throw AcademicScope.invalid("year_completed", year.name() + " is completed.");
        return year;
    }

    private record BatchFields(String code, String name, int admission, int graduation, String section, Integer capacity) {}

    private BatchFields batchFields(BatchCommand c, ProgramView program) {
        String code = Text.batchCode(c.code());
        String name = Text.required("name", c.name(), 200);
        if (c.admissionYear() == null || c.admissionYear() < 1990 || c.admissionYear() > 2200) {
            throw ApiException.badRequest("invalid_admission_year", "Give the admission year (1990–2200).");
        }
        int graduation = c.expectedGraduationYear() != null ? c.expectedGraduationYear()
                : c.admissionYear() + program.durationYears().setScale(0, RoundingMode.CEILING).intValue();
        if (graduation < c.admissionYear() || graduation > c.admissionYear() + 15) {
            throw ApiException.badRequest("invalid_graduation_year", "The expected graduation year must be on or after the admission year (at most 15 years).");
        }
        String section = Text.optional(c.section(), 20);
        if (c.capacity() != null && (c.capacity() < 1 || c.capacity() > 100000)) throw ApiException.badRequest("invalid_capacity", "Capacity must be 1 to 100000.");
        return new BatchFields(code, name, c.admissionYear(), graduation, section, c.capacity());
    }

    BatchView loadBatch(UUID id) {
        return jdbc.sql(BATCH_SELECT + " WHERE b.id = ?").param(id).query(this::mapBatch).optional().orElseThrow(() -> ApiException.notFound("Batch"));
    }

    private BatchView mapBatch(ResultSet rs, int n) throws SQLException {
        UUID dept = rs.getObject("department_id", UUID.class);
        return new BatchView(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"), rs.getObject("course_id", UUID.class),
                rs.getString("course_code"), rs.getString("course_name"), rs.getObject("program_id", UUID.class), rs.getString("program_name"), dept,
                rs.getString("department_name"), rs.getObject("academic_year_id", UUID.class), rs.getString("year_name"), rs.getInt("admission_year"),
                rs.getInt("expected_graduation_year"), rs.getString("section"), (Integer) rs.getObject("capacity"), BatchStatus.valueOf(rs.getString("status")),
                rs.getLong("offering_count"), Text.instant(rs, "updated_at"), rs.getLong("version"), scope.canManage(BATCH, dept));
    }

    // ================================================================================================ offerings

    private static final String OFFERING_FROM = """
            subject_offering o JOIN subject s ON s.id = o.subject_id JOIN course c ON c.id = o.course_id JOIN org_unit d ON d.id = c.department_id
            JOIN batch b ON b.id = o.batch_id JOIN academic_year y ON y.id = o.academic_year_id JOIN academic_term t ON t.id = o.term_id""";
    private static final String OFFERING_SELECT = """
            SELECT o.*, s.code AS subject_code, s.name AS subject_name, c.code AS course_code, c.department_id, b.code AS batch_code, b.name AS batch_name,
                   y.name AS year_name, t.name AS term_name,
                   (SELECT concat_ws(' ', sp.given_name, sp.family_name) FROM faculty_assignment f JOIN staff_profile sp ON sp.id = f.staff_profile_id
                     WHERE f.offering_id = o.id AND f.ended_at IS NULL AND f.is_primary) AS primary_faculty,
                   (SELECT count(*) FROM faculty_assignment f WHERE f.offering_id = o.id AND f.ended_at IS NULL) AS faculty_count
            FROM\s""" + OFFERING_FROM;
    private static final Map<String, String> OFFERING_SORTS = Map.of("subject", "s.code", "batch", "b.code", "term", "t.start_date",
            "termNumber", "o.term_number", "status", "o.status", "updatedAt", "o.updated_at");

    @Transactional(readOnly = true)
    public Page<OfferingView> offerings(String q, OfferingStatus status, UUID batchId, UUID termId, UUID courseId, UUID subjectId, UUID academicYearId,
                                        Boolean withoutFaculty, int page, int size, String sort, boolean asc) {
        scope.requireAnyRead();
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (status != null) { where.append(" AND o.status = ?"); params.add(status.name()); }
        if (batchId != null) { where.append(" AND o.batch_id = ?"); params.add(batchId); }
        if (termId != null) { where.append(" AND o.term_id = ?"); params.add(termId); }
        if (courseId != null) { where.append(" AND o.course_id = ?"); params.add(courseId); }
        if (subjectId != null) { where.append(" AND o.subject_id = ?"); params.add(subjectId); }
        if (academicYearId != null) { where.append(" AND o.academic_year_id = ?"); params.add(academicYearId); }
        if (Boolean.TRUE.equals(withoutFaculty)) {
            where.append(" AND o.status <> 'CANCELLED' AND NOT EXISTS (SELECT 1 FROM faculty_assignment f WHERE f.offering_id = o.id AND f.ended_at IS NULL)");
        }
        StructureService.search(q, where, params, "s.name", "s.code", "b.code", "b.name");
        if (!scope.appendVisibility("d.path", where, params)) return new Page<>(List.of(), 0);
        return structure.page(OFFERING_SELECT, OFFERING_FROM, where, params, page, size, OFFERING_SORTS.getOrDefault(sort, "s.code"), asc, "o.id",
                this::mapOffering);
    }

    @Transactional(readOnly = true)
    public OfferingView offering(UUID id) {
        OfferingView o = loadOffering(id);
        scope.requireRead(o.departmentId());
        return o;
    }

    /**
     * Offers a subject master to a batch in a calendar term. {@code termNumber} is the course term (e.g. semester 3 of
     * the course) and must be one in which the curriculum teaches the subject; the calendar term must belong to an
     * academic year that applies to the program and must not be completed.
     */
    @Transactional
    public UUID createOffering(OfferingCommand c) {
        if (c.batchId() == null) throw ApiException.badRequest("invalid_batchId", "Choose a batch.");
        BatchView batch = loadBatch(c.batchId());
        scope.requireManage(BATCH, batch.departmentId());
        if (batch.status() != BatchStatus.ACTIVE && batch.status() != BatchStatus.PLANNED) {
            throw AcademicScope.invalid("batch_not_active", batch.name() + " is " + batch.status().name().toLowerCase() + ".");
        }
        CourseView course = structure.loadCourse(batch.courseId());
        if (course.status() != StructureService.Status.ACTIVE) throw AcademicScope.invalid("course_inactive", course.name() + " is inactive.");
        ProgramView program = structure.loadProgram(course.programId());
        if (c.subjectId() == null) throw ApiException.badRequest("invalid_subjectId", "Choose a subject.");
        SubjectView subject = structure.loadSubject(c.subjectId());
        if (subject.status() != StructureService.Status.ACTIVE) throw AcademicScope.invalid("subject_inactive", subject.name() + " is inactive.");
        if (c.termId() == null) throw ApiException.badRequest("invalid_termId", "Choose a term of the academic calendar.");
        TermView term = calendar.loadTerm(c.termId());
        if (term.status() == CalendarService.Status.COMPLETED) throw AcademicScope.invalid("term_completed", term.name() + " is completed.");
        YearView year = applicableYear(term.academicYearId(), program);
        if (c.termNumber() == null || c.termNumber() < 1 || c.termNumber() > course.effectiveTerms()) {
            throw ApiException.badRequest("invalid_term_number", "Choose a course term from 1 to " + course.effectiveTerms() + ".");
        }
        boolean inCurriculum = jdbc.sql("SELECT count(*) FROM curriculum_entry WHERE course_id = ? AND subject_id = ? AND term_number = ?")
                .params(course.id(), subject.id(), c.termNumber()).query(Long.class).single() > 0;
        if (!inCurriculum) {
            throw AcademicScope.invalid("not_in_curriculum", subject.code() + " is not in term " + c.termNumber() + " of " + course.code()
                    + "'s curriculum. Add it to the curriculum first.");
        }
        String section = c.section() == null ? batch.section() : Text.optional(c.section(), 20);
        BigDecimal credits = c.credits() == null ? subject.credits() : c.credits();
        if (credits.signum() < 0 || credits.compareTo(BigDecimal.valueOf(40)) > 0) throw ApiException.badRequest("invalid_credits", "Credits must be between 0 and 40.");
        UUID id = Ids.newId();
        try {
            jdbc.sql("""
                    INSERT INTO subject_offering (id, tenant_id, subject_id, course_id, batch_id, academic_year_id, term_id, term_number, section, credits,
                        created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(id, tenant(), subject.id(), course.id(), batch.id(), year.id(), term.id(), c.termNumber(), section,
                            credits.setScale(1, RoundingMode.HALF_UP), actor(), actor()).update();
        } catch (DuplicateKeyException e) {
            throw AcademicScope.conflict("offering_exists", subject.code() + " is already offered to " + batch.code()
                    + (section == null ? "" : " section " + section) + " in " + term.name() + ".");
        }
        scope.changed("academics.offering.created", "subject_offering", id, details("subject", subject.code(), "batch", batch.code(), "term", term.name(),
                "year", year.code(), "termNumber", String.valueOf(c.termNumber()), "section", section));
        return id;
    }

    /** PLANNED → ACTIVE → COMPLETED, or CANCELLED from planned/active (open faculty assignments end with it). */
    @Transactional
    public void setOfferingStatus(UUID id, OfferingStatus status, long version, String reason) {
        scope.lock("subject_offering", id);
        OfferingView o = loadOffering(id);
        scope.requireManage(BATCH, o.departmentId());
        version(version, o.version());
        if (status == null) throw ApiException.badRequest("invalid_status", "Choose a status.");
        boolean ok = (o.status() == OfferingStatus.PLANNED && (status == OfferingStatus.ACTIVE || status == OfferingStatus.CANCELLED))
                || (o.status() == OfferingStatus.ACTIVE && (status == OfferingStatus.COMPLETED || status == OfferingStatus.CANCELLED || status == OfferingStatus.PLANNED));
        if (!ok) {
            throw AcademicScope.conflict("invalid_status_transition", "An offering cannot go from " + o.status().name().toLowerCase() + " to " + status.name().toLowerCase() + ".");
        }
        if (status == OfferingStatus.CANCELLED && (reason == null || reason.isBlank())) throw ApiException.badRequest("reason_required", "Give a reason for the cancellation.");
        int ended = 0;
        if (status == OfferingStatus.CANCELLED) {
            ended = jdbc.sql("""
                    UPDATE faculty_assignment SET ended_at = now(), ended_by = ?, end_reason = ?, ends_on = greatest(starts_on, ?)
                    WHERE offering_id = ? AND ended_at IS NULL""")
                    .params(actor(), "Offering cancelled: " + reason.trim(), today(), id).update();
        }
        jdbc.sql("UPDATE subject_offering SET status = ?, updated_at = now(), updated_by = ?, version = version + 1 WHERE id = ?").params(status.name(), actor(), id).update();
        scope.changed("academics.offering.status_changed", "subject_offering", id, details("subject", o.subjectCode(), "batch", o.batchCode(),
                "from", o.status().name(), "to", status.name(), "reason", reason, "facultyAssignmentsEnded", ended > 0 ? String.valueOf(ended) : null));
    }

    OfferingView loadOffering(UUID id) {
        return jdbc.sql(OFFERING_SELECT + " WHERE o.id = ?").param(id).query(this::mapOffering).optional()
                .orElseThrow(() -> ApiException.notFound("Subject offering"));
    }

    private OfferingView mapOffering(ResultSet rs, int n) throws SQLException {
        UUID dept = rs.getObject("department_id", UUID.class);
        return new OfferingView(rs.getObject("id", UUID.class), rs.getObject("subject_id", UUID.class), rs.getString("subject_code"),
                rs.getString("subject_name"), rs.getObject("course_id", UUID.class), rs.getString("course_code"), rs.getObject("batch_id", UUID.class),
                rs.getString("batch_code"), rs.getString("batch_name"), rs.getObject("academic_year_id", UUID.class), rs.getString("year_name"),
                rs.getObject("term_id", UUID.class), rs.getString("term_name"), rs.getInt("term_number"), rs.getString("section"), rs.getBigDecimal("credits"),
                OfferingStatus.valueOf(rs.getString("status")), dept, rs.getString("primary_faculty"), rs.getLong("faculty_count"),
                Text.instant(rs, "updated_at"), rs.getLong("version"), scope.canManage(BATCH, dept), scope.canManage(FACULTY, dept));
    }

    // ================================================================================================ faculty

    private static final String FACULTY_SELECT = """
            SELECT f.*, sp.staff_no, concat_ws(' ', sp.given_name, sp.family_name) AS staff_name, sp.designation
            FROM faculty_assignment f JOIN staff_profile sp ON sp.id = f.staff_profile_id""";

    @Transactional(readOnly = true)
    public List<FacultyView> faculty(UUID offeringId, boolean includeHistory) {
        OfferingView o = loadOffering(offeringId);
        scope.requireRead(o.departmentId());
        return jdbc.sql(FACULTY_SELECT + " WHERE f.offering_id = ?" + (includeHistory ? "" : " AND f.ended_at IS NULL")
                        + " ORDER BY (f.ended_at IS NULL) DESC, f.is_primary DESC, f.starts_on DESC, f.created_at DESC")
                .param(offeringId).query(this::mapFaculty).list();
    }

    /**
     * Staff who may teach this offering: an active teaching staff profile that belongs (organizational assignment,
     * current) to the organization as a whole or to a unit of the program's institution. No separate faculty identity
     * exists; this reuses the staff model of increment 1.2.
     */
    @Transactional(readOnly = true)
    public List<Candidate> candidates(UUID offeringId, String q) {
        OfferingView o = loadOffering(offeringId);
        scope.requireManage(FACULTY, o.departmentId());
        String institution = institutionPath(o);
        List<Object> params = new ArrayList<>(List.of(offeringId, today(), today(), institution));
        StringBuilder where = new StringBuilder();
        if (q != null && !q.isBlank()) {
            where.append(" AND (sp.given_name ILIKE ? OR sp.family_name ILIKE ? OR sp.staff_no ILIKE ? OR sp.designation ILIKE ?)");
            String like = Text.like(q);
            params.addAll(List.of(like, like, like, like));
        }
        return jdbc.sql("""
                SELECT sp.id, sp.staff_no, concat_ws(' ', sp.given_name, sp.family_name), sp.designation,
                       string_agg(DISTINCT coalesce(u.name, 'Organization'), ', '),
                       EXISTS (SELECT 1 FROM faculty_assignment f WHERE f.offering_id = ? AND f.staff_profile_id = sp.id AND f.ended_at IS NULL)
                FROM staff_profile sp JOIN org_assignment a ON a.staff_profile_id = sp.id LEFT JOIN org_unit u ON u.id = a.org_unit_id
                WHERE sp.employment_status = 'ACTIVE' AND sp.category IN ('FACULTY', 'ACADEMIC_ADMIN', 'DEPARTMENT_ADMIN')
                  AND (a.starts_on IS NULL OR a.starts_on <= ?) AND (a.ends_on IS NULL OR a.ends_on >= ?)
                  AND (a.org_unit_id IS NULL OR u.path <@ CAST(? AS ltree))""" + where + """
                 GROUP BY sp.id ORDER BY sp.given_name, sp.family_name LIMIT 50""")
                .params(params)
                .query((rs, n) -> new Candidate(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getBoolean(6)))
                .list();
    }

    @Transactional
    public UUID assignFaculty(UUID offeringId, AssignCommand c) {
        scope.lock("subject_offering", offeringId);
        OfferingView o = loadOffering(offeringId);
        scope.requireManage(FACULTY, o.departmentId());
        if (o.status() == OfferingStatus.CANCELLED || o.status() == OfferingStatus.COMPLETED) {
            throw AcademicScope.invalid("offering_closed", "Faculty cannot be assigned to a " + o.status().name().toLowerCase() + " offering.");
        }
        if (c.staffProfileId() == null) throw ApiException.badRequest("invalid_staffProfileId", "Choose a staff member.");
        record Staff(String no, String name, String category, String status) {}
        Staff staff = jdbc.sql("SELECT staff_no, concat_ws(' ', given_name, family_name), category, employment_status FROM staff_profile WHERE id = ?")
                .param(c.staffProfileId()).query((rs, n) -> new Staff(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4))).optional()
                .orElseThrow(() -> AcademicScope.invalid("invalid_staffProfileId", "Choose a staff member of your organization."));
        if (!TEACHING.contains(staff.category())) throw AcademicScope.invalid("not_teaching_staff", staff.name() + " is not teaching staff.");
        if (!"ACTIVE".equals(staff.status())) throw AcademicScope.invalid("staff_not_active", staff.name() + " is not in active employment.");
        long member = jdbc.sql("""
                SELECT count(*) FROM org_assignment a LEFT JOIN org_unit u ON u.id = a.org_unit_id
                WHERE a.staff_profile_id = ? AND (a.starts_on IS NULL OR a.starts_on <= ?) AND (a.ends_on IS NULL OR a.ends_on >= ?)
                  AND (a.org_unit_id IS NULL OR u.path <@ CAST(? AS ltree))""")
                .params(c.staffProfileId(), today(), today(), institutionPath(o)).query(Long.class).single();
        if (member == 0) throw AcademicScope.invalid("staff_outside_institution", staff.name() + " does not belong to this institution.");
        LocalDate starts = c.startsOn() == null ? today() : c.startsOn();
        if (c.endsOn() != null && c.endsOn().isBefore(starts)) throw ApiException.badRequest("invalid_dates", "The assignment must end on or after it starts.");
        long open = jdbc.sql("SELECT count(*) FROM faculty_assignment WHERE offering_id = ? AND ended_at IS NULL").param(offeringId).query(Long.class).single();
        boolean primary = c.primary() != null ? c.primary() : open == 0;
        boolean demoted = false;
        if (primary) {
            demoted = jdbc.sql("UPDATE faculty_assignment SET is_primary = false WHERE offering_id = ? AND ended_at IS NULL AND is_primary").param(offeringId).update() > 0;
        }
        UUID id = Ids.newId();
        try {
            jdbc.sql("""
                    INSERT INTO faculty_assignment (id, tenant_id, offering_id, staff_profile_id, is_primary, starts_on, ends_on, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(id, tenant(), offeringId, c.staffProfileId(), primary, starts, c.endsOn(), actor()).update();
        } catch (DuplicateKeyException e) {
            throw AcademicScope.conflict("faculty_already_assigned", staff.name() + " is already assigned to this offering.");
        }
        scope.changed("academics.faculty.assigned", "subject_offering", offeringId, details("subject", o.subjectCode(), "batch", o.batchCode(),
                "staff", staff.no(), "primary", String.valueOf(primary), "previousPrimaryReplaced", demoted ? "true" : null, "startsOn", starts.toString()));
        return id;
    }

    @Transactional
    public void makePrimary(UUID offeringId, UUID assignmentId) {
        scope.lock("subject_offering", offeringId);
        OfferingView o = loadOffering(offeringId);
        scope.requireManage(FACULTY, o.departmentId());
        FacultyView f = loadAssignment(offeringId, assignmentId);
        if (!f.current()) throw AcademicScope.invalid("assignment_ended", "This assignment has ended.");
        if (f.primary()) return;
        jdbc.sql("UPDATE faculty_assignment SET is_primary = false WHERE offering_id = ? AND ended_at IS NULL AND is_primary").param(offeringId).update();
        jdbc.sql("UPDATE faculty_assignment SET is_primary = true WHERE id = ?").param(assignmentId).update();
        scope.changed("academics.faculty.primary_changed", "subject_offering", offeringId, details("subject", o.subjectCode(), "batch", o.batchCode(),
                "staff", f.staffNo()));
    }

    /** Ends an assignment (kept as history); a reason is required. */
    @Transactional
    public void endAssignment(UUID offeringId, UUID assignmentId, String reason, LocalDate endsOn) {
        scope.lock("subject_offering", offeringId);
        OfferingView o = loadOffering(offeringId);
        scope.requireManage(FACULTY, o.departmentId());
        FacultyView f = loadAssignment(offeringId, assignmentId);
        if (!f.current()) throw AcademicScope.conflict("assignment_ended", "This assignment has already ended.");
        String why = Text.required("reason", reason, 500);
        LocalDate end = endsOn == null ? today() : endsOn;
        if (end.isBefore(f.startsOn())) end = f.startsOn();
        jdbc.sql("UPDATE faculty_assignment SET ended_at = now(), ended_by = ?, end_reason = ?, ends_on = ?, is_primary = false WHERE id = ?")
                .params(actor(), why, end, assignmentId).update();
        scope.changed("academics.faculty.ended", "subject_offering", offeringId, details("subject", o.subjectCode(), "batch", o.batchCode(),
                "staff", f.staffNo(), "wasPrimary", String.valueOf(f.primary()), "reason", why));
    }

    @Transactional(readOnly = true)
    public List<TeachingView> teaching(UUID staffProfileId, boolean includeHistory) {
        scope.requireAnyRead();
        List<Object> params = new ArrayList<>(List.of(staffProfileId));
        StringBuilder where = new StringBuilder(" WHERE f.staff_profile_id = ?");
        if (!includeHistory) where.append(" AND f.ended_at IS NULL");
        if (!scope.appendVisibility("d.path", where, params)) return List.of();
        return jdbc.sql("""
                SELECT f.id, o.id, s.code, s.name, b.code, t.name, y.name, f.is_primary, f.starts_on, f.ends_on, f.ended_at IS NULL
                FROM faculty_assignment f JOIN subject_offering o ON o.id = f.offering_id JOIN subject s ON s.id = o.subject_id
                JOIN batch b ON b.id = o.batch_id JOIN academic_term t ON t.id = o.term_id JOIN academic_year y ON y.id = o.academic_year_id
                JOIN course c ON c.id = o.course_id JOIN org_unit d ON d.id = c.department_id""" + where + " ORDER BY f.ended_at IS NULL DESC, t.start_date DESC")
                .params(params)
                .query((rs, n) -> new TeachingView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getBoolean(8), rs.getObject(9, LocalDate.class),
                        rs.getObject(10, LocalDate.class), rs.getBoolean(11)))
                .list();
    }

    private FacultyView loadAssignment(UUID offeringId, UUID assignmentId) {
        return jdbc.sql(FACULTY_SELECT + " WHERE f.id = ? AND f.offering_id = ?").params(assignmentId, offeringId).query(this::mapFaculty).optional()
                .orElseThrow(() -> ApiException.notFound("Faculty assignment"));
    }

    private FacultyView mapFaculty(ResultSet rs, int n) throws SQLException {
        Instant ended = Text.instant(rs, "ended_at");
        return new FacultyView(rs.getObject("id", UUID.class), rs.getObject("offering_id", UUID.class), rs.getObject("staff_profile_id", UUID.class),
                rs.getString("staff_no"), rs.getString("staff_name"), rs.getString("designation"), rs.getBoolean("is_primary"),
                rs.getObject("starts_on", LocalDate.class), rs.getObject("ends_on", LocalDate.class), ended, rs.getString("end_reason"), ended == null,
                Text.instant(rs, "created_at"));
    }

    private String institutionPath(OfferingView o) {
        CourseView course = structure.loadCourse(o.courseId());
        ProgramView program = structure.loadProgram(course.programId());
        return scope.find(program.ownerOrgUnitId()).orElseThrow().path();
    }

    private LocalDate today() {
        return policy.today();
    }
}
