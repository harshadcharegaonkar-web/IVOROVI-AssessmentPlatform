package com.ivorovi.features;

import static com.ivorovi.support.Fixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.support.Fixture;
import com.ivorovi.support.IntegrationTest;
import com.ivorovi.support.TestTokens;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class FeatureFlagIT extends IntegrationTest {

    @Autowired CacheManager cacheManager;

    @AfterEach
    void clean() {
        ownerJdbc().sql("DELETE FROM feature_flag_override").update();
        cacheManager.getCache("feature-overrides").clear();
    }

    private ResultActions as(Fixture.Caller c, MockHttpServletRequestBuilder r) throws Exception {
        return mvc.perform(r.header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.forCaller(c)));
    }

    private static MockHttpServletRequestBuilder setOverride(String key, UUID inst, boolean enabled) {
        String body = inst == null ? "{\"enabled\":" + enabled + "}"
                : "{\"orgUnitId\":\"" + inst + "\",\"enabled\":" + enabled + "}";
        return put("/api/v1/features/" + key + "/override").contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void aiFeaturesAreDisabledByDefaultForEveryTenant() throws Exception {
        for (Fixture.Caller c : new Fixture.Caller[] {ALICE_ORG_ADMIN_A, BELA_ORG_ADMIN_B}) {
            as(c, get("/api/v1/features"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.category == 'AI' && @.enabled == true)]").isEmpty())
                    .andExpect(jsonPath("$[?(@.key == 'ai.evaluation')].source").value("DEFAULT"));
        }
    }

    @Test
    void tenantOverrideAppliesOnlyToThatTenant() throws Exception {
        as(ALICE_ORG_ADMIN_A, setOverride("ai.evaluation", null, true))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true)).andExpect(jsonPath("$.source").value("TENANT"));
        as(ALICE_ORG_ADMIN_A, get("/api/v1/features")).andExpect(jsonPath("$[?(@.key == 'ai.evaluation')].enabled").value(true));
        as(BELA_ORG_ADMIN_B, get("/api/v1/features")).andExpect(jsonPath("$[?(@.key == 'ai.evaluation')].enabled").value(false));
    }

    @Test
    void institutionCanNarrowButNotExceedOrganisation() throws Exception {
        // AI is off at organisation level → an institution cannot switch it on
        as(ALICE_ORG_ADMIN_A, setOverride("ai.evaluation", INST_A1, true))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("disabled_at_organization_level"));
        // enable for organisation, disable for one institution
        as(ALICE_ORG_ADMIN_A, setOverride("ai.evaluation", null, true)).andExpect(status().isOk());
        as(ALICE_ORG_ADMIN_A, setOverride("ai.evaluation", INST_A2, false)).andExpect(status().isOk());
        as(ALICE_ORG_ADMIN_A, get("/api/v1/features").param("orgUnitId", INST_A1.toString()))
                .andExpect(jsonPath("$[?(@.key == 'ai.evaluation')].enabled").value(true));
        as(ALICE_ORG_ADMIN_A, get("/api/v1/features").param("orgUnitId", INST_A2.toString()))
                .andExpect(jsonPath("$[?(@.key == 'ai.evaluation')].enabled").value(false))
                .andExpect(jsonPath("$[?(@.key == 'ai.evaluation')].source").value("INSTITUTION"));
        // clearing the institution override restores inheritance
        as(ALICE_ORG_ADMIN_A, delete("/api/v1/features/ai.evaluation/override").param("orgUnitId", INST_A2.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true)).andExpect(jsonPath("$.source").value("TENANT"));
    }

    @Test
    void deploymentCeilingCannotBeOverriddenByTenant() throws Exception {
        as(ALICE_ORG_ADMIN_A, setOverride("ai.proctoring-vision", null, true))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("disabled_by_deployment"));
        as(ALICE_ORG_ADMIN_A, get("/api/v1/features"))
                .andExpect(jsonPath("$[?(@.key == 'ai.proctoring-vision')].source").value("DEPLOYMENT"));
    }

    @Test
    void integrationsAreOrganisationLevelOnly() throws Exception {
        as(ALICE_ORG_ADMIN_A, setOverride("integration.lti", INST_A1, false))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("not_institution_overridable"));
    }

    @Test
    void unknownFeatureIsNotFound() throws Exception {
        as(ALICE_ORG_ADMIN_A, setOverride("ai.make-everyone-pass", null, true)).andExpect(status().isNotFound());
    }

    @Test
    void changesAreAuditedAndEmitOutboxEventAtomically() throws Exception {
        long audits = count("SELECT count(*) FROM audit_event WHERE action = 'feature.override.changed' AND tenant_id = ?");
        long events = count("SELECT count(*) FROM outbox_event WHERE event_type = 'feature.override.changed' AND tenant_id = ?");
        as(ALICE_ORG_ADMIN_A, setOverride("module.proctoring", null, true)).andExpect(status().isOk());
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'feature.override.changed' AND tenant_id = ?")).isEqualTo(audits + 1);
        assertThat(count("SELECT count(*) FROM outbox_event WHERE event_type = 'feature.override.changed' AND tenant_id = ?")).isEqualTo(events + 1);
        // a rejected change writes neither
        as(ALICE_ORG_ADMIN_A, setOverride("ai.proctoring-vision", null, true)).andExpect(status().isConflict());
        assertThat(count("SELECT count(*) FROM outbox_event WHERE event_type = 'feature.override.changed' AND tenant_id = ?")).isEqualTo(events + 1);
    }

    @Autowired FeatureService features;

    @Test
    void cachedValuesAreInvalidatedAfterChange() throws Exception {
        TenantContext.runInTenant(TENANT_A, () -> assertThat(features.isEnabled(FeatureFlag.MODULE_PROCTORING, null)).isFalse());
        as(ALICE_ORG_ADMIN_A, setOverride("module.proctoring", null, true)).andExpect(status().isOk());
        TenantContext.runInTenant(TENANT_A, () -> assertThat(features.isEnabled(FeatureFlag.MODULE_PROCTORING, null)).isTrue());
        TenantContext.runInTenant(TENANT_B, () -> assertThat(features.isEnabled(FeatureFlag.MODULE_PROCTORING, null)).isFalse());
    }

    private static long count(String sql) {
        return ownerJdbc().sql(sql).param(TENANT_A).query(Long.class).single();
    }

    private static final UUID AURORA_UNIVERSITY = UUID.fromString("0192a000-0000-7000-8000-00000000a100");
    private static final UUID AIT_NORTH_CAMPUS = UUID.fromString("0192a000-0000-7000-8000-00000000a111");
    private static final UUID AIT_CSE = UUID.fromString("0192a000-0000-7000-8000-00000000a121");

    @Test
    void departmentsInheritFromTheirInstitutions() throws Exception {
        as(ALICE_ORG_ADMIN_A, setOverride("module.placement", INST_A1, false)).andExpect(status().isOk());
        as(ALICE_ORG_ADMIN_A, get("/api/v1/features").param("orgUnitId", AIT_CSE.toString()))
                .andExpect(jsonPath("$[?(@.key == 'module.placement')].enabled").value(false))
                .andExpect(jsonPath("$[?(@.key == 'module.placement')].source").value("INSTITUTION"));
        as(ALICE_ORG_ADMIN_A, get("/api/v1/features").param("orgUnitId", INST_A2.toString()))
                .andExpect(jsonPath("$[?(@.key == 'module.placement')].enabled").value(true));
    }

    @Test
    void aCollegeCannotEnableWhatItsUniversitySwitchedOff() throws Exception {
        as(ALICE_ORG_ADMIN_A, setOverride("module.placement", AURORA_UNIVERSITY, false)).andExpect(status().isOk());
        as(ALICE_ORG_ADMIN_A, setOverride("module.placement", INST_A1, true))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("disabled_at_parent_level"));
        as(ALICE_ORG_ADMIN_A, get("/api/v1/features/configuration").param("orgUnitId", INST_A1.toString()))
                .andExpect(jsonPath("$[?(@.key == 'module.placement')].inheritedEnabled").value(false))
                .andExpect(jsonPath("$[?(@.key == 'module.placement')].inheritedSource").value("INSTITUTION"));
    }

    @Test
    void campusesAndDepartmentsCannotHoldOverrides() throws Exception {
        as(ALICE_ORG_ADMIN_A, setOverride("module.placement", AIT_NORTH_CAMPUS, false))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("feature_scope_not_supported"));
        as(ALICE_ORG_ADMIN_A, setOverride("module.placement", AIT_CSE, false)).andExpect(status().isBadRequest());
    }
}
