package com.ivorovi.security;

import static com.ivorovi.support.Fixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ivorovi.support.IntegrationTest;
import com.ivorovi.support.TestTokens;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/** Acceptance criteria 3, 4, 5: protected API, tenant context establishment/validation, cross-tenant rejection. */
class TenantIsolationIT extends IntegrationTest {

    private static String bearer(String token) { return "Bearer " + token; }

    @Nested
    @DisplayName("Authentication and tenant context establishment")
    class Establishment {

        @Test
        void rejectsRequestWithoutToken() throws Exception {
            mvc.perform(get("/api/v1/me"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate", "Bearer"))
                    .andExpect(jsonPath("$.code").value("unauthenticated"));
        }

        @Test
        void establishesTenantContextFromVerifiedToken() throws Exception {
            mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(TestTokens.forCaller(ALICE_ORG_ADMIN_A))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.tenant.id").value(TENANT_A.toString()))
                    .andExpect(jsonPath("$.tenant.code").value("aurora"))
                    .andExpect(jsonPath("$.email").value("alice.admin@aurora.test"))
                    .andExpect(jsonPath("$.roles[0]").value("ORG_ADMIN"));
        }

        @Test
        void rejectsTokenSignedByUntrustedKey() throws Exception {
            mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(TestTokens.signedByUntrustedKey(ALICE_ORG_ADMIN_A))))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void rejectsWrongIssuer() throws Exception {
            String t = TestTokens.token(ALICE_ORG_ADMIN_A.subject(), TENANT_A.toString(), c -> c.issuer("https://evil.test"));
            mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(t))).andExpect(status().isUnauthorized());
        }

