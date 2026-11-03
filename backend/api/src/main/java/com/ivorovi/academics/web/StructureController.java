package com.ivorovi.academics.web;

import com.ivorovi.academics.internal.StructureService;
import com.ivorovi.academics.internal.StructureService.Category;
import com.ivorovi.academics.internal.StructureService.Delivery;
import com.ivorovi.academics.internal.StructureService.Level;
import com.ivorovi.academics.internal.StructureService.Status;
import com.ivorovi.academics.internal.StructureService.TermSystem;
import com.ivorovi.platform.idempotency.Idempotent;
import com.ivorovi.platform.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Programs, courses (branches), subjects, prerequisites and curricula. Rules live in {@link StructureService}. */
@RestController
@RequestMapping("/api/v1/academics")
@Tag(name = "Academic structure")
@Validated
class StructureController {

    @Schema(name = "ProgramRequest")
    record ProgramRequest(@Size(max = 30) String code, @Size(max = 200) String name, Level qualificationLevel, @Size(max = 200) String awardedQualification,
                          UUID ownerOrgUnitId, TermSystem termSystem, @Min(1) @Max(6) Integer termsPerYear, BigDecimal durationYears,
                          @Min(1) @Max(40) Integer totalTerms, @Size(max = 1000) String description, Long version) {}

    @Schema(name = "CourseRequest")
    record CourseRequest(@Size(max = 30) String code, @Size(max = 200) String name, UUID programId, UUID departmentId, @Size(max = 200) String specialization,
                         @Min(1) @Max(40) Integer totalTerms, @Size(max = 1000) String description, Long version) {}

    @Schema(name = "SubjectRequest")
    record SubjectRequest(@Size(max = 30) String code, @Size(max = 200) String name, @Size(max = 2000) String description, BigDecimal credits,
                          Delivery delivery, Boolean elective, Category category, UUID ownerOrgUnitId, Long version) {}

    @Schema(name = "AcademicStatusRequest")
    record StatusRequest(@NotNull Status status, @NotNull Long version, @Size(max = 500) String reason) {}

    @Schema(name = "CurriculumEntryRequest")
    record CurriculumRequest(@NotNull UUID subjectId, @NotNull @Min(1) @Max(40) Integer termNumber, Boolean elective) {}

    @Schema(name = "PrerequisiteRequest")
    record PrerequisiteRequest(@NotNull UUID prerequisiteId) {}

    private static final String SORT = "name|code|level|status|updatedAt|program|department|credits|category";

    private final StructureService structure;

    StructureController(StructureService structure) {
        this.structure = structure;
    }

    // ---------------------------------------------------------------------------------------------------- programs

