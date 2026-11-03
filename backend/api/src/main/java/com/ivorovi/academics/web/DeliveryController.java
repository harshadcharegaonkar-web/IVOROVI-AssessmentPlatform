package com.ivorovi.academics.web;

import com.ivorovi.academics.internal.DeliveryService;
import com.ivorovi.academics.internal.DeliveryService.BatchStatus;
import com.ivorovi.academics.internal.DeliveryService.OfferingStatus;
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
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Batches, subject offerings and faculty allocation. Rules live in {@link DeliveryService}. */
@RestController
@RequestMapping("/api/v1/academics")
@Tag(name = "Academic delivery")
@Validated
class DeliveryController {

    @Schema(name = "BatchRequest")
    record BatchRequest(@Size(max = 40) String code, @Size(max = 200) String name, UUID courseId, UUID academicYearId, Integer admissionYear,
                        Integer expectedGraduationYear, @Size(max = 20) String section, Integer capacity, BatchStatus status, Long version) {}

    @Schema(name = "BatchStatusRequest")
    record BatchStatusRequest(@NotNull BatchStatus status, @NotNull Long version, @Size(max = 500) String reason) {}

    @Schema(name = "OfferingRequest")
    record OfferingRequest(@NotNull UUID subjectId, @NotNull UUID batchId, @NotNull UUID termId, @NotNull @Min(1) @Max(40) Integer termNumber,
                           @Size(max = 20) String section, BigDecimal credits) {}

    @Schema(name = "OfferingStatusRequest")
    record OfferingStatusRequest(@NotNull OfferingStatus status, @NotNull Long version, @Size(max = 500) String reason) {}

    @Schema(name = "FacultyAssignRequest")
    record AssignRequest(@NotNull UUID staffProfileId, Boolean primary, LocalDate startsOn, LocalDate endsOn) {}

    @Schema(name = "FacultyEndRequest")
    record EndRequest(@NotNull @Size(min = 1, max = 500) String reason, LocalDate endsOn) {}

    private final DeliveryService delivery;

    DeliveryController(DeliveryService delivery) {
        this.delivery = delivery;
    }

    // ---------------------------------------------------------------------------------------------------- batches

