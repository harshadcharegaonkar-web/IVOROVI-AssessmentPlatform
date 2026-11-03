package com.ivorovi.students.web;

import com.ivorovi.platform.idempotency.Idempotent;
import com.ivorovi.platform.web.PageResponse;
import com.ivorovi.students.internal.StudentAccountLink;
import com.ivorovi.students.internal.StudentOverviewService;
import com.ivorovi.students.internal.StudentService;
import com.ivorovi.students.internal.StudentService.Gender;
import com.ivorovi.students.internal.StudentService.Status;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.LocalDate;
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

/** Student profiles, search, lifecycle, history, dashboard and sign-in accounts. Rules live in {@link StudentService}. */
@RestController
@RequestMapping("/api/v1/students")
@Tag(name = "Students")
@Validated
class StudentController {

    @Schema(name = "StudentPersonalRequest")
    record PersonalRequest(LocalDate dateOfBirth, Gender gender, @Size(max = 60) String nationality, @Size(max = 320) String personalEmail,
                           @Size(max = 20) String mobile, @Size(max = 20) String alternatePhone, @Size(max = 500) String currentAddress,
                           @Size(max = 500) String permanentAddress) {}

    @Schema(name = "StudentRequest")
    record StudentRequest(UUID institutionId, @Size(max = 40) String studentNo, @Min(1990) @Max(2200) Integer admissionYear, @Size(max = 40) String registrationNo,
                          @Size(max = 40) String rollNo, @Size(max = 100) String previousInstitutionRef, @Size(max = 100) String firstName,
                          @Size(max = 100) String middleName, @Size(max = 100) String lastName, @Size(max = 320) String institutionalEmail,
                          @Valid PersonalRequest personal, Long version) {}

    @Schema(name = "StudentStatusRequest")
    record StatusRequest(@NotNull Status status, LocalDate effectiveOn, @Size(max = 500) String reason, @NotNull Long version) {}

    @Schema(name = "StudentActiveRequest")
    record ActiveRequest(@NotNull Boolean active, @Size(max = 500) String reason, @NotNull Long version) {}

    @Schema(name = "StudentAccountRequest")
    record AccountRequest(@Size(max = 320) String email) {}

    private final StudentService students;
    private final StudentOverviewService overview;
    private final StudentAccountLink accounts;

    StudentController(StudentService students, StudentOverviewService overview, StudentAccountLink accounts) {
        this.students = students;
        this.overview = overview;
        this.accounts = accounts;
    }

    @GetMapping
    @PreAuthorize("@authz.hasAnywhere('student.read')")
    @Operation(summary = "Search students (name, student ID, registration or roll number) with structure and status filters")
    PageResponse<StudentService.Summary> search(@RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) UUID institutionId, @RequestParam(required = false) UUID campusId,
            @RequestParam(required = false) UUID departmentId, @RequestParam(required = false) UUID programId,
            @RequestParam(required = false) UUID courseId, @RequestParam(required = false) UUID batchId,
            @RequestParam(required = false) UUID academicYearId, @RequestParam(required = false) @Min(1) @Max(40) Integer termNumber,
            @RequestParam(required = false) Status status, @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") @Min(0) int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "name") @Pattern(regexp = "name|studentNo|status|updatedAt|batch") String sort,
            @RequestParam(defaultValue = "asc") @Pattern(regexp = "asc|desc") String direction) {
        int s = PageResponse.clampSize(size);
        var r = students.search(new StudentService.Query(q, institutionId, campusId, departmentId, programId, courseId, batchId, academicYearId, termNumber,
                status, active), page, s, sort, "asc".equals(direction));
        return new PageResponse<>(r.items(), page, s, r.total(), (int) Math.ceil(r.total() / (double) s));
    }

    @GetMapping("/overview")
    @PreAuthorize("@authz.hasAnywhere('student.read')")
    @Operation(summary = "Student KPIs from the caller's authorized students (cached; invalidated by every student change)")
    StudentOverviewService.Kpis kpis() {
        return overview.kpis();
    }

    @PostMapping
    @Idempotent
    @PreAuthorize("@authz.hasAnywhere('student.manage')")
    @Operation(summary = "Create a student profile (prospective); the student ID is generated or validated by the institution's rule")
    ResponseEntity<StudentService.Detail> create(@Valid @RequestBody StudentRequest r) {
        UUID id = students.create(command(r));
        return ResponseEntity.created(URI.create("/api/v1/students/" + id)).body(students.detail(id));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@authz.hasAnywhere('student.read')")
    @Operation(summary = "Student profile; personal details only with student.personal.read")
    StudentService.Detail detail(@PathVariable UUID id) {
        return students.detail(id);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("@authz.hasAnywhere('student.manage')")
    @Operation(summary = "Edit a student profile (optimistic version; omit 'personal' to leave personal details unchanged)")
    StudentService.Detail update(@PathVariable UUID id, @Valid @RequestBody StudentRequest r) {
        students.update(id, command(r));
        return students.detail(id);
    }

    @PostMapping("/{id}/status")
    @PreAuthorize("@authz.hasAnywhere('student.manage')")
    @Operation(summary = "Change the lifecycle status (only valid transitions; reason required; history kept)")
    StudentService.Detail status(@PathVariable UUID id, @Valid @RequestBody StatusRequest r) {
        students.changeStatus(id, r.status(), r.effectiveOn(), r.reason(), r.version());
        return students.detail(id);
    }

    @PostMapping("/{id}/active")
    @PreAuthorize("@authz.hasAnywhere('student.manage')")
    @Operation(summary = "Activate or deactivate the profile (deactivated profiles cannot be changed)")
    StudentService.Detail active(@PathVariable UUID id, @Valid @RequestBody ActiveRequest r) {
        students.setActive(id, r.active(), r.reason(), r.version());
        return students.detail(id);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@authz.hasAnywhere('student.manage')")
    @Operation(summary = "Delete a profile created in error (prospective or cancelled, without academic records)")
    ResponseEntity<Void> delete(@PathVariable UUID id, @RequestParam long version) {
        students.delete(id, version);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/history")
    @PreAuthorize("@authz.hasAnywhere('student.read')")
    @Operation(summary = "Lifecycle status history and change log (no personal values)")
    List<StudentService.HistoryEntry> history(@PathVariable UUID id) {
        return students.history(id);
    }

    @PostMapping("/{id}/account")
    @PreAuthorize("@authz.hasAnywhere('student.account.manage')")
    @Operation(summary = "Create a sign-in account on demand (student role only) and send the invitation")
    StudentService.Detail createAccount(@PathVariable UUID id, @Valid @RequestBody AccountRequest r) {
        accounts.create(id, r.email());
        return students.detail(id);
    }

    private static StudentService.Command command(StudentRequest r) {
        PersonalRequest p = r.personal();
        return new StudentService.Command(r.institutionId(), r.studentNo(), r.admissionYear(), r.registrationNo(), r.rollNo(), r.previousInstitutionRef(),
                r.firstName(), r.middleName(), r.lastName(), r.institutionalEmail(),
                p == null ? null : new StudentService.PersonalCommand(p.dateOfBirth(), p.gender(), p.nationality(), p.personalEmail(), p.mobile(),
                        p.alternatePhone(), p.currentAddress(), p.permanentAddress()), r.version());
    }
}
