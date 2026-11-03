package com.ivorovi.academics.internal;

import com.ivorovi.platform.ids.Ids;
import com.ivorovi.platform.security.CurrentUser;
import com.ivorovi.platform.security.Permission;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.platform.web.ApiException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Programs (owned by a university or college), courses/branches (a program's specialization owned by one of the
 * institution's departments) and subjects (reusable masters owned by any unit, placed into course terms through the
 * curriculum). Deactivation keeps records; links (curriculum, prerequisites) are removed only while unused.
 */
@Service
public class StructureService {

    public enum Status { ACTIVE, INACTIVE }
    public enum Level { CERTIFICATE, DIPLOMA, UNDERGRADUATE, POSTGRADUATE, DOCTORAL, OTHER }
    public enum TermSystem { SEMESTER, TRIMESTER, QUARTER, ANNUAL, OTHER }
    public enum Delivery { THEORY, PRACTICAL, THEORY_AND_PRACTICAL }
    public enum Category { CORE, ELECTIVE, OPEN_ELECTIVE, FOUNDATION, LABORATORY, PROJECT, SEMINAR, INTERNSHIP, AUDIT, OTHER }

    @Schema(name = "Program")
    public record ProgramView(UUID id, String code, String name, Level qualificationLevel, String awardedQualification,
                              UUID ownerOrgUnitId, String ownerName, String ownerType, TermSystem termSystem, int termsPerYear,
                              BigDecimal durationYears, int totalTerms, String description, Status status, long courseCount,
                              Instant updatedAt, long version, boolean canManage) {}

    @Schema(name = "Course")
    public record CourseView(UUID id, String code, String name, UUID programId, String programCode, String programName,
                             UUID departmentId, String departmentName, String specialization, Integer totalTerms, int effectiveTerms,
                             String description, Status status, long batchCount, long curriculumCount, Instant updatedAt, long version,
                             boolean canManage) {}

    @Schema(name = "Subject")
    public record SubjectView(UUID id, String code, String name, String description, BigDecimal credits, Delivery delivery,
                              boolean elective, Category category, UUID ownerOrgUnitId, String ownerName, Status status,
                              long courseCount, Instant updatedAt, long version, boolean canManage) {}

    @Schema(name = "CurriculumEntry")
    public record CurriculumView(UUID id, UUID courseId, String courseCode, String courseName, String programName, UUID subjectId,
                                 String subjectCode, String subjectName, BigDecimal credits, int termNumber, boolean elective,
                                 Category category, Delivery delivery) {}

    @Schema(name = "SubjectRef")
    public record SubjectRef(UUID id, String code, String name, Status status) {}

    @Schema(name = "SubjectDetail")
    public record SubjectDetail(SubjectView subject, List<SubjectRef> prerequisites, List<SubjectRef> requiredBy, List<CurriculumView> curriculum) {}

    public record Page<T>(List<T> items, long total) {}

    public record ProgramCommand(String code, String name, Level qualificationLevel, String awardedQualification, UUID ownerOrgUnitId,
                                 TermSystem termSystem, Integer termsPerYear, BigDecimal durationYears, Integer totalTerms, String description, Long version) {}

    public record CourseCommand(String code, String name, UUID programId, UUID departmentId, String specialization, Integer totalTerms,
                                String description, Long version) {}

    public record SubjectCommand(String code, String name, String description, BigDecimal credits, Delivery delivery, Boolean elective,
                                 Category category, UUID ownerOrgUnitId, Long version) {}

    static final String PROGRAM = Permission.PROGRAM_MANAGE.key();
    static final String SUBJECT = Permission.SUBJECT_MANAGE.key();
    private static final Set<String> INSTITUTIONS = Set.of("UNIVERSITY", "COLLEGE");
    private static final Set<String> ANY_UNIT = Set.of("UNIVERSITY", "COLLEGE", "CAMPUS", "DEPARTMENT");

    private final JdbcClient jdbc;
    private final AcademicScope scope;

    StructureService(JdbcClient jdbc, AcademicScope scope) {
        this.jdbc = jdbc;
        this.scope = scope;
    }

    // ================================================================================================ programs

    private static final String PROGRAM_SELECT = """
            SELECT p.*, ou.name AS owner_name, ou.unit_type AS owner_type,
                   (SELECT count(*) FROM course c WHERE c.program_id = p.id) AS course_count
            FROM program p JOIN org_unit ou ON ou.id = p.owner_org_unit_id""";
    private static final Map<String, String> PROGRAM_SORTS = Map.of("name", "p.name", "code", "p.code", "level", "p.qualification_level",
            "status", "p.status", "updatedAt", "p.updated_at");

