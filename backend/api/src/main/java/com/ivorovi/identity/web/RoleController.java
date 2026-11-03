package com.ivorovi.identity.web;

import com.ivorovi.identity.internal.AssignmentService;
import com.ivorovi.identity.internal.RoleService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Roles, the permission catalogue and the role-assignment overview. Rules live in {@link RoleService}. */
@RestController
@Tag(name = "Roles")
@Validated
class RoleController {

    @Schema(name = "RoleCreateRequest")
    record CreateRequest(@Size(max = 63) String code, @NotNull @Size(min = 1, max = 100) String name, @Size(max = 1000) String description,
                         UUID scopeOrgUnitId, @NotNull @Size(min = 1, max = 100) List<@Size(max = 100) String> permissions,
                         UUID clonedFromRoleId) {}

    @Schema(name = "RoleUpdateRequest")
    record UpdateRequest(@NotNull Long version, @Size(min = 1, max = 100) String name, @Size(max = 1000) String description,
                         @Size(min = 1, max = 100) List<@Size(max = 100) String> permissions) {}

    @Schema(name = "RoleStatusRequest")
    record StatusRequest(@NotNull RoleService.Status status, @NotNull Long version, @Size(max = 500) String reason) {}

    private final RoleService roles;
    private final AssignmentService assignments;

    RoleController(RoleService roles, AssignmentService assignments) {
        this.roles = roles;
        this.assignments = assignments;
    }

    @GetMapping("/api/v1/permissions")
    @PreAuthorize("@authz.hasAnywhere('role.read')")
    @Operation(summary = "Permission catalogue grouped by functional domain (planned permissions are listed but not grantable)")
    List<RoleService.DomainView> catalogue() {
        return roles.catalogue();
    }

    @GetMapping("/api/v1/roles")
    @PreAuthorize("@authz.hasAnywhere('role.read')")
    @Operation(summary = "System and custom roles (search, type and status filters, sort, pagination)")
    PageResponse<RoleService.RoleSummary> list(
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) @Pattern(regexp = "system|custom") String type,
            @RequestParam(required = false) RoleService.Status status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "name") @Pattern(regexp = "name|code|status|updatedAt|permissions") String sort,
            @RequestParam(defaultValue = "asc") @Pattern(regexp = "asc|desc") String direction) {
        int s = PageResponse.clampSize(size);
        var r = roles.list(q, type == null ? null : "system".equals(type), status, page, s, sort, "asc".equals(direction));
        return new PageResponse<>(r.items(), page, s, r.total(), (int) Math.ceil(r.total() / (double) s));
    }

    @GetMapping("/api/v1/roles/assignable")
    @PreAuthorize("@authz.hasAnywhere('role.read')")
    @Operation(summary = "Active roles that can be assigned, with their permissions and scope limits")
    List<AssignmentService.RoleView> assignable() {
        return assignments.assignableRoles();
    }

    @PostMapping("/api/v1/roles")
    @Idempotent
    @PreAuthorize("@authz.hasAnywhere('role.manage')")
    @Operation(summary = "Create a custom role (or clone one with clonedFromRoleId); only permissions you hold at its scope")
    ResponseEntity<RoleService.RoleDetail> create(@Valid @RequestBody CreateRequest r) {
        UUID id = roles.create(new RoleService.Command(r.code(), r.name(), r.description(), r.scopeOrgUnitId(), r.permissions(),
                r.clonedFromRoleId(), null));
        return ResponseEntity.created(URI.create("/api/v1/roles/" + id)).body(roles.detail(id));
    }

    @GetMapping("/api/v1/roles/{id}")
    @PreAuthorize("@authz.hasAnywhere('role.read')")
    RoleService.RoleDetail detail(@PathVariable UUID id) {
        return roles.detail(id);
    }

    @PatchMapping("/api/v1/roles/{id}")
    @PreAuthorize("@authz.hasAnywhere('role.manage')")
    @Operation(summary = "Edit a custom role (system roles are protected); changes apply to every holder immediately")
    RoleService.RoleDetail update(@PathVariable UUID id, @Valid @RequestBody UpdateRequest r) {
        roles.update(id, new RoleService.Command(null, r.name(), r.description(), null, r.permissions(), null, r.version()));
        return roles.detail(id);
    }

    @PostMapping("/api/v1/roles/{id}/status")
    @PreAuthorize("@authz.hasAnywhere('role.manage')")
    @Operation(summary = "Activate or deactivate a custom role; deactivation suspends all of its assignments at once")
    RoleService.RoleDetail status(@PathVariable UUID id, @Valid @RequestBody StatusRequest r) {
        roles.setStatus(id, r.status(), r.reason(), r.version());
        return roles.detail(id);
    }

    @DeleteMapping("/api/v1/roles/{id}")
    @PreAuthorize("@authz.hasAnywhere('role.manage')")
    @Operation(summary = "Archive a custom role that has no active assignments (history is kept)")
    ResponseEntity<Void> archive(@PathVariable UUID id, @RequestParam long version) {
        roles.archive(id, version);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/roles/{id}/holders")
    @PreAuthorize("@authz.hasAnywhere('role.read')")
    @Operation(summary = "Users who hold the role (only users visible to the caller)")
    PageResponse<RoleService.Holder> holders(@PathVariable UUID id, @RequestParam(defaultValue = "false") boolean includeInactive,
                                             @RequestParam(defaultValue = "0") @Min(0) int page, @RequestParam(defaultValue = "20") int size) {
        int s = PageResponse.clampSize(size);
        var r = roles.holders(id, includeInactive, page, s);
        return new PageResponse<>(r.items(), page, s, r.total(), (int) Math.ceil(r.total() / (double) s));
    }

    @GetMapping("/api/v1/role-assignments")
    @PreAuthorize("@authz.hasAnywhere('role.read')")
    @Operation(summary = "Current role assignments of visible users (filter by role, unit subtree or search)")
    PageResponse<AssignmentService.OverviewRow> overview(@RequestParam(required = false) @Size(max = 100) String q,
                                                         @RequestParam(required = false) UUID roleId,
                                                         @RequestParam(required = false) UUID orgUnitId,
                                                         @RequestParam(defaultValue = "0") @Min(0) int page,
                                                         @RequestParam(defaultValue = "20") int size) {
        int s = PageResponse.clampSize(size);
        var r = assignments.overview(q, roleId, orgUnitId, page, s);
        return new PageResponse<>(r.items(), page, s, r.total(), (int) Math.ceil(r.total() / (double) s));
    }
}
