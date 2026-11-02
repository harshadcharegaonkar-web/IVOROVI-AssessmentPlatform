package com.ivorovi.identity.web;

import com.ivorovi.identity.internal.AssignmentService;
import com.ivorovi.identity.internal.AssignmentService.Subject;
import com.ivorovi.identity.internal.InvitationService;
import com.ivorovi.identity.internal.UserService;
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
import java.time.Instant;
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

/** User accounts. Scope checks live in the services (they depend on the user's units). */
@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Users")
@Validated
class UserController {

    @Schema(name = "UserCreateRequest")
    record CreateRequest(@NotNull @Size(max = 320) String email, @NotNull @Size(min = 1, max = 100) String givenName,
                         @Size(max = 100) String familyName, @Size(max = 30) String phone, UUID orgUnitId,
                         @Size(max = 100) String title, Boolean sendInvitation) {}

    @Schema(name = "UserUpdateRequest")
    record UpdateRequest(@NotNull Long version, @Size(max = 320) String email, @Size(min = 1, max = 100) String givenName,
                         @Size(max = 100) String familyName, @Size(max = 30) String phone) {}

    @Schema(name = "UserStatusRequest")
    record StatusRequest(@NotNull UserService.StatusAction action, @NotNull Long version, @Size(max = 500) String reason) {}

    @Schema(name = "OrgAssignmentRequest")
    record OrgAssignmentRequest(UUID orgUnitId, @Size(max = 100) String title, Boolean primary, LocalDate startsOn, LocalDate endsOn) {}

    @Schema(name = "RoleAssignmentRequest")
    record RoleAssignmentRequest(@NotNull UUID roleId, UUID orgUnitId, Instant validFrom, Instant validTo) {}

    @Schema(name = "RoleRevokeRequest")
    record RevokeRequest(@NotNull @Size(min = 1, max = 500) String reason) {}

    @Schema(name = "UserCreateResponse")
    record CreateResponse(UserService.UserDetail user, boolean invitationSent) {}

    private final UserService users;
    private final InvitationService invitations;
    private final AssignmentService assignments;

    UserController(UserService users, InvitationService invitations, AssignmentService assignments) {
        this.users = users;
        this.invitations = invitations;
        this.assignments = assignments;
    }