    @Transactional(readOnly = true)
    public Page<ProgramView> programs(String q, Status status, Level level, UUID ownerOrgUnitId, int page, int size, String sort, boolean asc) {
        scope.requireAnyRead();
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (status != null) { where.append(" AND p.status = ?"); params.add(status.name()); }
        if (level != null) { where.append(" AND p.qualification_level = ?"); params.add(level.name()); }
        if (ownerOrgUnitId != null) { where.append(" AND p.owner_org_unit_id = ?"); params.add(ownerOrgUnitId); }
        search(q, where, params, "p.name", "p.code", "p.awarded_qualification");
        if (!scope.appendVisibility("ou.path", where, params)) return new Page<>(List.of(), 0);
        return page(PROGRAM_SELECT, "program p JOIN org_unit ou ON ou.id = p.owner_org_unit_id", where, params, page, size,
                PROGRAM_SORTS.getOrDefault(sort, "p.name"), asc, "p.id", this::mapProgram);
    }

    @Transactional(readOnly = true)
    public ProgramView program(UUID id) {
        ProgramView p = loadProgram(id);
        scope.requireRead(p.ownerOrgUnitId());
        return p;
    }

    @Transactional
    public UUID createProgram(ProgramCommand c) {
        AcademicScope.Unit owner = scope.activeUnit(c.ownerOrgUnitId(), INSTITUTIONS, "owner", "a university or college");
        scope.requireManage(PROGRAM, owner.id());
        ProgramFields f = programFields(c);
        UUID id = Ids.newId();
        try {
            jdbc.sql("""
                    INSERT INTO program (id, tenant_id, owner_org_unit_id, code, name, qualification_level, awarded_qualification, term_system,
                        terms_per_year, duration_years, total_terms, description, created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(id, tenant(), owner.id(), f.code, f.name, f.level.name(), f.awarded, f.system.name(), f.perYear, f.duration,
                            f.total, f.description, actor(), actor()).update();
        } catch (DuplicateKeyException e) {
            throw AcademicScope.conflict("code_in_use", "Another program of " + owner.name() + " already uses the code " + f.code + ".");
        }
        scope.changed("academics.program.created", "program", id, details("code", f.code, "name", f.name, "owner", owner.name(),
                "termSystem", f.system.name(), "totalTerms", String.valueOf(f.total)));
        return id;
    }

    @Transactional
    public void updateProgram(UUID id, ProgramCommand c) {
        scope.lock("program", id);
        ProgramView before = loadProgram(id);
        scope.requireManage(PROGRAM, before.ownerOrgUnitId());
        version(c.version(), before.version());
        if (c.ownerOrgUnitId() != null && !c.ownerOrgUnitId().equals(before.ownerOrgUnitId())) {
            throw ApiException.badRequest("owner_immutable", "A program cannot move to another institution.");
        }
        ProgramFields f = programFields(new ProgramCommand(c.code(), c.name(), c.qualificationLevel(), c.awardedQualification(), before.ownerOrgUnitId(),
                c.termSystem(), c.termsPerYear(), c.durationYears(), c.totalTerms(), c.description(), c.version()));
        Integer longestCourse = jdbc.sql("SELECT max(total_terms) FROM course WHERE program_id = ?").param(id).query(Integer.class).optional().orElse(null);
        Integer highestCurriculumTerm = jdbc.sql("SELECT max(e.term_number) FROM curriculum_entry e JOIN course c ON c.id = e.course_id WHERE c.program_id = ? AND c.total_terms IS NULL")
                .param(id).query(Integer.class).optional().orElse(null);
        if ((longestCourse != null && longestCourse > f.total) || (highestCurriculumTerm != null && highestCurriculumTerm > f.total)) {
            throw AcademicScope.invalid("terms_in_use", "Courses or curricula of this program use terms beyond " + f.total + ". Shorten them first.");
        }
        try {
            jdbc.sql("""
                    UPDATE program SET code = ?, name = ?, qualification_level = ?, awarded_qualification = ?, term_system = ?, terms_per_year = ?,
                        duration_years = ?, total_terms = ?, description = ?, updated_at = now(), updated_by = ?, version = version + 1 WHERE id = ?""")
                    .params(f.code, f.name, f.level.name(), f.awarded, f.system.name(), f.perYear, f.duration, f.total, f.description, actor(), id).update();
        } catch (DuplicateKeyException e) {
            throw AcademicScope.conflict("code_in_use", "Another program of " + before.ownerName() + " already uses the code " + f.code + ".");
        }
        Map<String, String> d = details("code", f.code);
        diff(d, "name", before.name(), f.name);
        diff(d, "totalTerms", String.valueOf(before.totalTerms()), String.valueOf(f.total));
        diff(d, "termSystem", before.termSystem().name(), f.system.name());
        diff(d, "qualificationLevel", before.qualificationLevel().name(), f.level.name());
        scope.changed("academics.program.updated", "program", id, d);
    }

    @Transactional
    public void setProgramStatus(UUID id, Status status, long version, String reason) {
        scope.lock("program", id);
        ProgramView p = loadProgram(id);
        scope.requireManage(PROGRAM, p.ownerOrgUnitId());
        version(version, p.version());
        if (p.status() == status) throw AcademicScope.conflict("invalid_status_transition", "The program is already " + status.name().toLowerCase() + ".");
        if (status == Status.ACTIVE) scope.activeUnit(p.ownerOrgUnitId(), INSTITUTIONS, "owner", "an active university or college");
        jdbc.sql("UPDATE program SET status = ?, updated_at = now(), updated_by = ?, version = version + 1 WHERE id = ?").params(status.name(), actor(), id).update();
        scope.changed(status == Status.ACTIVE ? "academics.program.activated" : "academics.program.deactivated", "program", id,
                details("code", p.code(), "reason", reason, "courses", String.valueOf(p.courseCount())));
    }

    private record ProgramFields(String code, String name, Level level, String awarded, TermSystem system, int perYear, BigDecimal duration,
                                 int total, String description) {}

    private static ProgramFields programFields(ProgramCommand c) {
        String code = Text.code(c.code());
        String name = Text.required("name", c.name(), 200);
        if (c.qualificationLevel() == null) throw ApiException.badRequest("invalid_qualification_level", "Choose a qualification level.");
        TermSystem system = c.termSystem() == null ? TermSystem.SEMESTER : c.termSystem();
        int perYear = switch (system) {
            case SEMESTER -> 2;
            case TRIMESTER -> 3;
            case QUARTER -> 4;
            case ANNUAL -> 1;
            case OTHER -> c.termsPerYear() == null ? 0 : c.termsPerYear();
        };
        if (c.termsPerYear() != null && c.termsPerYear() != perYear) {
            throw ApiException.badRequest("invalid_terms_per_year", "A " + system.name().toLowerCase() + " system has " + perYear + " term(s) per year.");
        }
        if (perYear < 1 || perYear > 6) throw ApiException.badRequest("invalid_terms_per_year", "Use 1 to 6 terms per year.");
        BigDecimal duration = c.durationYears();
        if (duration == null || duration.signum() <= 0 || duration.compareTo(BigDecimal.TEN) > 0) {
            throw ApiException.badRequest("invalid_duration", "Duration must be more than 0 and at most 10 years.");
        }
        int computed = duration.multiply(BigDecimal.valueOf(perYear)).setScale(0, RoundingMode.CEILING).intValue();
        int total = c.totalTerms() == null ? computed : c.totalTerms();
        if (total < 1 || total > 40) throw ApiException.badRequest("invalid_total_terms", "Use 1 to 40 terms.");
        return new ProgramFields(code, name, c.qualificationLevel(), Text.optional(c.awardedQualification(), 200), system, perYear,
                duration.setScale(1, RoundingMode.HALF_UP), total, Text.optional(c.description(), 1000));
    }

    ProgramView loadProgram(UUID id) {
        return jdbc.sql(PROGRAM_SELECT + " WHERE p.id = ?").param(id).query(this::mapProgram).optional()
                .orElseThrow(() -> ApiException.notFound("Program"));
    }

    private ProgramView mapProgram(ResultSet rs, int n) throws SQLException {
        UUID owner = rs.getObject("owner_org_unit_id", UUID.class);
        return new ProgramView(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"), Level.valueOf(rs.getString("qualification_level")),
                rs.getString("awarded_qualification"), owner, rs.getString("owner_name"), rs.getString("owner_type"),
                TermSystem.valueOf(rs.getString("term_system")), rs.getInt("terms_per_year"), rs.getBigDecimal("duration_years"), rs.getInt("total_terms"),
                rs.getString("description"), Status.valueOf(rs.getString("status")), rs.getLong("course_count"), Text.instant(rs, "updated_at"),
                rs.getLong("version"), scope.canManage(PROGRAM, owner));
    }

    // ================================================================================================ courses

    private static final String COURSE_SELECT = """
            SELECT c.*, p.code AS program_code, p.name AS program_name, p.total_terms AS program_terms, d.name AS department_name,
                   (SELECT count(*) FROM batch b WHERE b.course_id = c.id) AS batch_count,
                   (SELECT count(*) FROM curriculum_entry e WHERE e.course_id = c.id) AS curriculum_count
            FROM course c JOIN program p ON p.id = c.program_id JOIN org_unit d ON d.id = c.department_id""";
    private static final Map<String, String> COURSE_SORTS = Map.of("name", "c.name", "code", "c.code", "program", "p.name",
            "department", "d.name", "status", "c.status", "updatedAt", "c.updated_at");

    @Transactional(readOnly = true)
    public Page<CourseView> courses(String q, Status status, UUID programId, UUID departmentId, int page, int size, String sort, boolean asc) {
        scope.requireAnyRead();
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (status != null) { where.append(" AND c.status = ?"); params.add(status.name()); }
        if (programId != null) { where.append(" AND c.program_id = ?"); params.add(programId); }
        if (departmentId != null) { where.append(" AND c.department_id = ?"); params.add(departmentId); }
        search(q, where, params, "c.name", "c.code", "c.specialization");
        if (!scope.appendVisibility("d.path", where, params)) return new Page<>(List.of(), 0);
        return page(COURSE_SELECT, "course c JOIN program p ON p.id = c.program_id JOIN org_unit d ON d.id = c.department_id", where, params,
                page, size, COURSE_SORTS.getOrDefault(sort, "c.name"), asc, "c.id", this::mapCourse);
    }

    @Transactional(readOnly = true)
    public CourseView course(UUID id) {
        CourseView c = loadCourse(id);
        scope.requireRead(c.departmentId());
        return c;
    }

    @Transactional
    public UUID createCourse(CourseCommand c) {
        if (c.programId() == null) throw ApiException.badRequest("invalid_programId", "Choose a program.");
        ProgramView program = loadProgram(c.programId());
        if (program.status() != Status.ACTIVE) throw AcademicScope.invalid("program_inactive", program.name() + " is inactive.");
        AcademicScope.Unit dept = department(c.departmentId(), program);
        scope.requireManage(PROGRAM, dept.id());
        String code = Text.code(c.code());
        String name = Text.required("name", c.name(), 200);
        Integer terms = courseTerms(c.totalTerms(), program);
        UUID id = Ids.newId();
        try {
            jdbc.sql("""
                    INSERT INTO course (id, tenant_id, program_id, department_id, code, name, specialization, total_terms, description, created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(id, tenant(), program.id(), dept.id(), code, name, Text.optional(c.specialization(), 200), terms,
                            Text.optional(c.description(), 1000), actor(), actor()).update();
        } catch (DuplicateKeyException e) {
            throw AcademicScope.conflict("code_in_use", program.name() + " already has a course with the code " + code + ".");
        }
        scope.changed("academics.course.created", "course", id, details("code", code, "name", name, "program", program.code(), "department", dept.name()));
        return id;
    }

    @Transactional
    public void updateCourse(UUID id, CourseCommand c) {
        scope.lock("course", id);
        CourseView before = loadCourse(id);
        scope.requireManage(PROGRAM, before.departmentId());
        version(c.version(), before.version());
        if (c.programId() != null && !c.programId().equals(before.programId())) {
            throw ApiException.badRequest("program_immutable", "A course cannot move to another program; create a new course instead.");
        }
        ProgramView program = loadProgram(before.programId());
        AcademicScope.Unit dept = c.departmentId() == null || c.departmentId().equals(before.departmentId())
                ? scope.find(before.departmentId()).orElseThrow() : department(c.departmentId(), program);
        if (!dept.id().equals(before.departmentId())) scope.requireManage(PROGRAM, dept.id());
        String code = Text.code(c.code());
        String name = Text.required("name", c.name(), 200);
        Integer terms = courseTerms(c.totalTerms(), program);
        int effective = terms == null ? program.totalTerms() : terms;
        Integer used = jdbc.sql("SELECT max(term_number) FROM curriculum_entry WHERE course_id = ?").param(id).query(Integer.class).optional().orElse(null);
        if (used != null && used > effective) throw AcademicScope.invalid("terms_in_use", "The curriculum uses term " + used + "; remove those entries first.");
        try {
            jdbc.sql("""
                    UPDATE course SET code = ?, name = ?, department_id = ?, specialization = ?, total_terms = ?, description = ?, updated_at = now(),
                        updated_by = ?, version = version + 1 WHERE id = ?""")
                    .params(code, name, dept.id(), Text.optional(c.specialization(), 200), terms, Text.optional(c.description(), 1000), actor(), id).update();
        } catch (DuplicateKeyException e) {
            throw AcademicScope.conflict("code_in_use", program.name() + " already has a course with the code " + code + ".");
        }
        Map<String, String> d = details("code", code);
        diff(d, "name", before.name(), name);
        diff(d, "department", before.departmentName(), dept.name());
        diff(d, "totalTerms", String.valueOf(before.totalTerms()), String.valueOf(terms));
        scope.changed("academics.course.updated", "course", id, d);
    }

    @Transactional
    public void setCourseStatus(UUID id, Status status, long version, String reason) {
        scope.lock("course", id);
        CourseView c = loadCourse(id);
        scope.requireManage(PROGRAM, c.departmentId());
        version(version, c.version());
        if (c.status() == status) throw AcademicScope.conflict("invalid_status_transition", "The course is already " + status.name().toLowerCase() + ".");
        if (status == Status.ACTIVE && loadProgram(c.programId()).status() != Status.ACTIVE) {
            throw AcademicScope.invalid("program_inactive", "Activate the program first.");
        }
        jdbc.sql("UPDATE course SET status = ?, updated_at = now(), updated_by = ?, version = version + 1 WHERE id = ?").params(status.name(), actor(), id).update();
        scope.changed(status == Status.ACTIVE ? "academics.course.activated" : "academics.course.deactivated", "course", id,
                details("code", c.code(), "reason", reason, "batches", String.valueOf(c.batchCount())));
    }

    /** A department inside the program's institution. */
    private AcademicScope.Unit department(UUID id, ProgramView program) {
        AcademicScope.Unit dept = scope.activeUnit(id, Set.of("DEPARTMENT"), "department", "a department");
        AcademicScope.Unit owner = scope.find(program.ownerOrgUnitId()).orElseThrow();
        if (!AcademicScope.within(dept.path(), owner.path())) {
            throw AcademicScope.invalid("department_outside_institution", dept.name() + " does not belong to " + owner.name() + ".");
        }
        return dept;
    }

    private static Integer courseTerms(Integer requested, ProgramView program) {
        if (requested == null) return null;
        if (requested < 1 || requested > program.totalTerms()) {
            throw ApiException.badRequest("invalid_total_terms", "Use 1 to " + program.totalTerms() + " terms (the program's length).");
        }
        return requested;
    }

    CourseView loadCourse(UUID id) {
        return jdbc.sql(COURSE_SELECT + " WHERE c.id = ?").param(id).query(this::mapCourse).optional().orElseThrow(() -> ApiException.notFound("Course"));
    }

    private CourseView mapCourse(ResultSet rs, int n) throws SQLException {
        Integer terms = (Integer) rs.getObject("total_terms");
        UUID dept = rs.getObject("department_id", UUID.class);
        return new CourseView(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"), rs.getObject("program_id", UUID.class),
                rs.getString("program_code"), rs.getString("program_name"), dept, rs.getString("department_name"), rs.getString("specialization"),
                terms, terms == null ? rs.getInt("program_terms") : terms, rs.getString("description"), Status.valueOf(rs.getString("status")),
                rs.getLong("batch_count"), rs.getLong("curriculum_count"), Text.instant(rs, "updated_at"), rs.getLong("version"), scope.canManage(PROGRAM, dept));
    }

    // ================================================================================================ subjects

    private static final String SUBJECT_SELECT = """
            SELECT s.*, ou.name AS owner_name, (SELECT count(DISTINCT e.course_id) FROM curriculum_entry e WHERE e.subject_id = s.id) AS course_count
            FROM subject s JOIN org_unit ou ON ou.id = s.owner_org_unit_id""";
    private static final Map<String, String> SUBJECT_SORTS = Map.of("name", "s.name", "code", "s.code", "credits", "s.credits",
            "category", "s.category", "status", "s.status", "updatedAt", "s.updated_at");

    @Transactional(readOnly = true)
    public Page<SubjectView> subjects(String q, Status status, Category category, UUID ownerOrgUnitId, UUID courseId, int page, int size, String sort, boolean asc) {
        scope.requireAnyRead();
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (status != null) { where.append(" AND s.status = ?"); params.add(status.name()); }
        if (category != null) { where.append(" AND s.category = ?"); params.add(category.name()); }
        if (ownerOrgUnitId != null) { where.append(" AND s.owner_org_unit_id = ?"); params.add(ownerOrgUnitId); }
        if (courseId != null) { where.append(" AND EXISTS (SELECT 1 FROM curriculum_entry e WHERE e.subject_id = s.id AND e.course_id = ?)"); params.add(courseId); }
        search(q, where, params, "s.name", "s.code", "s.description");
        if (!scope.appendVisibility("ou.path", where, params)) return new Page<>(List.of(), 0);
        return page(SUBJECT_SELECT, "subject s JOIN org_unit ou ON ou.id = s.owner_org_unit_id", where, params, page, size,
                SUBJECT_SORTS.getOrDefault(sort, "s.name"), asc, "s.id", this::mapSubject);
    }

    @Transactional(readOnly = true)
    public SubjectDetail subject(UUID id) {
        SubjectView s = loadSubject(id);
        scope.requireRead(s.ownerOrgUnitId());
        return new SubjectDetail(s,
                jdbc.sql("SELECT s.id, s.code, s.name, s.status FROM subject_prerequisite p JOIN subject s ON s.id = p.prerequisite_id WHERE p.subject_id = ? ORDER BY s.code")
                        .param(id).query(this::ref).list(),
                jdbc.sql("SELECT s.id, s.code, s.name, s.status FROM subject_prerequisite p JOIN subject s ON s.id = p.subject_id WHERE p.prerequisite_id = ? ORDER BY s.code")
                        .param(id).query(this::ref).list(),
                curriculum(null, id));
    }

    @Transactional
    public UUID createSubject(SubjectCommand c) {
        AcademicScope.Unit owner = scope.activeUnit(c.ownerOrgUnitId(), ANY_UNIT, "owner", "a unit");
        scope.requireManage(SUBJECT, owner.id());
        SubjectFields f = subjectFields(c);
        UUID id = Ids.newId();
        try {
            jdbc.sql("""
                    INSERT INTO subject (id, tenant_id, owner_org_unit_id, code, name, description, credits, delivery, elective, category, created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(id, tenant(), owner.id(), f.code, f.name, f.description, f.credits, f.delivery.name(), f.elective, f.category.name(), actor(), actor())
                    .update();
        } catch (DuplicateKeyException e) {
            throw AcademicScope.conflict("code_in_use", owner.name() + " already has a subject with the code " + f.code + ".");
        }
        scope.changed("academics.subject.created", "subject", id, details("code", f.code, "name", f.name, "owner", owner.name(), "credits", f.credits.toPlainString()));
        return id;
    }

    @Transactional
    public void updateSubject(UUID id, SubjectCommand c) {
        scope.lock("subject", id);
        SubjectView before = loadSubject(id);
        scope.requireManage(SUBJECT, before.ownerOrgUnitId());
        version(c.version(), before.version());
        if (c.ownerOrgUnitId() != null && !c.ownerOrgUnitId().equals(before.ownerOrgUnitId())) {
            throw ApiException.badRequest("owner_immutable", "A subject cannot move to another unit.");
        }
        SubjectFields f = subjectFields(c);
        try {
            jdbc.sql("""
                    UPDATE subject SET code = ?, name = ?, description = ?, credits = ?, delivery = ?, elective = ?, category = ?, updated_at = now(),
                        updated_by = ?, version = version + 1 WHERE id = ?""")
                    .params(f.code, f.name, f.description, f.credits, f.delivery.name(), f.elective, f.category.name(), actor(), id).update();
        } catch (DuplicateKeyException e) {
            throw AcademicScope.conflict("code_in_use", before.ownerName() + " already has a subject with the code " + f.code + ".");
        }
        Map<String, String> d = details("code", f.code);
        diff(d, "name", before.name(), f.name);
        diff(d, "credits", before.credits().toPlainString(), f.credits.toPlainString());
        diff(d, "category", before.category().name(), f.category.name());
        scope.changed("academics.subject.updated", "subject", id, d);
    }

    @Transactional
    public void setSubjectStatus(UUID id, Status status, long version, String reason) {
        scope.lock("subject", id);
        SubjectView s = loadSubject(id);
        scope.requireManage(SUBJECT, s.ownerOrgUnitId());
        version(version, s.version());
        if (s.status() == status) throw AcademicScope.conflict("invalid_status_transition", "The subject is already " + status.name().toLowerCase() + ".");
        jdbc.sql("UPDATE subject SET status = ?, updated_at = now(), updated_by = ?, version = version + 1 WHERE id = ?").params(status.name(), actor(), id).update();
        scope.changed(status == Status.ACTIVE ? "academics.subject.activated" : "academics.subject.deactivated", "subject", id,
                details("code", s.code(), "reason", reason));
    }

    @Transactional
    public void addPrerequisite(UUID subjectId, UUID prerequisiteId) {
        SubjectView s = loadSubject(subjectId);
        scope.requireManage(SUBJECT, s.ownerOrgUnitId());
        SubjectView p = loadSubject(prerequisiteId);
        scope.requireRead(p.ownerOrgUnitId());
        if (subjectId.equals(prerequisiteId)) throw AcademicScope.invalid("prerequisite_cycle", "A subject cannot be its own prerequisite.");
        boolean cycle = jdbc.sql("""
                WITH RECURSIVE chain(id) AS (
                    SELECT prerequisite_id FROM subject_prerequisite WHERE subject_id = ?
                    UNION SELECT sp.prerequisite_id FROM subject_prerequisite sp JOIN chain c ON sp.subject_id = c.id)
                SELECT count(*) FROM chain WHERE id = ?""").params(prerequisiteId, subjectId).query(Long.class).single() > 0;
        if (cycle) throw AcademicScope.invalid("prerequisite_cycle", p.code() + " already requires " + s.code() + " (directly or indirectly).");
        try {
            jdbc.sql("INSERT INTO subject_prerequisite (tenant_id, subject_id, prerequisite_id, created_by) VALUES (?, ?, ?, ?)")
                    .params(tenant(), subjectId, prerequisiteId, actor()).update();
        } catch (DuplicateKeyException e) {
            throw AcademicScope.conflict("prerequisite_exists", p.code() + " is already a prerequisite.");
        }
        scope.changed("academics.subject.prerequisite_added", "subject", subjectId, details("subject", s.code(), "prerequisite", p.code()));
    }

    @Transactional
    public void removePrerequisite(UUID subjectId, UUID prerequisiteId) {
        SubjectView s = loadSubject(subjectId);
        scope.requireManage(SUBJECT, s.ownerOrgUnitId());
        int n = jdbc.sql("DELETE FROM subject_prerequisite WHERE subject_id = ? AND prerequisite_id = ?").params(subjectId, prerequisiteId).update();
        if (n == 0) throw ApiException.notFound("Prerequisite");
        scope.changed("academics.subject.prerequisite_removed", "subject", subjectId, details("subject", s.code(), "prerequisite", prerequisiteId.toString()));
    }

    private record SubjectFields(String code, String name, String description, BigDecimal credits, Delivery delivery, boolean elective, Category category) {}

    private static SubjectFields subjectFields(SubjectCommand c) {
        BigDecimal credits = c.credits();
        if (credits == null || credits.signum() < 0 || credits.compareTo(BigDecimal.valueOf(40)) > 0) {
            throw ApiException.badRequest("invalid_credits", "Credits must be between 0 and 40.");
        }
        if (c.delivery() == null) throw ApiException.badRequest("invalid_delivery", "Choose theory, practical or both.");
        if (c.category() == null) throw ApiException.badRequest("invalid_category", "Choose a category.");
        boolean elective = c.elective() != null ? c.elective() : (c.category() == Category.ELECTIVE || c.category() == Category.OPEN_ELECTIVE);
        return new SubjectFields(Text.code(c.code()), Text.required("name", c.name(), 200), Text.optional(c.description(), 2000),
                credits.setScale(1, RoundingMode.HALF_UP), c.delivery(), elective, c.category());
    }

    SubjectView loadSubject(UUID id) {
        return jdbc.sql(SUBJECT_SELECT + " WHERE s.id = ?").param(id).query(this::mapSubject).optional().orElseThrow(() -> ApiException.notFound("Subject"));
    }

    private SubjectView mapSubject(ResultSet rs, int n) throws SQLException {
        UUID owner = rs.getObject("owner_org_unit_id", UUID.class);
        return new SubjectView(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"), rs.getString("description"),
                rs.getBigDecimal("credits"), Delivery.valueOf(rs.getString("delivery")), rs.getBoolean("elective"), Category.valueOf(rs.getString("category")),
                owner, rs.getString("owner_name"), Status.valueOf(rs.getString("status")), rs.getLong("course_count"), Text.instant(rs, "updated_at"),
                rs.getLong("version"), scope.canManage(SUBJECT, owner));
    }

    private SubjectRef ref(ResultSet rs, int n) throws SQLException {
        return new SubjectRef(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), Status.valueOf(rs.getString(4)));
    }

    // ================================================================================================ curriculum

    @Transactional(readOnly = true)
    public List<CurriculumView> courseCurriculum(UUID courseId) {
        CourseView c = loadCourse(courseId);
        scope.requireRead(c.departmentId());
        return curriculum(courseId, null);
    }

    /**
     * Places a subject into a term of a course. The subject must be active and belong to the course's institution
     * (owned by the institution, one of its units, or an ancestor such as the university), so subjects are reused
     * across courses without copying, but never across unrelated institutions.
     */
    @Transactional
    public UUID addCurriculumEntry(UUID courseId, UUID subjectId, int termNumber, Boolean elective) {
        CourseView course = loadCourse(courseId);
        scope.requireManage(SUBJECT, course.departmentId());
        if (course.status() != Status.ACTIVE) throw AcademicScope.invalid("course_inactive", course.name() + " is inactive.");
        SubjectView subject = loadSubject(subjectId);
        if (subject.status() != Status.ACTIVE) throw AcademicScope.invalid("subject_inactive", subject.name() + " is inactive.");
        if (termNumber < 1 || termNumber > course.effectiveTerms()) {
            throw AcademicScope.invalid("invalid_term_number", "Choose a term between 1 and " + course.effectiveTerms() + ".");
        }
        ProgramView program = loadProgram(course.programId());
        String institution = scope.find(program.ownerOrgUnitId()).orElseThrow().path();
        String owner = scope.find(subject.ownerOrgUnitId()).orElseThrow().path();
        if (!AcademicScope.within(owner, institution) && !AcademicScope.within(institution, owner)) {
            throw AcademicScope.invalid("subject_outside_institution", subject.name() + " belongs to another institution and cannot be used in " + program.ownerName() + ".");
        }
        UUID id = Ids.newId();
        try {
            jdbc.sql("INSERT INTO curriculum_entry (id, tenant_id, course_id, subject_id, term_number, elective, created_by) VALUES (?, ?, ?, ?, ?, ?, ?)")
                    .params(id, tenant(), courseId, subjectId, termNumber, elective != null ? elective : subject.elective(), actor()).update();
        } catch (DuplicateKeyException e) {
            throw AcademicScope.conflict("curriculum_entry_exists", subject.code() + " is already in term " + termNumber + " of " + course.code() + ".");
        }
        scope.changed("academics.curriculum.added", "course", courseId, details("course", course.code(), "subject", subject.code(), "term", String.valueOf(termNumber)));
        return id;
    }

    @Transactional
    public void removeCurriculumEntry(UUID courseId, UUID entryId) {
        CourseView course = loadCourse(courseId);
        scope.requireManage(SUBJECT, course.departmentId());
        record E(UUID subject, int term) {}
        E e = jdbc.sql("SELECT subject_id, term_number FROM curriculum_entry WHERE id = ? AND course_id = ?").params(entryId, courseId)
                .query((rs, n) -> new E(rs.getObject(1, UUID.class), rs.getInt(2))).optional().orElseThrow(() -> ApiException.notFound("Curriculum entry"));
        long offerings = jdbc.sql("SELECT count(*) FROM subject_offering WHERE course_id = ? AND subject_id = ? AND term_number = ? AND status <> 'CANCELLED'")
                .params(courseId, e.subject(), e.term()).query(Long.class).single();
        if (offerings > 0) throw AcademicScope.conflict("curriculum_entry_in_use", "This subject has been offered " + offerings + " time(s) in that term; the entry is kept for history.");
        jdbc.sql("DELETE FROM curriculum_entry WHERE id = ?").param(entryId).update();
        scope.changed("academics.curriculum.removed", "course", courseId, details("course", course.code(), "subject", e.subject().toString(), "term", String.valueOf(e.term())));
    }

    List<CurriculumView> curriculum(UUID courseId, UUID subjectId) {
        String where = courseId != null ? "e.course_id = ?" : "e.subject_id = ?";
        return jdbc.sql("""
                SELECT e.id, c.id, c.code, c.name, p.name, s.id, s.code, s.name, s.credits, e.term_number, e.elective, s.category, s.delivery
                FROM curriculum_entry e JOIN course c ON c.id = e.course_id JOIN program p ON p.id = c.program_id JOIN subject s ON s.id = e.subject_id
                WHERE\s""" + where + " ORDER BY e.term_number, s.code, c.code")
                .param(courseId != null ? courseId : subjectId)
                .query((rs, n) -> new CurriculumView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getObject(6, UUID.class), rs.getString(7), rs.getString(8), rs.getBigDecimal(9), rs.getInt(10), rs.getBoolean(11),
                        Category.valueOf(rs.getString(12)), Delivery.valueOf(rs.getString(13))))
                .list();
    }

    // ================================================================================================ helpers

    <T> Page<T> page(String select, String from, StringBuilder where, List<Object> params, int page, int size, String order, boolean asc,
                     String tiebreak, org.springframework.jdbc.core.RowMapper<T> mapper) {
        long total = jdbc.sql("SELECT count(*) FROM " + from + where).params(params).query(Long.class).single();
        List<Object> p = new ArrayList<>(params);
        p.add(size);
        p.add((long) page * size);
        return new Page<>(jdbc.sql(select + where + " ORDER BY " + order + (asc ? " ASC" : " DESC") + " NULLS LAST, " + tiebreak + " LIMIT ? OFFSET ?")
                .params(p).query(mapper).list(), total);
    }

    static void search(String q, StringBuilder where, List<Object> params, String... columns) {
        if (q == null || q.isBlank()) return;
        String like = Text.like(q);
        where.append(" AND (").append(String.join(" OR ", java.util.Arrays.stream(columns).map(c -> c + " ILIKE ?").toList())).append(")");
        for (int i = 0; i < columns.length; i++) params.add(like);
    }

    static void version(Long given, long current) {
        if (given == null || given != current) throw AcademicScope.conflict("concurrent_modification", "This record was changed by someone else. Reload and try again.");
    }

    static Map<String, String> details(String... kv) {
        Map<String, String> d = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) if (kv[i + 1] != null) d.put(kv[i], kv[i + 1]);
        return d;
    }

    static void diff(Map<String, String> d, String key, String before, String after) {
        if (!Objects.equals(before, after)) d.put(key, before + " → " + after);
    }

    static UUID tenant() { return TenantContext.requireTenantId(); }

    static UUID actor() { return CurrentUser.require().userId(); }
}
