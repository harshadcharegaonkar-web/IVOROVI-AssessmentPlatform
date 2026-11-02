package com.ivorovi.identity.web;

import com.ivorovi.identity.internal.AssignmentService;
import com.ivorovi.identity.internal.AssignmentService.Subject;
import com.ivorovi.identity.internal.StaffService;
import com.ivorovi.platform.idempotency.Idempotent;
import com.ivorovi.platform.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/staff")
@Tag(name = "Staff")
@Validated
class StaffController {

    @Schema(name = "StaffRequest")
    record StaffRequest(@NotNull @Size(min = 1, max = 50) String staffNo, @NotNull @Size(min = 1, max = 100) String givenName,
                        @Size(max = 100) String familyName, @Size(max = 320) String email, @Size(max = 30) String phone,
                        @NotNull StaffService.Category category, @Size(max = 100) String designation,
                        StaffService.EmploymentStatus employmentStatus, StaffService.EmploymentType employmentType,
                        LocalDate joinedOn, UUID orgUnitId, Long version) {}

    @Schema(name = "StaffLinkRequest")
    record LinkRequest(@NotNull UUID userId) {}

    private final StaffService staff;
    private final AssignmentService assignments;

    StaffController(StaffService staff, AssignmentService assignments) {
        this.staff = staff;
        this.assignments = assignments;
    }

    @GetMapping
    @PreAuthorize("@authz.hasAnywhere('staff.read')")
    @Operation(summary = "List staff profiles visible to the caller")
    PageResponse<StaffService.StaffView> list(
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) StaffService.Category category,
            @RequestParam(required = false) StaffService.EmploymentStatus status,
            @RequestParam(required = false) UUID orgUnitId,
            @RequestParam(required = false) Boolean linked,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "name") @Pattern(regexp = "name|staffNo|category|status|joinedOn") String sort,
            @RequestParam(defaultValue = "asc") @Pattern(regexp = "asc|desc") String direction) {
        int s = PageResponse.clampSize(size);
        var r = staff.list(new StaffService.Query(q, category, status, orgUnitId, linked), page, s, sort, "asc".equals(direction));
        return new PageResponse<>(r.items(), page, s, r.total(), (int) Math.ceil(r.total() / (double) s));
    }

    @PostMapping
    @Idempotent
    @PreAuthorize("@authz.hasAnywhere('staff.manage')")
    @Operation(summary = "Create a staff profile (no login account required)")
    ResponseEntity<StaffService.StaffDetail> create(@Valid @RequestBody StaffRequest r) {
        UUID id = staff.create(command(r));
        return ResponseEntity.created(URI.create("/api/v1/staff/" + id)).body(staff.detail(id));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@authz.hasAnywhere('staff.read')")
    StaffService.StaffDetail detail(@PathVariable UUID id) {
        return staff.detail(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@authz.hasAnywhere('staff.manage')")
    @Operation(summary = "Update a staff profile (optimistic version required)")
    StaffService.StaffDetail update(@PathVariable UUID id, @Valid @RequestBody StaffRequest r) {
        staff.update(id, command(r));
        return staff.detail(id);
    }

    @PostMapping("/{id}/link")
    @PreAuthorize("@authz.hasAnywhere('staff.manage')")
    @Operation(summary = "Link the profile to a user account")
    StaffService.StaffDetail link(@PathVariable UUID id, @Valid @RequestBody LinkRequest r) {
        staff.link(id, r.userId());
        return staff.detail(id);
    }

    @PostMapping("/{id}/unlink")
    @PreAuthorize("@authz.hasAnywhere('staff.manage')")
    StaffService.StaffDetail unlink(@PathVariable UUID id) {
        staff.unlink(id);
        return staff.detail(id);
    }

    @PostMapping("/{id}/org-assignments")
    @PreAuthorize("@authz.hasAnywhere('staff.manage')")
    AssignmentService.OrgAssignmentView addOrgAssignment(@PathVariable UUID id, @Valid @RequestBody UserController.OrgAssignmentRequest r) {
        return assignments.addOrgAssignment(Subject.STAFF, id,
                new AssignmentService.OrgAssignmentCommand(r.orgUnitId(), r.title(), Boolean.TRUE.equals(r.primary()), r.startsOn(), r.endsOn()));
    }

    @DeleteMapping("/{id}/org-assignments/{assignmentId}")
    @PreAuthorize("@authz.hasAnywhere('staff.manage')")
    ResponseEntity<Void> removeOrgAssignment(@PathVariable UUID id, @PathVariable UUID assignmentId) {
        assignments.removeOrgAssignment(Subject.STAFF, id, assignmentId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/org-assignments/{assignmentId}/primary")
    @PreAuthorize("@authz.hasAnywhere('staff.manage')")
    ResponseEntity<Void> makePrimary(@PathVariable UUID id, @PathVariable UUID assignmentId) {
        assignments.makePrimary(Subject.STAFF, id, assignmentId);
        return ResponseEntity.noContent().build();
    }

    private static StaffService.Command command(StaffRequest r) {
        return new StaffService.Command(r.staffNo(), r.givenName(), r.familyName(), r.email(), r.phone(), r.category(),
                r.designation(), r.employmentStatus(), r.employmentType(), r.joinedOn(), r.orgUnitId(), r.version());
    }
}