    @GetMapping
    @PreAuthorize("@authz.hasAnywhere('user.read')")
    @Operation(summary = "List users visible to the caller (search, filters, sort, pagination)")
    PageResponse<UserService.UserSummary> list(
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) UserService.AccountStatus status,
            @RequestParam(required = false) UserService.InvitationState invitation,
            @RequestParam(required = false) UUID orgUnitId,
            @RequestParam(required = false) @Pattern(regexp = "[A-Z_]{2,63}") String role,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "name") @Pattern(regexp = "name|email|status|lastLoginAt|createdAt") String sort,
            @RequestParam(defaultValue = "asc") @Pattern(regexp = "asc|desc") String direction) {
        int s = PageResponse.clampSize(size);
        var r = users.list(new UserService.Query(q, status, invitation, orgUnitId, role), page, s, sort, "asc".equals(direction));
        return new PageResponse<>(r.items(), page, s, r.total(), (int) Math.ceil(r.total() / (double) s));
    }

    @PostMapping
    @Idempotent
    @PreAuthorize("@authz.hasAnywhere('user.manage')")
    @Operation(summary = "Create a user (status INVITED) with a primary organizational assignment; optionally email an invitation")
    ResponseEntity<CreateResponse> create(@Valid @RequestBody CreateRequest r) {
        var result = users.create(new UserService.CreateCommand(r.email(), r.givenName(), r.familyName(), r.phone(), r.orgUnitId(),
                r.title(), !Boolean.FALSE.equals(r.sendInvitation())));
        result.invitation().ifPresent(invitations::deliver);
        return ResponseEntity.created(URI.create("/api/v1/users/" + result.id()))
                .body(new CreateResponse(users.detail(result.id()), result.invitation().isPresent()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@authz.hasAnywhere('user.read')")
    @Operation(summary = "User details, including what the caller may do with the account")
    UserService.UserDetail detail(@PathVariable UUID id) {
        return users.detail(id);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("@authz.hasAnywhere('user.manage')")
    @Operation(summary = "Edit profile fields (email only before the first sign-in)")
    UserService.UserDetail update(@PathVariable UUID id, @Valid @RequestBody UpdateRequest r) {
        users.update(id, new UserService.UpdateCommand(r.version(), r.email(), r.givenName(), r.familyName(), r.phone()));
        return users.detail(id);
    }

    @PostMapping("/{id}/status")
    @PreAuthorize("@authz.hasAnywhere('user.manage')")
    @Operation(summary = "Suspend, reactivate, deactivate or activate an account (also enables/disables the login)")
    UserService.UserDetail status(@PathVariable UUID id, @Valid @RequestBody StatusRequest r) {
        users.changeStatus(id, r.action(), r.reason(), r.version());
        return users.detail(id);
    }

    @PostMapping("/{id}/invitation")
    @PreAuthorize("@authz.hasAnywhere('user.manage')")
    @Operation(summary = "Send or re-send the invitation email (a new link replaces the previous one)")
    InvitationService.InvitationView sendInvitation(@PathVariable UUID id) {
        InvitationService.Issued issued = invitations.resend(id, users);
        invitations.deliver(issued);
        return users.detail(id).invitation();
    }

    @GetMapping("/{id}/access")
    @PreAuthorize("@authz.hasAnywhere('user.read')")
    @Operation(summary = "Access overview: organizational assignments, roles and effective permissions by scope")
    AssignmentService.Access access(@PathVariable UUID id) {
        return assignments.access(id);
    }

    @GetMapping("/{id}/org-assignments")
    @PreAuthorize("@authz.hasAnywhere('user.read')")
    List<AssignmentService.OrgAssignmentView> orgAssignments(@PathVariable UUID id) {
        return assignments.orgAssignments(Subject.USER, id);
    }

    @PostMapping("/{id}/org-assignments")
    @PreAuthorize("@authz.hasAnywhere('user.manage')")
    @Operation(summary = "Assign the user to a unit (or the whole organization when orgUnitId is omitted)")
    AssignmentService.OrgAssignmentView addOrgAssignment(@PathVariable UUID id, @Valid @RequestBody OrgAssignmentRequest r) {
        return assignments.addOrgAssignment(Subject.USER, id,
                new AssignmentService.OrgAssignmentCommand(r.orgUnitId(), r.title(), Boolean.TRUE.equals(r.primary()), r.startsOn(), r.endsOn()));
    }

    @DeleteMapping("/{id}/org-assignments/{assignmentId}")
    @PreAuthorize("@authz.hasAnywhere('user.manage')")
    ResponseEntity<Void> removeOrgAssignment(@PathVariable UUID id, @PathVariable UUID assignmentId) {
        assignments.removeOrgAssignment(Subject.USER, id, assignmentId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/org-assignments/{assignmentId}/primary")
    @PreAuthorize("@authz.hasAnywhere('user.manage')")
    ResponseEntity<Void> makePrimary(@PathVariable UUID id, @PathVariable UUID assignmentId) {
        assignments.makePrimary(Subject.USER, id, assignmentId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/role-assignments")
    @PreAuthorize("@authz.hasAnywhere('user.read')")
    @Operation(summary = "Role assignments of the user, including revoked and expired ones")
    List<AssignmentService.RoleAssignmentView> roleAssignments(@PathVariable UUID id) {
        return assignments.roleAssignments(id);
    }

    @PostMapping("/{id}/role-assignments")
    @PreAuthorize("@authz.hasAnywhere('role.assign')")
    @Operation(summary = "Assign a role at the organization or a unit (anti-escalation: you must hold its permissions there)")
    ResponseEntity<AssignmentService.RoleAssignmentView> assignRole(@PathVariable UUID id, @Valid @RequestBody RoleAssignmentRequest r) {
        var view = assignments.assignRole(id, new AssignmentService.RoleAssignmentCommand(r.roleId(), r.orgUnitId(), r.validFrom(), r.validTo()));
        return ResponseEntity.status(201).body(view);
    }

    @PostMapping("/{id}/role-assignments/{assignmentId}/revoke")
    @PreAuthorize("@authz.hasAnywhere('role.assign')")
    @Operation(summary = "Revoke a role assignment (kept in history with the reason)")
    ResponseEntity<Void> revokeRole(@PathVariable UUID id, @PathVariable UUID assignmentId, @Valid @RequestBody RevokeRequest r) {
        assignments.revokeRole(id, assignmentId, r.reason());
        return ResponseEntity.noContent().build();
    }
}