        @Test
        void rejectsWrongAudience() throws Exception {
            String t = TestTokens.token(ALICE_ORG_ADMIN_A.subject(), TENANT_A.toString(), c -> c.audience(List.of("ivorovi-web")));
            mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(t))).andExpect(status().isUnauthorized());
        }

        @Test
        void rejectsExpiredToken() throws Exception {
            String t = TestTokens.token(ALICE_ORG_ADMIN_A.subject(), TENANT_A.toString(),
                    c -> c.issuedAt(Instant.now().minusSeconds(3600)).expiresAt(Instant.now().minusSeconds(1800)));
            mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(t))).andExpect(status().isUnauthorized());
        }

        @Test
        void rejectsMissingTenantClaim() throws Exception {
            String t = TestTokens.token(ALICE_ORG_ADMIN_A.subject(), null, c -> {});
            mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(t)))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("tenant_access_denied"));
        }

        @Test
        void rejectsMalformedTenantClaim() throws Exception {
            String t = TestTokens.token(ALICE_ORG_ADMIN_A.subject(), "aurora' OR 1=1 --", c -> {});
            mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(t))).andExpect(status().isForbidden());
        }

        @Test
        void rejectsUnknownTenant() throws Exception {
            String t = TestTokens.token(ALICE_ORG_ADMIN_A.subject(), UUID.randomUUID().toString(), c -> {});
            mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(t))).andExpect(status().isForbidden());
        }

        @Test
        void rejectsSuspendedTenant() throws Exception {
            mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(TestTokens.forCaller(CIRRUS_ADMIN_SUSPENDED))))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("tenant_suspended")));
        }

        @Test
        void rejectsIdpUserWithoutIvoroviAccount() throws Exception {
            mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(TestTokens.forCaller(GHOST_A))))
                    .andExpect(status().isForbidden());
        }

        @Test
        void rejectsSuspendedAccount() throws Exception {
            mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(TestTokens.forCaller(SUSPENDED_USER_A))))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("Cross-tenant access")
    class CrossTenant {

        @Test
        void userOfTenantBCannotClaimTenantA() throws Exception {
            // Bela's real subject, but the token claims tenant A: she has no account there.
            String t = TestTokens.token(BELA_ORG_ADMIN_B.subject(), TENANT_A.toString(), c -> {});
            mvc.perform(get("/api/v1/org-units").header(HttpHeaders.AUTHORIZATION, bearer(t)))
                    .andExpect(status().isForbidden());
        }

        @Test
        void listShowsOnlyOwnTenantInstitutions() throws Exception {
            // tenant A fixture: university, 2 colleges, campus, 3 departments
            mvc.perform(get("/api/v1/org-units").header(HttpHeaders.AUTHORIZATION, bearer(TestTokens.forCaller(ALICE_ORG_ADMIN_A))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(7))
                    .andExpect(jsonPath("$.items[*].id", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(INST_B1.toString()))));
            mvc.perform(get("/api/v1/org-units").header(HttpHeaders.AUTHORIZATION, bearer(TestTokens.forCaller(BELA_ORG_ADMIN_B))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(2))
                    .andExpect(jsonPath("$.items[*].id", org.hamcrest.Matchers.hasItem(INST_B1.toString())));
        }

        @Test
        void otherTenantsInstitutionIsNotFound() throws Exception {
            mvc.perform(get("/api/v1/org-units/" + INST_B1).header(HttpHeaders.AUTHORIZATION, bearer(TestTokens.forCaller(ALICE_ORG_ADMIN_A))))
                    .andExpect(status().isNotFound());
            mvc.perform(get("/api/v1/org-units/" + INST_A1).header(HttpHeaders.AUTHORIZATION, bearer(TestTokens.forCaller(BELA_ORG_ADMIN_B))))
                    .andExpect(status().isNotFound());
        }

        @Test
        void tenantHeadersAndParametersAreIgnored() throws Exception {
            mvc.perform(get("/api/v1/org-units").param("tenantId", TENANT_B.toString())
                            .header("X-Tenant-Id", TENANT_B.toString())
                            .header(HttpHeaders.AUTHORIZATION, bearer(TestTokens.forCaller(ALICE_ORG_ADMIN_A))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(7));
        }

        @Test
        void otherTenantsInstitutionCannotBeUsedForFeatureConfiguration() throws Exception {
            mvc.perform(get("/api/v1/features").param("orgUnitId", INST_B1.toString())
                            .header(HttpHeaders.AUTHORIZATION, bearer(TestTokens.forCaller(ALICE_ORG_ADMIN_A))))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("Database-level enforcement (Row Level Security)")
    class DatabaseLevel {

        @Test
        void appRoleWithoutTenantContextSeesNoTenantRows() {
            var jdbc = rawAppJdbc();
            assertThat(jdbc.sql("SELECT count(*) FROM org_unit").query(Long.class).single()).isZero();
            assertThat(jdbc.sql("SELECT count(*) FROM org_unit_history").query(Long.class).single()).isZero();
            assertThat(jdbc.sql("SELECT count(*) FROM tenant_settings").query(Long.class).single()).isZero();
            assertThat(jdbc.sql("SELECT count(*) FROM user_account").query(Long.class).single()).isZero();
            assertThat(jdbc.sql("SELECT count(*) FROM audit_event").query(Long.class).single()).isZero();
            assertThat(jdbc.sql("SELECT count(*) FROM tenant").query(Long.class).single()).isZero();
        }

        @Test
        void appRoleCannotWriteIntoAnotherTenant() throws Exception {
            try (var c = java.sql.DriverManager.getConnection(com.ivorovi.support.Containers.jdbcUrl(), "ivorovi_app",
                    com.ivorovi.support.Containers.APP_PASSWORD); var st = c.createStatement()) {
                st.execute("SELECT set_config('app.tenant_id', '" + TENANT_A + "', false)");
                var rs = st.executeQuery("SELECT count(*) FROM org_unit WHERE tenant_id = '" + TENANT_B + "'");
                rs.next();
                assertThat(rs.getLong(1)).as("tenant B rows visible from tenant A context").isZero();
                assertThatThrownBy(() -> st.executeUpdate("INSERT INTO org_unit (id, tenant_id, unit_type, code, name, path) VALUES ("
                        + "gen_random_uuid(), '" + TENANT_B + "', 'COLLEGE', 'X', 'Injected', 'x')"))
                        .hasMessageContaining("row-level security");
                assertThat(st.executeUpdate("UPDATE org_unit SET name = 'hijacked' WHERE tenant_id = '" + TENANT_B + "'"))
                        .as("rows of tenant B updated from tenant A context").isZero();
            }
        }

        @Test
        void appRoleCannotBypassRls() {
            Boolean bypass = ownerJdbc().sql("SELECT rolbypassrls OR rolsuper FROM pg_roles WHERE rolname = 'ivorovi_app'")
                    .query(Boolean.class).single();
            assertThat(bypass).isFalse();
        }

        @Test
        void everyTenantOwnedTableHasRowLevelSecurity() {
            List<String> withoutRls = ownerJdbc().sql("""
                    SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                    JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'tenant_id'
                    WHERE n.nspname = 'ivorovi' AND c.relkind = 'r' AND NOT c.relrowsecurity""")
                    .query(String.class).list();
            assertThat(withoutRls).as("tables with tenant_id but RLS disabled").isEmpty();
        }
    }
}
