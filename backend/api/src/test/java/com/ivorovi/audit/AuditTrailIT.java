package com.ivorovi.audit;

import static com.ivorovi.support.Fixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ivorovi.platform.audit.AuditLog;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.support.IntegrationTest;
import com.ivorovi.support.TestTokens;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;

class AuditTrailIT extends IntegrationTest {

    @Autowired AuditLog audit;
    @Autowired TransactionTemplate tx;

    private void write(java.util.UUID tenant, String action) {
        TenantContext.runInTenant(tenant, () -> tx.executeWithoutResult(s ->
                audit.recordSuccess(action, "test", "1", Map.of("k", "v"))));
    }

    @Test
    void auditTrailIsTenantScoped() throws Exception {
        write(TENANT_B, "test.only_in_b");
        mvc.perform(get("/api/v1/audit-events").param("action", "test.only_in_b")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.forCaller(ALICE_ORG_ADMIN_A)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/v1/audit-events").param("action", "test.only_in_b")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.forCaller(BELA_ORG_ADMIN_B)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].details.k").value("v"))
                .andExpect(jsonPath("$.items[0].actorSubject").value("system"))
                .andExpect(jsonPath("$.items[0].actorName").doesNotExist());
    }

    @Test
    void sensitiveApiChangeRecordsActorAndCorrelation() throws Exception {
        mvc.perform(put("/api/v1/features/module.academics/override").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}")
                        .header("User-Agent", "audit-test")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.forCaller(BELA_ORG_ADMIN_B)))
                .andExpect(status().isOk());
        var row = ownerJdbc().sql("""
                SELECT actor_subject, resource_id, user_agent, trace_id, details->>'new' AS new_value
                FROM audit_event WHERE tenant_id = ? AND action = 'feature.override.changed' ORDER BY seq DESC LIMIT 1""")
                .param(TENANT_B).query().singleRow();
        assertThat(row.get("actor_subject")).isEqualTo(BELA_ORG_ADMIN_B.subject());
        assertThat(row.get("resource_id")).isEqualTo("module.academics");
        assertThat(row.get("user_agent")).isEqualTo("audit-test");
        assertThat(row.get("trace_id")).isNotNull();
        assertThat(row.get("new_value")).isEqualTo("false");
        mvc.perform(get("/api/v1/audit-events").param("action", "feature.override.changed").param("size", "1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.forCaller(BELA_ORG_ADMIN_B)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].actorName").value("Bela Admin"));
        ownerJdbc().sql("DELETE FROM feature_flag_override").update();
    }

    @Test
    void hashChainVerifiesAndDetectsTampering() throws Exception {
        write(TENANT_SUSPENDED, "test.chain.1");
        write(TENANT_SUSPENDED, "test.chain.2");
        write(TENANT_SUSPENDED, "test.chain.3");
        var verification = TenantContext.callInTenant(TENANT_SUSPENDED,
                () -> tx.execute(s -> ownerVerify()));
        assertThat(verification.valid()).isTrue();
        assertThat(verification.eventsChecked()).isGreaterThanOrEqualTo(3);

        // Tamper as the schema owner (an insider with DB access), then verify again.
        ownerJdbc().sql("UPDATE audit_event SET details = '{\"k\":\"forged\"}' WHERE tenant_id = ? AND action = 'test.chain.2'")
                .param(TENANT_SUSPENDED).update();
        var after = TenantContext.callInTenant(TENANT_SUSPENDED, () -> tx.execute(s -> ownerVerify()));
        assertThat(after.valid()).isFalse();
        assertThat(after.firstInvalidSeq()).isNotNull();
    }

    @Autowired com.ivorovi.platform.audit.AuditTrail trail;

    private com.ivorovi.platform.audit.AuditTrail.Verification ownerVerify() {
        return trail.verify();
    }

    @Test
    void verificationEndpointReportsValidChain() throws Exception {
        mvc.perform(get("/api/v1/audit-events/verification")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.forCaller(ALICE_ORG_ADMIN_A)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(true));
    }

    @Test
    void applicationRoleCannotModifyOrDeleteAuditEvents() {
        write(TENANT_A, "test.immutable");
        try (var c = java.sql.DriverManager.getConnection(com.ivorovi.support.Containers.jdbcUrl(), "ivorovi_app",
                com.ivorovi.support.Containers.APP_PASSWORD); var st = c.createStatement()) {
            st.execute("SELECT set_config('app.tenant_id', '" + TENANT_A + "', false)");
            assertThatThrownBy(() -> st.executeUpdate("UPDATE audit_event SET action = 'x'")).hasMessageContaining("permission denied");
            assertThatThrownBy(() -> st.executeUpdate("DELETE FROM audit_event")).hasMessageContaining("permission denied");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