    @GetMapping("/batches")
    @PreAuthorize("@authz.hasAnywhere('academics.read')")
    @Operation(summary = "Batches visible to the caller (filters: status, course, program, academic year, admission year)")
    PageResponse<DeliveryService.BatchView> batches(@RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) BatchStatus status, @RequestParam(required = false) UUID courseId,
            @RequestParam(required = false) UUID programId, @RequestParam(required = false) UUID academicYearId,
            @RequestParam(required = false) Integer admissionYear, @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "admissionYear") @Pattern(regexp = "name|code|admissionYear|course|status|updatedAt") String sort,
            @RequestParam(defaultValue = "desc") @Pattern(regexp = "asc|desc") String direction) {
        int s = PageResponse.clampSize(size);
        return StructureController.page(delivery.batches(q, status, courseId, programId, academicYearId, admissionYear, page, s, sort,
                "asc".equals(direction)), page, s);
    }

    @PostMapping("/batches")
    @Idempotent
    @PreAuthorize("@authz.hasAnywhere('batch.manage')")
    ResponseEntity<DeliveryService.BatchView> createBatch(@Valid @RequestBody BatchRequest r) {
        UUID id = delivery.createBatch(batch(r));
        return ResponseEntity.created(URI.create("/api/v1/academics/batches/" + id)).body(delivery.batch(id));
    }

    @GetMapping("/batches/{id}")
    @PreAuthorize("@authz.hasAnywhere('academics.read')")
    DeliveryService.BatchView batch(@PathVariable UUID id) {
        return delivery.batch(id);
    }

    @PatchMapping("/batches/{id}")
    @PreAuthorize("@authz.hasAnywhere('batch.manage')")
    DeliveryService.BatchView updateBatch(@PathVariable UUID id, @Valid @RequestBody BatchRequest r) {
        delivery.updateBatch(id, batch(r));
        return delivery.batch(id);
    }

    @PostMapping("/batches/{id}/status")
    @PreAuthorize("@authz.hasAnywhere('batch.manage')")
    DeliveryService.BatchView batchStatus(@PathVariable UUID id, @Valid @RequestBody BatchStatusRequest r) {
        delivery.setBatchStatus(id, r.status(), r.version(), r.reason());
        return delivery.batch(id);
    }

    // ---------------------------------------------------------------------------------------------------- offerings

    @GetMapping("/subject-offerings")
    @PreAuthorize("@authz.hasAnywhere('academics.read')")
    @Operation(summary = "Subject offerings visible to the caller")
    PageResponse<DeliveryService.OfferingView> offerings(@RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) OfferingStatus status, @RequestParam(required = false) UUID batchId,
            @RequestParam(required = false) UUID termId, @RequestParam(required = false) UUID courseId,
            @RequestParam(required = false) UUID subjectId, @RequestParam(required = false) UUID academicYearId,
            @RequestParam(required = false) Boolean withoutFaculty, @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "subject") @Pattern(regexp = "subject|batch|term|termNumber|status|updatedAt") String sort,
            @RequestParam(defaultValue = "asc") @Pattern(regexp = "asc|desc") String direction) {
        int s = PageResponse.clampSize(size);
        return StructureController.page(delivery.offerings(q, status, batchId, termId, courseId, subjectId, academicYearId, withoutFaculty, page, s,
                sort, "asc".equals(direction)), page, s);
    }

    @PostMapping("/subject-offerings")
    @Idempotent
    @PreAuthorize("@authz.hasAnywhere('batch.manage')")
    @Operation(summary = "Offer a subject of the curriculum to a batch in a calendar term")
    ResponseEntity<DeliveryService.OfferingView> createOffering(@Valid @RequestBody OfferingRequest r) {
        UUID id = delivery.createOffering(new DeliveryService.OfferingCommand(r.subjectId(), r.batchId(), r.termId(), r.termNumber(), r.section(),
                r.credits()));
        return ResponseEntity.created(URI.create("/api/v1/academics/subject-offerings/" + id)).body(delivery.offering(id));
    }

    @GetMapping("/subject-offerings/{id}")
    @PreAuthorize("@authz.hasAnywhere('academics.read')")
    DeliveryService.OfferingView offering(@PathVariable UUID id) {
        return delivery.offering(id);
    }

    @PostMapping("/subject-offerings/{id}/status")
    @PreAuthorize("@authz.hasAnywhere('batch.manage')")
    @Operation(summary = "Planned → active → completed, or cancel (ends open faculty assignments)")
    DeliveryService.OfferingView offeringStatus(@PathVariable UUID id, @Valid @RequestBody OfferingStatusRequest r) {
        delivery.setOfferingStatus(id, r.status(), r.version(), r.reason());
        return delivery.offering(id);
    }

    // ---------------------------------------------------------------------------------------------------- faculty

    @GetMapping("/subject-offerings/{id}/faculty")
    @PreAuthorize("@authz.hasAnywhere('academics.read')")
    @Operation(summary = "Faculty assigned to the offering (optionally with ended assignments)")
    List<DeliveryService.FacultyView> faculty(@PathVariable UUID id, @RequestParam(defaultValue = "false") boolean includeHistory) {
        return delivery.faculty(id, includeHistory);
    }

    @GetMapping("/subject-offerings/{id}/faculty-candidates")
    @PreAuthorize("@authz.hasAnywhere('faculty.assign')")
    @Operation(summary = "Active teaching staff of the program's institution who can be assigned")
    List<DeliveryService.Candidate> candidates(@PathVariable UUID id, @RequestParam(required = false) @Size(max = 100) String q) {
        return delivery.candidates(id, q);
    }

    @PostMapping("/subject-offerings/{id}/faculty")
    @Idempotent
    @PreAuthorize("@authz.hasAnywhere('faculty.assign')")
    @Operation(summary = "Assign a staff member (a new primary replaces the previous primary)")
    List<DeliveryService.FacultyView> assign(@PathVariable UUID id, @Valid @RequestBody AssignRequest r) {
        delivery.assignFaculty(id, new DeliveryService.AssignCommand(r.staffProfileId(), r.primary(), r.startsOn(), r.endsOn()));
        return delivery.faculty(id, true);
    }

    @PostMapping("/subject-offerings/{id}/faculty/{assignmentId}/primary")
    @PreAuthorize("@authz.hasAnywhere('faculty.assign')")
    List<DeliveryService.FacultyView> makePrimary(@PathVariable UUID id, @PathVariable UUID assignmentId) {
        delivery.makePrimary(id, assignmentId);
        return delivery.faculty(id, true);
    }

    @PostMapping("/subject-offerings/{id}/faculty/{assignmentId}/end")
    @PreAuthorize("@authz.hasAnywhere('faculty.assign')")
    @Operation(summary = "End an assignment with a reason (kept as history)")
    List<DeliveryService.FacultyView> end(@PathVariable UUID id, @PathVariable UUID assignmentId, @Valid @RequestBody EndRequest r) {
        delivery.endAssignment(id, assignmentId, r.reason(), r.endsOn());
        return delivery.faculty(id, true);
    }

    @GetMapping("/staff/{staffProfileId}/teaching")
    @PreAuthorize("@authz.hasAnywhere('academics.read')")
    @Operation(summary = "Offerings a staff member teaches (in offerings visible to the caller)")
    List<DeliveryService.TeachingView> teaching(@PathVariable UUID staffProfileId, @RequestParam(defaultValue = "false") boolean includeHistory) {
        return delivery.teaching(staffProfileId, includeHistory);
    }

    private static DeliveryService.BatchCommand batch(BatchRequest r) {
        return new DeliveryService.BatchCommand(r.code(), r.name(), r.courseId(), r.academicYearId(), r.admissionYear(), r.expectedGraduationYear(),
                r.section(), r.capacity(), r.status(), r.version());
    }
}
