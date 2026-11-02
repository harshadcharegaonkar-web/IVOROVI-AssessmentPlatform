package com.ivorovi.tenancy.web;

import com.ivorovi.tenancy.OrgUnitType;
import com.ivorovi.tenancy.internal.TenantSettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tenant/settings")
@Tag(name = "Organization")
class TenantSettingsController {

    record UpdateRequest(Map<OrgUnitType, TenantSettingsService.Label> labels, @Size(max = 64) String timeZone,
                         @NotNull Long version) {}

    private final TenantSettingsService service;

    TenantSettingsController(TenantSettingsService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Organization settings: unit-type display labels and time zone (all signed-in users)")
    TenantSettingsService.Settings get() {
        return service.get();
    }

    @PutMapping
    @Operation(summary = "Update organization settings (requires tenant.settings.manage)")
    TenantSettingsService.Settings update(@Valid @RequestBody UpdateRequest r) {
        return service.update(r.labels(), r.timeZone(), r.version());
    }
}
