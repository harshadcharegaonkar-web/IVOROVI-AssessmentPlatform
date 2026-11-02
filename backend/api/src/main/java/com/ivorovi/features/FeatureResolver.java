package com.ivorovi.features;

import java.util.Optional;

/**
 * Pure resolution rule (unit-tested in isolation):
 * <ol>
 *   <li>deployment ceiling OFF → OFF;</li>
 *   <li>tenant level = tenant override, else catalog default;</li>
 *   <li>tenant level OFF → OFF (institutions cannot exceed their organisation);</li>
 *   <li>institution override (when the flag is institution-overridable), else tenant level.</li>
 * </ol>
 */
public final class FeatureResolver {

    private FeatureResolver() {}

    public static FeatureState resolve(FeatureFlag flag, boolean deploymentDisabled, Optional<Boolean> tenantOverride,
                                       Optional<Boolean> institutionOverride) {
        if (deploymentDisabled) return new FeatureState(flag, false, FeatureState.Source.DEPLOYMENT);
        boolean tenantLevel = tenantOverride.orElse(flag.defaultEnabled());
        FeatureState.Source tenantSource = tenantOverride.isPresent() ? FeatureState.Source.TENANT : FeatureState.Source.DEFAULT;
        if (!tenantLevel) return new FeatureState(flag, false, tenantSource);
        if (flag.institutionOverridable() && institutionOverride.isPresent()) {
            return new FeatureState(flag, institutionOverride.get(), FeatureState.Source.INSTITUTION);
        }
        return new FeatureState(flag, true, tenantSource);
    }
}