    @GetMapping("/programs")
    @PreAuthorize("@authz.hasAnywhere('academics.read')")
    @Operation(summary = "Programs visible to the caller (search, status/level/owner filters, sort, pagination)")
    PageResponse<StructureService.ProgramView> programs(@RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) Status status, @RequestParam(required = false) Level level,
            @RequestParam(required = false) UUID ownerOrgUnitId, @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") int size, @RequestParam(defaultValue = "name") @Pattern(regexp = SORT) String sort,
            @RequestParam(defaultValue = "asc") @Pattern(regexp = "asc|desc") String direction) {
        int s = PageResponse.clampSize(size);
        return page(structure.programs(q, status, level, ownerOrgUnitId, page, s, sort, "asc".equals(direction)), page, s);
    }

    @PostMapping("/programs")
    @Idempotent
    @PreAuthorize("@authz.hasAnywhere('program.manage')")
    @Operation(summary = "Create a program owned by a university or college")
    ResponseEntity<StructureService.ProgramView> createProgram(@Valid @RequestBody ProgramRequest r) {
        UUID id = structure.createProgram(program(r));
        return ResponseEntity.created(URI.create("/api/v1/academics/programs/" + id)).body(structure.program(id));
    }

    @GetMapping("/programs/{id}")
    @PreAuthorize("@authz.hasAnywhere('academics.read')")
    StructureService.ProgramView program(@PathVariable UUID id) {
        return structure.program(id);
    }

    @PatchMapping("/programs/{id}")
    @PreAuthorize("@authz.hasAnywhere('program.manage')")
    @Operation(summary = "Edit a program (optimistic version; the owner cannot change)")
    StructureService.ProgramView updateProgram(@PathVariable UUID id, @Valid @RequestBody ProgramRequest r) {
        structure.updateProgram(id, program(r));
        return structure.program(id);
    }

    @PostMapping("/programs/{id}/status")
    @PreAuthorize("@authz.hasAnywhere('program.manage')")
    @Operation(summary = "Activate or deactivate a program (records are kept)")
    StructureService.ProgramView programStatus(@PathVariable UUID id, @Valid @RequestBody StatusRequest r) {
        structure.setProgramStatus(id, r.status(), r.version(), r.reason());
        return structure.program(id);
    }

    // ---------------------------------------------------------------------------------------------------- courses

    @GetMapping("/courses")
    @PreAuthorize("@authz.hasAnywhere('academics.read')")
    @Operation(summary = "Courses (branches) visible to the caller")
    PageResponse<StructureService.CourseView> courses(@RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) Status status, @RequestParam(required = false) UUID programId,
            @RequestParam(required = false) UUID departmentId, @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") int size, @RequestParam(defaultValue = "name") @Pattern(regexp = SORT) String sort,
            @RequestParam(defaultValue = "asc") @Pattern(regexp = "asc|desc") String direction) {
        int s = PageResponse.clampSize(size);
        return page(structure.courses(q, status, programId, departmentId, page, s, sort, "asc".equals(direction)), page, s);
    }

    @PostMapping("/courses")
    @Idempotent
    @PreAuthorize("@authz.hasAnywhere('program.manage')")
    @Operation(summary = "Create a course of a program, owned by a department of the program's institution")
    ResponseEntity<StructureService.CourseView> createCourse(@Valid @RequestBody CourseRequest r) {
        UUID id = structure.createCourse(course(r));
        return ResponseEntity.created(URI.create("/api/v1/academics/courses/" + id)).body(structure.course(id));
    }

    @GetMapping("/courses/{id}")
    @PreAuthorize("@authz.hasAnywhere('academics.read')")
    StructureService.CourseView course(@PathVariable UUID id) {
        return structure.course(id);
    }

    @PatchMapping("/courses/{id}")
    @PreAuthorize("@authz.hasAnywhere('program.manage')")
    StructureService.CourseView updateCourse(@PathVariable UUID id, @Valid @RequestBody CourseRequest r) {
        structure.updateCourse(id, course(r));
        return structure.course(id);
    }

    @PostMapping("/courses/{id}/status")
    @PreAuthorize("@authz.hasAnywhere('program.manage')")
    StructureService.CourseView courseStatus(@PathVariable UUID id, @Valid @RequestBody StatusRequest r) {
        structure.setCourseStatus(id, r.status(), r.version(), r.reason());
        return structure.course(id);
    }

    @GetMapping("/courses/{id}/curriculum")
    @PreAuthorize("@authz.hasAnywhere('academics.read')")
    @Operation(summary = "Subjects taught in each term of the course")
    List<StructureService.CurriculumView> curriculum(@PathVariable UUID id) {
        return structure.courseCurriculum(id);
    }

    @PostMapping("/courses/{id}/curriculum")
    @Idempotent
    @PreAuthorize("@authz.hasAnywhere('subject.manage')")
    @Operation(summary = "Teach a subject in a term of the course (subjects are reused, never copied)")
    List<StructureService.CurriculumView> addCurriculum(@PathVariable UUID id, @Valid @RequestBody CurriculumRequest r) {
        structure.addCurriculumEntry(id, r.subjectId(), r.termNumber(), r.elective());
        return structure.courseCurriculum(id);
    }

    @DeleteMapping("/courses/{id}/curriculum/{entryId}")
    @PreAuthorize("@authz.hasAnywhere('subject.manage')")
    @Operation(summary = "Remove a curriculum entry that has not been offered")
    ResponseEntity<Void> removeCurriculum(@PathVariable UUID id, @PathVariable UUID entryId) {
        structure.removeCurriculumEntry(id, entryId);
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------------------------------------------- subjects

    @GetMapping("/subjects")
    @PreAuthorize("@authz.hasAnywhere('academics.read')")
    @Operation(summary = "Subject masters visible to the caller (filter by course to see a curriculum's subjects)")
    PageResponse<StructureService.SubjectView> subjects(@RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) Status status, @RequestParam(required = false) Category category,
            @RequestParam(required = false) UUID ownerOrgUnitId, @RequestParam(required = false) UUID courseId,
            @RequestParam(defaultValue = "0") @Min(0) int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "name") @Pattern(regexp = SORT) String sort,
            @RequestParam(defaultValue = "asc") @Pattern(regexp = "asc|desc") String direction) {
        int s = PageResponse.clampSize(size);
        return page(structure.subjects(q, status, category, ownerOrgUnitId, courseId, page, s, sort, "asc".equals(direction)), page, s);
    }

    @PostMapping("/subjects")
    @Idempotent
    @PreAuthorize("@authz.hasAnywhere('subject.manage')")
    ResponseEntity<StructureService.SubjectDetail> createSubject(@Valid @RequestBody SubjectRequest r) {
        UUID id = structure.createSubject(subject(r));
        return ResponseEntity.created(URI.create("/api/v1/academics/subjects/" + id)).body(structure.subject(id));
    }

    @GetMapping("/subjects/{id}")
    @PreAuthorize("@authz.hasAnywhere('academics.read')")
    @Operation(summary = "Subject with prerequisites, dependants and the courses/terms that teach it")
    StructureService.SubjectDetail subject(@PathVariable UUID id) {
        return structure.subject(id);
    }

    @PatchMapping("/subjects/{id}")
    @PreAuthorize("@authz.hasAnywhere('subject.manage')")
    StructureService.SubjectDetail updateSubject(@PathVariable UUID id, @Valid @RequestBody SubjectRequest r) {
        structure.updateSubject(id, subject(r));
        return structure.subject(id);
    }

    @PostMapping("/subjects/{id}/status")
    @PreAuthorize("@authz.hasAnywhere('subject.manage')")
    StructureService.SubjectDetail subjectStatus(@PathVariable UUID id, @Valid @RequestBody StatusRequest r) {
        structure.setSubjectStatus(id, r.status(), r.version(), r.reason());
        return structure.subject(id);
    }

    @PostMapping("/subjects/{id}/prerequisites")
    @PreAuthorize("@authz.hasAnywhere('subject.manage')")
    @Operation(summary = "Add a prerequisite (cycles are refused)")
    StructureService.SubjectDetail addPrerequisite(@PathVariable UUID id, @Valid @RequestBody PrerequisiteRequest r) {
        structure.addPrerequisite(id, r.prerequisiteId());
        return structure.subject(id);
    }

    @DeleteMapping("/subjects/{id}/prerequisites/{prerequisiteId}")
    @PreAuthorize("@authz.hasAnywhere('subject.manage')")
    StructureService.SubjectDetail removePrerequisite(@PathVariable UUID id, @PathVariable UUID prerequisiteId) {
        structure.removePrerequisite(id, prerequisiteId);
        return structure.subject(id);
    }

    // ---------------------------------------------------------------------------------------------------- mapping

    private static StructureService.ProgramCommand program(ProgramRequest r) {
        return new StructureService.ProgramCommand(r.code(), r.name(), r.qualificationLevel(), r.awardedQualification(), r.ownerOrgUnitId(),
                r.termSystem(), r.termsPerYear(), r.durationYears(), r.totalTerms(), r.description(), r.version());
    }

    private static StructureService.CourseCommand course(CourseRequest r) {
        return new StructureService.CourseCommand(r.code(), r.name(), r.programId(), r.departmentId(), r.specialization(), r.totalTerms(),
                r.description(), r.version());
    }

    private static StructureService.SubjectCommand subject(SubjectRequest r) {
        return new StructureService.SubjectCommand(r.code(), r.name(), r.description(), r.credits(), r.delivery(), r.elective(), r.category(),
                r.ownerOrgUnitId(), r.version());
    }

    static <T> PageResponse<T> page(StructureService.Page<T> r, int page, int size) {
        return new PageResponse<>(r.items(), page, size, r.total(), (int) Math.ceil(r.total() / (double) size));
    }
}
