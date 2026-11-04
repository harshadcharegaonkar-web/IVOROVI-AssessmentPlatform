package com.ivorovi.integrations.web;

import com.ivorovi.integrations.internal.ServiceIdentityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Service identities (KI-59): read-only non-human principals with custodians and an independently approved envelope. */
@RestController
@RequestMapping("/api/v1/service-identities")
@Tag(name = "Service identities")
@Validated
class ServiceIdentityController {

    @Schema(name = "ServiceIdentityRequest")
    record Request(@Size(max = 100) String name, @Size(max = 500) String purpose, UUID orgUnitId, UUID businessCustodianId, UUID technicalCustodianId,
                   @Size(max = 40) List<@Size(max = 60) String> envelope, Long version) {}

    @Schema(name = "ServiceIdentityEnvelopeRequest")
    record EnvelopeRequest(@Size(max = 40) List<@Size(max = 60) String> envelope, Long version) {}

    @Schema(name = "ServiceIdentityCustodiansRequest")
    record CustodiansRequest(UUID businessCustodianId, UUID technicalCustodianId, Long version) {}

    @Schema(name = "ServiceIdentityVersionRequest")
    record VersionRequest(Long version) {}

    @Schema(name = "ServiceIdentityReasonRequest")
    record ReasonRequest(@Size(max = 300) String reason) {}

    @Schema(name = "ServiceIdentityRoleRequest")
    record RoleRequest(UUID roleId, UUID orgUnitId) {}

    private final ServiceIdentityService service;

    ServiceIdentityController(ServiceIdentityService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Service identities with custodians, envelope and status")
    List<ServiceIdentityService.View> list() {
        return service.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a service identity; its envelope waits for approval by a second integration manager")
    ServiceIdentityService.View create(@RequestBody Request r) {
        return service.create(new ServiceIdentityService.Command(r.name(), r.purpose(), r.orgUnitId(), r.businessCustodianId(), r.technicalCustodianId(),
                r.envelope(), null));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Identity with role assignments, API clients and current effective permissions")
    ServiceIdentityService.Detail get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PutMapping("/{id}/envelope")
    @Operation(summary = "Narrow the envelope (immediate) or propose a wider one (needs independent approval)")
    ServiceIdentityService.View envelope(@PathVariable UUID id, @RequestBody EnvelopeRequest r) {
        return service.changeEnvelope(id, r.envelope(), r.version());
    }

    @PostMapping("/{id}/envelope/approve")
    @Operation(summary = "Approve the proposed envelope (not by its proposer; approver must hold the permissions)")
    ServiceIdentityService.View approve(@PathVariable UUID id, @RequestBody VersionRequest r) {
        return service.approveEnvelope(id, r.version());
    }

    @PostMapping("/{id}/envelope/reject")
    @Operation(summary = "Reject the proposed envelope")
    ServiceIdentityService.View reject(@PathVariable UUID id, @RequestBody ReasonRequest r) {
        return service.rejectEnvelope(id, r.reason());
    }

    @PutMapping("/{id}/custodians")
    @Operation(summary = "Name the business and technical custodians (active users)")
    ServiceIdentityService.View custodians(@PathVariable UUID id, @RequestBody CustodiansRequest r) {
        return service.changeCustodians(id, r.businessCustodianId(), r.technicalCustodianId(), r.version());
    }

    @PostMapping("/{id}/roles")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Assign a role (must fit the envelope; ceiling rule applies)")
    ServiceIdentityService.RoleAssignment assignRole(@PathVariable UUID id, @RequestBody RoleRequest r) {
        return service.assignRole(id, r.roleId(), r.orgUnitId());
    }

    @DeleteMapping("/{id}/roles/{assignmentId}")
    @Operation(summary = "Revoke a role assignment (effective on the next request)")
    ResponseEntity<Void> revokeRole(@PathVariable UUID id, @PathVariable UUID assignmentId) {
        service.revokeRole(id, assignmentId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/suspend")
    @Operation(summary = "Suspend (its keys stop working)")
    ServiceIdentityService.View suspend(@PathVariable UUID id, @RequestBody ReasonRequest r) {
        return service.suspend(id, r.reason());
    }

    @PostMapping("/{id}/resume")
    @Operation(summary = "Resume (custodians must be active)")
    ServiceIdentityService.View resume(@PathVariable UUID id) {
        return service.resume(id);
    }

    @PostMapping("/{id}/disable")
    @Operation(summary = "Disable permanently; revokes its role assignments and API clients")
    ServiceIdentityService.View disable(@PathVariable UUID id, @RequestBody ReasonRequest r) {
        return service.disable(id, r.reason());
    }
}
