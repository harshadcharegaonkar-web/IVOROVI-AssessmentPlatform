package com.ivorovi.platform.kernel;

import static com.ivorovi.support.Fixture.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ivorovi.platform.security.PrincipalCache;
import com.ivorovi.platform.security.PrincipalResolver;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.platform.tenancy.TenantDirectory;
import com.ivorovi.support.Fixture;
import com.ivorovi.support.IntegrationTest;
import com.ivorovi.support.TestTokens;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.ResultActions;

/** Increment 1.0 AC1/AC2: cached steady state skips the DB; keys are tenant-derived; eviction is immediate. */
class PrincipalCacheIT extends IntegrationTest {

    @MockitoSpyBean PrincipalResolver resolver;
    @Autowired PrincipalCache principalCache;
    @Autowired TenantDirectory tenants;
    @Autowired StringRedisTemplate redis;
    @Autowired MeterRegistry meters;

    @BeforeEach
    void flush() {
        redis.delete(redis.keys("ivorovi:principal*"));
        redis.delete(redis.keys("ivorovi:tenant:*"));
        clearInvocations(resolver);
    }

    @AfterEach
    void restore() {
        ownerJdbc().sql("UPDATE tenant SET status = 'ACTIVE' WHERE id = ?").param(TENANT_B).update();
        ownerJdbc().sql("UPDATE user_account SET status = 'ACTIVE' WHERE tenant_id = ? AND email = 'arun.faculty@aurora.test'")
                .param(TENANT_A).update();
        redis.delete(redis.keys("ivorovi:principal*"));
        redis.delete(redis.keys("ivorovi:tenant:*"));
    }

    private ResultActions me(Fixture.Caller c) throws Exception {
        return mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.forCaller(c)));
    }

    private double tenantMisses() {
        var c = meters.find("ivorovi.cache.requests").tags("cache", "tenant", "result", "miss").counter();
        return c == null ? 0 : c.count();
    }

    @Test
    void steadyStateRequestsDoNotHitTheDatabaseForIdentity() throws Exception {
        double missesBefore = tenantMisses();
        for (int i = 0; i < 5; i++) me(ALICE_ORG_ADMIN_A).andExpect(status().isOk());
        verify(resolver, times(1)).resolve(any(), anyString());
        org.assertj.core.api.Assertions.assertThat(tenantMisses() - missesBefore).isEqualTo(1.0);
    }

    @Test
    void cachedPrincipalOfOneTenantIsNeverUsedForAnother() throws Exception {
        me(BELA_ORG_ADMIN_B).andExpect(status().isOk()); // cached under tenant B
        String forged = TestTokens.token(BELA_ORG_ADMIN_B.subject(), TENANT_A.toString(), c -> {});
        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + forged)).andExpect(status().isForbidden());
    }

    @Test
    void subjectEvictionTakesEffectOnTheNextRequest() throws Exception {
        me(ARUN_FACULTY_A1).andExpect(status().isOk());
        ownerJdbc().sql("UPDATE user_account SET status = 'SUSPENDED' WHERE tenant_id = ? AND email = 'arun.faculty@aurora.test'")
                .param(TENANT_A).update();
        me(ARUN_FACULTY_A1).andExpect(status().isOk()); // still cached (bounded by 60 s TTL)
        TenantContext.runInTenant(TENANT_A, () -> principalCache.evictSubject(TENANT_A, ARUN_FACULTY_A1.subject()));
        me(ARUN_FACULTY_A1).andExpect(status().isForbidden());
    }

    @Test
    void tenantWideEvictionInvalidatesAllPrincipalsOfThatTenantOnly() throws Exception {
        me(ALICE_ORG_ADMIN_A).andExpect(status().isOk());
        me(BELA_ORG_ADMIN_B).andExpect(status().isOk());
        clearInvocations(resolver);
        TenantContext.runInTenant(TENANT_A, () -> principalCache.evictTenant(TENANT_A));
        me(ALICE_ORG_ADMIN_A).andExpect(status().isOk());
        me(BELA_ORG_ADMIN_B).andExpect(status().isOk());
        verify(resolver, times(1)).resolve(any(), anyString()); // only Alice re-resolved
    }

    @Test
    void tenantSuspensionTakesEffectAfterEviction() throws Exception {
        me(BELA_ORG_ADMIN_B).andExpect(status().isOk()).andExpect(jsonPath("$.tenant.code").value("borealis"));
        ownerJdbc().sql("UPDATE tenant SET status = 'SUSPENDED' WHERE id = ?").param(TENANT_B).update();
        tenants.evict(TENANT_B);
        me(BELA_ORG_ADMIN_B).andExpect(status().isForbidden());
    }
}
