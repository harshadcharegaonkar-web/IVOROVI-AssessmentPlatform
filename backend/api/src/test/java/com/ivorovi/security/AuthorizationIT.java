package com.ivorovi.security;

import static com.ivorovi.support.Fixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ivorovi.support.Fixture;
import com.ivorovi.support.IntegrationTest;
import com.ivorovi.support.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** Acceptance criterion 6: backend authorization, including privilege-escalation attempts. */
class AuthorizationIT extends IntegrationTest {

    private ResultActions as(Fixture.Caller caller, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.forCaller(caller)));
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder override(String key, String body) {
        return put("/api/v1/features/" + key + "/override").contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @AfterEach
    void cleanOverrides() {
        ownerJdbc().sql("DELETE FROM feature_flag_override").update();
        // Cache entries are evicted by the API on change; direct DB cleanup needs an explicit flush.
        flushCache();
    }

    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.cache.CacheManager cacheManager;

    private void flushCache() {
        var c = cacheManager.getCache("feature-overrides");
        if (c != null) c.clear();
    }

    @Test
    void rolesWithoutPermissionsAreStillReported() throws Exception {
        // STUDENT grants no permissions in Phase 1 but must still appear as the user's role.
        as(ASHA_STUDENT_A1, get("/api/v1/me")).andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]").value("STUDENT"))
                .andExpect(jsonPath("$.grants").isEmpty());
    }

    @Test
    void studentCannotListInstitutions() throws Exception {
        as(ASHA_STUDENT_A1, get("/api/v1/org-units")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden"));
    }

    @Test
    void institutionScopedReaderSeesOnlyAssignedSubtree() throws Exception {
        // AIT college + its campus + 2 departments; not the university above it, not the sibling college
        as(ARUN_FACULTY_A1, get("/api/v1/org-units")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.items[*].id", org.hamcrest.Matchers.hasItem(INST_A1.toString())));
        as(ARUN_FACULTY_A1, get("/api/v1/org-units/" + INST_A1)).andExpect(status().isOk());
        as(ARUN_FACULTY_A1, get("/api/v1/org-units/" + INST_A2)).andExpect(status().isForbidden());
    }

    @Test
    void facultyCannotReadAuditTrailAndAttemptIsAudited() throws Exception {
        long before = deniedCount();
        as(ARUN_FACULTY_A1, get("/api/v1/audit-events")).andExpect(status().isForbidden());
        assertThat(deniedCount()).isEqualTo(before + 1);
    }

    @Test
    void facultyCannotChangeFeatures() throws Exception {
        as(ARUN_FACULTY_A1, override("ai.evaluation", "{\"enabled\":true}")).andExpect(status().isForbidden());
        as(ARUN_FACULTY_A1, override("module.placement", "{\"orgUnitId\":\"" + INST_A1 + "\",\"enabled\":false}"))
                .andExpect(status().isForbidden());
        as(ARUN_FACULTY_A1, get("/api/v1/features/configuration")).andExpect(status().isForbidden());
    }

    @Test
    void institutionAdminIsConfinedToOwnInstitution() throws Exception {
        // allowed: own institution
        as(INES_INST_ADMIN_A1, override("module.placement", "{\"orgUnitId\":\"" + INST_A1 + "\",\"enabled\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.source").value("INSTITUTION"));
        // escalation attempts: organisation level, sibling institution, other tenant's institution
        as(INES_INST_ADMIN_A1, override("module.placement", "{\"enabled\":false}")).andExpect(status().isForbidden());
        as(INES_INST_ADMIN_A1, override("module.placement", "{\"orgUnitId\":\"" + INST_A2 + "\",\"enabled\":false}"))
                .andExpect(status().isForbidden());
        as(INES_INST_ADMIN_A1, override("module.placement", "{\"orgUnitId\":\"" + INST_B1 + "\",\"enabled\":false}"))
                .andExpect(status().isForbidden());
        as(INES_INST_ADMIN_A1, delete("/api/v1/features/module.placement/override")).andExpect(status().isForbidden());
        as(INES_INST_ADMIN_A1, get("/api/v1/features/configuration")).andExpect(status().isForbidden());
        as(INES_INST_ADMIN_A1, get("/api/v1/features/configuration").param("orgUnitId", INST_A1.toString()))
                .andExpect(status().isOk());
    }

    @Test
    void orgAdminOfTenantBCannotTouchTenantAInstitution() throws Exception {
        as(BELA_ORG_ADMIN_B, override("module.placement", "{\"orgUnitId\":\"" + INST_A1 + "\",\"enabled\":false}"))
                .andExpect(status().isNotFound());
        assertThat(ownerJdbc().sql("SELECT count(*) FROM feature_flag_override").query(Long.class).single()).isZero();
    }

    @Test
    void expiredRoleAssignmentGrantsNothing() throws Exception {
        as(EXPIRED_ADMIN_A, get("/api/v1/me")).andExpect(status().isOk()).andExpect(jsonPath("$.grants").isEmpty());
        as(EXPIRED_ADMIN_A, get("/api/v1/audit-events")).andExpect(status().isForbidden());
        as(EXPIRED_ADMIN_A, override("ai.evaluation", "{\"enabled\":true}")).andExpect(status().isForbidden());
    }

    @Test
    void roleClaimsInTokenAreIgnored() throws Exception {
        // Authorization comes from Ivorovi's database, never from IdP/token role claims.
        String t = TestTokens.token(ASHA_STUDENT_A1.subject(), TENANT_A.toString(), c -> c
                .claim("roles", java.util.List.of("ORG_ADMIN", "PLATFORM_SUPER_ADMIN"))
                .claim("realm_access", java.util.Map.of("roles", java.util.List.of("ORG_ADMIN")))
                .claim("scope", "audit.read feature.manage"));
        mvc.perform(get("/api/v1/audit-events").header(HttpHeaders.AUTHORIZATION, "Bearer " + t)).andExpect(status().isForbidden());
    }

    @Test
    void orgAdminHasTenantWideAccess() throws Exception {
        as(ALICE_ORG_ADMIN_A, get("/api/v1/audit-events")).andExpect(status().isOk());
        as(ALICE_ORG_ADMIN_A, get("/api/v1/features/configuration")).andExpect(status().isOk());
        as(ALICE_ORG_ADMIN_A, get("/api/v1/org-units/" + INST_A2)).andExpect(status().isOk());
    }

    private static long deniedCount() {
        return ownerJdbc().sql("SELECT count(*) FROM audit_event WHERE tenant_id = ? AND action = 'security.access_denied'")
                .param(TENANT_A).query(Long.class).single();
    }
}
