package com.ivorovi.integrations.web;

import com.ivorovi.integrations.internal.IntegrationAdapter;
import com.ivorovi.integrations.internal.IntegrationService;
import com.ivorovi.platform.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Integration configurations (integration.read to view, integration.manage to change and run). Secrets are write-only. */
@RestController
@RequestMapping("/api/v1/integrations")
@Tag(name = "Integrations")
@Validated
class IntegrationController {

    @Schema(name = "IntegrationRequest")
    record Request(IntegrationAdapter.Kind kind, @Size(max = 40) String adapter, @Size(max = 100) String name, @Size(max = 300) String description,
                   @Size(max = 20) Map<@Size(max = 40) String, @Size(max = 500) String> config,
                   @Schema(description = "Write-only. Omit to keep, empty string to remove") @Size(max = 1000) String secret, Long version) {}

    @Schema(name = "IntegrationEnabledRequest")
    record EnabledRequest(@NotNull Boolean enabled) {}

    private final IntegrationService service;

    IntegrationController(IntegrationService service) {
        this.service = service;
    }

    @GetMapping("/adapters")
    @Operation(summary = "Built-in, vendor-neutral adapters with their configuration fields")
    List<IntegrationAdapter.Info> adapters() {
        return service.adapters();
    }

    @GetMapping
    @Operation(summary = "Integrations with connection status, last check, last success and last sync")
    List<IntegrationService.View> list() {
        return service.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an integration (disabled until enabled); configuration validated by the adapter, targets SSRF-checked")
    IntegrationService.View create(@RequestBody Request r) {
        return service.create(new IntegrationService.Command(r.kind(), r.adapter(), r.name(), r.description(), r.config(), r.secret(), null));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Integration with adapter details, recent runs and 30-day run counts")
    IntegrationService.Detail get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Change name, description, configuration or secret (optimistic version check)")
    IntegrationService.View update(@PathVariable UUID id, @RequestBody Request r) {
        return service.update(id, new IntegrationService.Command(null, null, r.name(), r.description(), r.config(), r.secret(), r.version()));
    }

    @PutMapping("/{id}/enabled")
    @Operation(summary = "Enable or disable an integration")
    IntegrationService.View enabled(@PathVariable UUID id, @RequestBody EnabledRequest r) {
        return service.setEnabled(id, r.enabled());
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a disabled integration and its run history")
    ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/test")
    @Operation(summary = "Run a connection test now (with retries); the run is recorded")
    IntegrationService.Run test(@PathVariable UUID id) {
        return service.test(id);
    }

    @PostMapping("/{id}/sync")
    @Operation(summary = "Run a sync now (enabled integrations whose adapter supports it)")
    IntegrationService.Run sync(@PathVariable UUID id) {
        return service.sync(id);
    }

    @GetMapping("/{id}/runs")
    @Operation(summary = "Execution history (newest first)")
    PageResponse<IntegrationService.Run> runs(@PathVariable UUID id, @RequestParam(defaultValue = "0") @Min(0) int page,
                                              @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        IntegrationService.Page<IntegrationService.Run> p = service.runs(id, page, size);
        return new PageResponse<>(p.items(), page, size, p.total(), (int) Math.ceil(p.total() / (double) size));
    }

    @PostMapping("/{id}/runs/{runId}/retry")
    @Operation(summary = "Retry a failed run")
    IntegrationService.Run retry(@PathVariable UUID id, @PathVariable UUID runId) {
        return service.retry(id, runId);
    }
}
