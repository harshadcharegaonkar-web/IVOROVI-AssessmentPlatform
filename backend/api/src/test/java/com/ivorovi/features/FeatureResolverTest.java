package com.ivorovi.features;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class FeatureResolverTest {

    @Test
    void deploymentCeilingWins() {
        var s = FeatureResolver.resolve(FeatureFlag.MODULE_ADMISSIONS, true, Optional.of(true), Optional.of(true));
        assertThat(s.enabled()).isFalse();
        assertThat(s.source()).isEqualTo(FeatureState.Source.DEPLOYMENT);
    }

    @Test
    void defaultsApplyWithoutOverrides() {
        assertThat(FeatureResolver.resolve(FeatureFlag.AI_EVALUATION, false, Optional.empty(), Optional.empty()).enabled()).isFalse();
        assertThat(FeatureResolver.resolve(FeatureFlag.MODULE_ADMISSIONS, false, Optional.empty(), Optional.empty()).enabled()).isTrue();
    }

    @Test
    void institutionCannotExceedTenant() {
        var s = FeatureResolver.resolve(FeatureFlag.AI_EVALUATION, false, Optional.of(false), Optional.of(true));
        assertThat(s.enabled()).isFalse();
        assertThat(s.source()).isEqualTo(FeatureState.Source.TENANT);
    }

    @Test
    void institutionMayNarrow() {
        var s = FeatureResolver.resolve(FeatureFlag.AI_EVALUATION, false, Optional.of(true), Optional.of(false));
        assertThat(s.enabled()).isFalse();
        assertThat(s.source()).isEqualTo(FeatureState.Source.INSTITUTION);
    }

    @Test
    void integrationsIgnoreInstitutionOverrides() {
        var s = FeatureResolver.resolve(FeatureFlag.INTEGRATION_LTI, false, Optional.of(true), Optional.of(false));
        assertThat(s.enabled()).isTrue();
    }

    @Test
    void everyAiAndIntegrationFlagDefaultsOff() {
        for (FeatureFlag f : FeatureFlag.values()) {
            if (f.category() == FeatureFlag.Category.AI || f.category() == FeatureFlag.Category.INTEGRATION) {
                assertThat(f.defaultEnabled()).as(f.key()).isFalse();
            }
        }
    }

    @Test
    void deploymentPatternsMatchPrefixes() {
        var p = new FeatureProperties(java.util.List.of("ai.*", "integration.lti"));
        assertThat(p.isDeploymentDisabled(FeatureFlag.AI_EVALUATION)).isTrue();
        assertThat(p.isDeploymentDisabled(FeatureFlag.INTEGRATION_LTI)).isTrue();
        assertThat(p.isDeploymentDisabled(FeatureFlag.INTEGRATION_SIS)).isFalse();
        assertThat(p.isDeploymentDisabled(FeatureFlag.MODULE_ADMISSIONS)).isFalse();
    }
}
