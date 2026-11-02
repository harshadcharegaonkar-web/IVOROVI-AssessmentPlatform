package com.ivorovi.features.web;

import com.ivorovi.features.FeatureFlag;
import com.ivorovi.features.FeatureService;
import com.ivorovi.features.FeatureState;
import com.ivorovi.platform.web.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/features")
@Tag(name = "Features")
class FeatureController {

    record EffectiveFeature(String key, String category, boolean enabled, String source) {
        static EffectiveFeature of(FeatureState s) {
            return new EffectiveFeature(s.flag().key(), s.flag().category().name(), s.enabled(), s.source().name());
        }
    }

    record FeatureConfigurationView(String key, String category, String description, boolean defaultEnabled,
                                    boolean institutionOverridable, boolean deploymentDisabled, Boolean tenantOverride,
                                    Boolean unitOverride, boolean inheritedEnabled, String inheritedSource,
                                    boolean enabled, String source) {
        static FeatureConfigurationView of(FeatureService.FlagConfiguration c) {
            FeatureFlag f = c.flag();
            return new FeatureConfigurationView(f.key(), f.category().name(), f.description(), f.defaultEnabled(),
                    f.institutionOverridable(), c.deploymentDisabled(), c.tenantOverride().orElse(null),
                    c.unitOverride().orElse(null), c.inherited().enabled(), c.inherited().source().name(),
                    c.effective().enabled(), c.effective().source().name());
        }
    }

    record OverrideRequest(UUID orgUnitId, @NotNull Boolean enabled) {}

    private final FeatureService service;

    FeatureController(FeatureService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Effective features for the organization, or for any unit (inherited down the hierarchy)")
    List<EffectiveFeature> effective(@RequestParam(required = false) UUID orgUnitId) {
        return service.effective(orgUnitId).stream().map(EffectiveFeature::of).toList();
    }

    @GetMapping("/configuration")
    @Operation(summary = "Feature configuration for the organization or a university/college (requires feature.read in scope)")
    List<FeatureConfigurationView> configuration(@RequestParam(required = false) UUID orgUnitId) {
        return service.configurations(orgUnitId).stream().map(FeatureConfigurationView::of).toList();
    }

    @PutMapping("/{key}/override")
    @Operation(summary = "Set an organization-level (no orgUnitId) or university/college override (requires feature.manage in scope)")
    FeatureConfigurationView setOverride(@PathVariable String key, @Valid @RequestBody OverrideRequest body) {
        return FeatureConfigurationView.of(service.setOverride(flag(key), body.orgUnitId(), body.enabled()));
    }

    @DeleteMapping("/{key}/override")
    @Operation(summary = "Remove an override so the value is inherited again")
    FeatureConfigurationView clearOverride(@PathVariable String key, @RequestParam(required = false) UUID orgUnitId) {
        return FeatureConfigurationView.of(service.clearOverride(flag(key), orgUnitId));
    }

    private static FeatureFlag flag(String key) {
        return FeatureFlag.fromKey(key).orElseThrow(() -> ApiException.notFound("Feature"));
    }
}
