package com.ivorovi.features;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FeatureResolverTest {

    @Test
    void deploymentCeilingWins() {
        var s = FeatureResolver.resolve(FeatureFlag.MODULE_ADMISSIONS, true, Optional.of(true), List.of(Optional.of(true)));
        assertThat(s.enabled()).isFalse();
        assertThat(s.source()).isEqualTo(FeatureState.Source.DEPLOYMENT);
    }

    @Test
    void defaultsApplyWithoutOverrides() {
        assertThat(FeatureResolver.resolve(FeatureFlag.AI_EVALUATION, false, Optional.empty(), List.of(Optional.empty())).enabled()).isFalse();
        assertThat(FeatureResolver.resolve(FeatureFlag.MODULE_ADMISSIONS, false, Optional.empty(), List.of(Optional.empty())).enabled()).isTrue();
    }

    @Test
    void institutionCannotExceedTenant() {
        var s = FeatureResolver.resolve(FeatureFlag.AI_EVALUATION, false, Optional.of(false), List.of(Optional.of(true)));
        assertThat(s.enabled()).isFalse();
        assertThat(s.source()).isEqualTo(FeatureState.Source.TENANT);
    }

    @Test
    void institutionMayNarrow() {
        var s = FeatureResolver.resolve(FeatureFlag.AI_EVALUATION, false, Optional.of(true), List.of(Optional.of(false)));
        assertThat(s.enabled()).isFalse();
        assertThat(s.source()).isEqualTo(FeatureState.Source.INSTITUTION);
    }

    @Test
    void integrationsIgnoreInstitutionOverrides() {
        var s = FeatureResolver.resolve(FeatureFlag.INTEGRATION_LTI, false, Optional.of(true), List.of(Optional.of(false)));
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

    @Test
    void chainAppliesTopDownAndNeverReEnablesBelowAnOff() {
        // university off, college on → off (college cannot exceed the university)
        var s = FeatureResolver.resolve(FeatureFlag.AI_EVALUATION, false, Optional.of(true), List.of(Optional.of(false), Optional.of(true)));
        assertThat(s.enabled()).isFalse();
        // university inherits (on), college off → off from INSTITUTION
        var t = FeatureResolver.resolve(FeatureFlag.MODULE_PLACEMENT, false, Optional.empty(), List.of(Optional.empty(), Optional.of(false)));
        assertThat(t.enabled()).isFalse();
        assertThat(t.source()).isEqualTo(FeatureState.Source.INSTITUTION);
        // nothing overridden in the chain → tenant level
        var u = FeatureResolver.resolve(FeatureFlag.MODULE_PLACEMENT, false, Optional.empty(), List.of(Optional.empty(), Optional.empty()));
        assertThat(u.enabled()).isTrue();
        assertThat(u.source()).isEqualTo(FeatureState.Source.DEFAULT);
    }
}
