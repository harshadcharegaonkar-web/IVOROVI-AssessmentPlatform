package com.ivorovi.students.web;

import com.ivorovi.platform.idempotency.Idempotent;
import com.ivorovi.students.internal.AdmissionService;
import com.ivorovi.students.internal.AdmissionService.AdmissionStatus;
import com.ivorovi.students.internal.EnrolmentService;
import com.ivorovi.students.internal.EnrolmentService.ChangeType;
import com.ivorovi.students.internal.GuardianService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
import org.springframework.web.bind.annotation.RestController;

/** Admission records, enrolments (with history) and guardians of a student. */
@RestController
@RequestMapping("/api/v1/students/{studentId}")
@Tag(name = "Students")
@Validated
class StudentRecordsController {

    @Schema(name = "AdmissionRequest")
    record AdmissionRequest(@Size(max = 40) String admissionNo, LocalDate admissionDate, @Size(max = 40) String admissionType,
                            @Size(max = 40) String admissionCategory, UUID entryAcademicYearId, UUID programId, UUID courseId,
                            @Min(1) @Max(40) Integer entryTermNumber, AdmissionStatus status, @Size(max = 200) String previousInstitutionName,
                            @Size(max = 500) String previousInstitutionDetails, Long version) {}

    @Schema(name = "AdmissionStatusRequest")
    record AdmissionStatusRequest(@NotNull AdmissionStatus status, @Size(max = 500) String reason, @NotNull Long version) {}

    @Schema(name = "EnrolmentRequest")
    record EnrolmentRequest(@NotNull ChangeType type, @NotNull UUID batchId, UUID academicYearId, UUID termId, @Min(1) @Max(40) Integer termNumber,
                            LocalDate effectiveOn, @Size(max = 500) String reason) {}

    @Schema(name = "GuardianRequest")
    record GuardianRequest(@Size(max = 200) String name, @Size(max = 40) String relationship, @Size(max = 20) String mobile, @Size(max = 320) String email,
                           @Size(max = 500) String address, Boolean primary, Boolean emergency, Long version) {}

    private final AdmissionService admissions;
    private final EnrolmentService enrolments;
    private final GuardianService guardians;

    StudentRecordsController(AdmissionService admissions, EnrolmentService enrolments, GuardianService guardians) {
        this.admissions = admissions;
        this.enrolments = enrolments;
        this.guardians = guardians;
    }

    // ---------------------------------------------------------------------------------------------------- admissions

    @GetMapping("/admissions")
    @PreAuthorize("@authz.hasAnywhere('student.read')")
    @Operation(summary = "Admission records of the student (categories only with student.personal.read)")
    List<AdmissionService.AdmissionView> admissions(@PathVariable UUID studentId) {
        return admissions.list(studentId);
    }

    @PostMapping("/admissions")
    @Idempotent
    @PreAuthorize("@authz.hasAnywhere('student.manage')")
    @Operation(summary = "Record the admission (regular, lateral entry, transfer or a configured type); confirming admits the student")
    List<AdmissionService.AdmissionView> createAdmission(@PathVariable UUID studentId, @Valid @RequestBody AdmissionRequest r) {
        admissions.create(studentId, admission(r));
        return admissions.list(studentId);
    }

    @PatchMapping("/admissions/{admissionId}")
    @PreAuthorize("@authz.hasAnywhere('student.manage')")
    List<AdmissionService.AdmissionView> updateAdmission(@PathVariable UUID studentId, @PathVariable UUID admissionId, @Valid @RequestBody AdmissionRequest r) {
        admissions.update(studentId, admissionId, admission(r));
        return admissions.list(studentId);
    }

    @PostMapping("/admissions/{admissionId}/status")
    @PreAuthorize("@authz.hasAnywhere('student.manage')")
    @Operation(summary = "Confirm or cancel an admission")
    List<AdmissionService.AdmissionView> admissionStatus(@PathVariable UUID studentId, @PathVariable UUID admissionId, @Valid @RequestBody AdmissionStatusRequest r) {
        admissions.setStatus(studentId, admissionId, r.status(), r.reason(), r.version());
        return admissions.list(studentId);
    }

    // ---------------------------------------------------------------------------------------------------- enrolments

    @GetMapping("/enrolments")
    @PreAuthorize("@authz.hasAnywhere('student.read')")
    @Operation(summary = "Academic history: every enrolment, current first")
    List<EnrolmentService.EnrolmentView> enrolments(@PathVariable UUID studentId) {
        return enrolments.history(studentId);
    }

    @PostMapping("/enrolments")
    @Idempotent
    @PreAuthorize("@authz.hasAnywhere('student.manage')")
    @Operation(summary = "Initial enrolment, progression, promotion, batch or course transfer (closes the current enrolment, keeps history)")
    List<EnrolmentService.EnrolmentView> changeEnrolment(@PathVariable UUID studentId, @Valid @RequestBody EnrolmentRequest r) {
        enrolments.change(studentId, new EnrolmentService.Command(r.type(), r.batchId(), r.academicYearId(), r.termId(), r.termNumber(), r.effectiveOn(), r.reason()));
        return enrolments.history(studentId);
    }

    // ---------------------------------------------------------------------------------------------------- guardians

    @GetMapping("/guardians")
    @PreAuthorize("@authz.hasAnywhere('student.personal.read')")
    @Operation(summary = "Guardians (personal information; access is audited)")
    List<GuardianService.GuardianView> guardians(@PathVariable UUID studentId) {
        return guardians.list(studentId);
    }

    @PostMapping("/guardians")
    @Idempotent
    @PreAuthorize("@authz.hasAnywhere('student.manage')")
    List<GuardianService.GuardianView> addGuardian(@PathVariable UUID studentId, @Valid @RequestBody GuardianRequest r) {
        guardians.add(studentId, guardian(r));
        return guardians.list(studentId);
    }

    @PatchMapping("/guardians/{guardianId}")
    @PreAuthorize("@authz.hasAnywhere('student.manage')")
    List<GuardianService.GuardianView> updateGuardian(@PathVariable UUID studentId, @PathVariable UUID guardianId, @Valid @RequestBody GuardianRequest r) {
        guardians.update(studentId, guardianId, guardian(r));
        return guardians.list(studentId);
    }

    @DeleteMapping("/guardians/{guardianId}")
    @PreAuthorize("@authz.hasAnywhere('student.manage')")
    ResponseEntity<Void> removeGuardian(@PathVariable UUID studentId, @PathVariable UUID guardianId) {
        guardians.remove(studentId, guardianId);
        return ResponseEntity.noContent().build();
    }

    private static AdmissionService.Command admission(AdmissionRequest r) {
        return new AdmissionService.Command(r.admissionNo(), r.admissionDate(), r.admissionType(), r.admissionCategory(), r.entryAcademicYearId(), r.programId(),
                r.courseId(), r.entryTermNumber(), r.status(), r.previousInstitutionName(), r.previousInstitutionDetails(), r.version());
    }

    private static GuardianService.Command guardian(GuardianRequest r) {
        return new GuardianService.Command(r.name(), r.relationship(), r.mobile(), r.email(), r.address(), r.primary(), r.emergency(), r.version());
    }
}
