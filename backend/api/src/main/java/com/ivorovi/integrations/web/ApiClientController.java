package com.ivorovi.integrations.web;

import com.ivorovi.integrations.internal.ApiClientService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * API clients (read-only machine access). The full key is returned only by create and rotate; it is never retrievable
 * afterwards. Clients send it as {@code X-Api-Key: <key>} (or {@code Authorization: ApiKey <key>}) on GET requests.
 */
@RestController
@RequestMapping("/api/v1/api-clients")
@Tag(name = "API clients")
@Validated
class ApiClientController {

    @Schema(name = "ApiClientRequest")
    record Request(@Size(max = 100) String name, @Size(max = 300) String description, @Size(max = 40) List<@Size(max = 60) String> scopes, UUID orgUnitId,
                   @Size(max = 50) List<@Size(max = 50) String> allowedIps, Instant expiresAt, Long version) {}

    @Schema(name = "ApiClientRotateRequest")
    record RotateRequest(@Min(0) @Max(168) int graceHours) {}

    @Schema(name = "ApiClientRevokeRequest")
    record RevokeRequest(@Size(max = 300) String reason) {}

    private final ApiClientService service;

    ApiClientController(ApiClientService service) {
        this.service = service;
    }

    @GetMapping("/scopes")
    @Operation(summary = "Scopes the caller can give an API client (read permissions they hold; never personal, audit or integration data)")
    List<ApiClientService.Scope> scopes() {
        return service.scopes();
    }

    @GetMapping
    @Operation(summary = "API clients with status, last use and 30-day usage")
    List<ApiClientService.View> list() {
        return service.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register an API client; returns the key once")
    ApiClientService.Issued create(@RequestBody Request r) {
        return service.create(new ApiClientService.Command(r.name(), r.description(), r.scopes(), r.orgUnitId(), r.allowedIps(), r.expiresAt(), null));
    }

    @GetMapping("/{id}")
    @Operation(summary = "API client with its keys (id and last four characters only) and daily usage")
    ApiClientService.Detail get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Change name, description, IP allow-list or expiry (scopes are fixed)")
    ApiClientService.View update(@PathVariable UUID id, @RequestBody Request r) {
        return service.update(id, new ApiClientService.Command(r.name(), r.description(), null, null, r.allowedIps(), r.expiresAt(), r.version()));
    }

    @PostMapping("/{id}/rotate")
    @Operation(summary = "Issue a new key; existing keys stay valid for the grace period (0-168 hours); returns the new key once")
    ApiClientService.Issued rotate(@PathVariable UUID id, @RequestBody RotateRequest r) {
        return service.rotate(id, r.graceHours());
    }

    @PostMapping("/{id}/keys/{keyId}/revoke")
    @Operation(summary = "Revoke one key immediately")
    ResponseEntity<Void> revokeKey(@PathVariable UUID id, @PathVariable UUID keyId) {
        service.revokeKey(id, keyId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/revoke")
    @Operation(summary = "Revoke the client and all its keys (permanent)")
    ApiClientService.View revoke(@PathVariable UUID id, @RequestBody RevokeRequest r) {
        return service.revoke(id, r.reason());
    }
}
