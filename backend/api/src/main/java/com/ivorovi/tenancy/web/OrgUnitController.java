package com.ivorovi.tenancy.web;

import com.ivorovi.platform.idempotency.Idempotent;
import com.ivorovi.platform.web.PageResponse;
import com.ivorovi.tenancy.OrgUnitType;
import com.ivorovi.tenancy.internal.OrgUnitService;
import com.ivorovi.tenancy.internal.OrgUnitService.Detail;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.Map;
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

/**
 * Organization hierarchy API. Authorization is enforced in {@link OrgUnitService} because the required scope
 * (the unit, its parent, or a new parent) depends on the request.
 */
@RestController
@RequestMapping("/api/v1/org-units")
@Tag(name = "Organization")
@Validated
class OrgUnitController {

    @io.swagger.v3.oas.annotations.media.Schema(name = "OrgUnitCreateRequest")
    record CreateRequest(@NotNull OrgUnitType type, UUID parentId, @NotNull @Size(min = 1, max = 63) String code,
                         @NotNull @Size(min = 1, max = 200) String name, @Size(max = 50) String shortName,
                         Map<String, String> attributes, Map<String, String> branding) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "OrgUnitUpdateRequest")
    record UpdateRequest(@NotNull Long version, @Size(min = 1, max = 63) String code, @Size(min = 1, max = 200) String name,
                         @Size(max = 50) String shortName, Map<String, String> attributes, Map<String, String> branding) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "OrgUnitMoveRequest")
    record MoveRequest(UUID newParentId, @NotNull Long version, @Size(max = 500) String reason) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "OrgUnitStatusRequest")
    record StatusRequest(@NotNull Long version, @Size(max = 500) String reason) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "OrgUnitSummaryResponse")
    record Summary(Map<OrgUnitType, Long> active, Map<OrgUnitType, Long> inactive, List<OrgUnitService.Distribution> institutions) {}

    private final OrgUnitService service;

    OrgUnitController(OrgUnitService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("@authz.hasAnywhere('org_unit.read')")
    @Operation(summary = "List organization units visible to the caller (search, filters, sort, pagination)")
    PageResponse<OrgUnitService.OrgUnit> list(
            @RequestParam(required = false) OrgUnitType type,
            @RequestParam(required = false) UUID parentId,
            @RequestParam(required = false) OrgUnitService.Status status,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "name") @Pattern(regexp = "name|code|type|createdAt|status") String sort,
            @RequestParam(defaultValue = "asc") @Pattern(regexp = "asc|desc") String direction) {
        int s = PageResponse.clampSize(size);
        var result = service.list(new OrgUnitService.Query(type, parentId, status, q), page, s, sort, "asc".equals(direction));
        return new PageResponse<>(result.items(), page, s, result.total(), (int) Math.ceil(result.total() / (double) s));
    }

    @GetMapping("/tree")
    @PreAuthorize("@authz.hasAnywhere('org_unit.read')")
    @Operation(summary = "All visible units (flat, with parent ids) for building the hierarchy tree")
    List<OrgUnitService.TreeNode> tree() {
        return service.tree();
    }

    @GetMapping("/summary")
    @PreAuthorize("@authz.hasAnywhere('org_unit.read')")
    @Operation(summary = "Counts by type and status, and campuses/departments per institution (visible units)")
    Summary summary() {
        var s = service.summary();
        return new Summary(s.active(), s.inactive(), s.institutions());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Unit details with ancestors (breadcrumb), child counts and dependents")
    Detail get(@PathVariable UUID id) {
        return service.detail(id);
    }

    @GetMapping("/{id}/history")
    @Operation(summary = "Change history of a unit (newest first)")
    PageResponse<OrgUnitService.HistoryEntry> history(@PathVariable UUID id,
                                                      @RequestParam(defaultValue = "0") @Min(0) int page,
                                                      @RequestParam(defaultValue = "20") int size) {
        int s = PageResponse.clampSize(size);
        var h = service.history(id, page, s);
        return new PageResponse<>(h.items(), page, s, h.total(), (int) Math.ceil(h.total() / (double) s));
    }

    @PostMapping
    @Idempotent
    @Operation(summary = "Create a unit (requires org_unit.manage on the parent; tenant-wide for top-level units)")
    ResponseEntity<Detail> create(@Valid @RequestBody CreateRequest r) {
        Detail d = service.create(new OrgUnitService.CreateCommand(r.type(), r.parentId(), r.code(), r.name(), r.shortName(),
                r.attributes(), r.branding()));
        return ResponseEntity.created(URI.create("/api/v1/org-units/" + d.unit().id())).body(d);
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Edit code, name, short name, attributes or branding (optimistic version)")
    Detail update(@PathVariable UUID id, @Valid @RequestBody UpdateRequest r) {
        return service.update(id, new OrgUnitService.UpdateCommand(r.version(), r.code(), r.name(), r.shortName(),
                r.attributes(), r.branding()));
    }

    @PostMapping("/{id}/move")
    @Operation(summary = "Move a unit (and its subtree) under a new parent, or to the top level")
    Detail move(@PathVariable UUID id, @Valid @RequestBody MoveRequest r) {
        return service.move(id, r.newParentId(), r.version(), r.reason());
    }

    @PostMapping("/{id}/deactivate")
    @Operation(summary = "Deactivate a unit (not allowed while it has active sub-units)")
    Detail deactivate(@PathVariable UUID id, @Valid @RequestBody StatusRequest r) {
        return service.setStatus(id, OrgUnitService.Status.INACTIVE, r.version(), r.reason());
    }

    @PostMapping("/{id}/activate")
    @Operation(summary = "Activate a unit (its parent must be active)")
    Detail activate(@PathVariable UUID id, @Valid @RequestBody StatusRequest r) {
        return service.setStatus(id, OrgUnitService.Status.ACTIVE, r.version(), r.reason());
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete an unused unit (otherwise 409 org_unit_has_dependents: deactivate instead)")
    ResponseEntity<Void> delete(@PathVariable UUID id, @RequestParam long version) {
        service.delete(id, version);
        return ResponseEntity.noContent().build();
    }
}
