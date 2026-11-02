package com.ivorovi.features;

import java.util.List;
import java.util.Optional;

/**
 * Pure resolution rule (unit-tested in isolation):
 * <ol>
 *   <li>deployment ceiling OFF → OFF;</li>
 *   <li>tenant level = tenant override, else catalog default;</li>
 *   <li>then walk the institution chain top-down (e.g. university → college): an override applies only while the
 *       value above it is ON, so a lower level can switch a feature off but never on when a higher level has it off;</li>
 *   <li>flags that are not institution-overridable (integrations) ignore the chain.</li>
 * </ol>
 */
public final class FeatureResolver {

    private FeatureResolver() {}

    public static FeatureState resolve(FeatureFlag flag, boolean deploymentDisabled, Optional<Boolean> tenantOverride,
                                       List<Optional<Boolean>> institutionChain) {
        if (deploymentDisabled) return new FeatureState(flag, false, FeatureState.Source.DEPLOYMENT);
        boolean value = tenantOverride.orElse(flag.defaultEnabled());
        FeatureState.Source source = tenantOverride.isPresent() ? FeatureState.Source.TENANT : FeatureState.Source.DEFAULT;
        if (!flag.institutionOverridable()) return new FeatureState(flag, value, source);
        for (Optional<Boolean> o : institutionChain) {
            if (!value) break;
            if (o.isPresent()) {
                value = o.get();
                source = FeatureState.Source.INSTITUTION;
            }
        }
        return new FeatureState(flag, value, source);
    }
}
