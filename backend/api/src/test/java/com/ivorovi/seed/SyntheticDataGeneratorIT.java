package com.ivorovi.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Acceptance criterion 10: synthetic data can be generated (and respects tenant isolation). */
class SyntheticDataGeneratorIT extends IntegrationTest {

    @Autowired SyntheticDataGenerator generator;
    @Autowired JdbcClient jdbc;

    @Test
    void generatesIsolatedSyntheticTenants() {
        var result = generator.generate(new SyntheticDataGenerator.Request(2, 3, 25, 7L));
        assertThat(result.tenantIds()).hasSize(2);
        assertThat(result.institutions()).isEqualTo(6);
        assertThat(result.users()).isEqualTo(50);

        UUID first = result.tenantIds().get(0);
        long visibleUsers = TenantContext.callInTenant(first,
                () -> jdbc.sql("SELECT count(*) FROM user_account").query(Long.class).single());
        assertThat(visibleUsers).isEqualTo(25);
        long admins = ownerJdbc().sql("""
                SELECT count(*) FROM role_assignment ra JOIN role r ON r.id = ra.role_id
                WHERE ra.tenant_id = ? AND r.code = 'ORG_ADMIN'""").param(first).query(Long.class).single();
        assertThat(admins).isEqualTo(1);
        assertThat(ownerJdbc().sql("SELECT count(*) FROM user_account WHERE tenant_id = ? AND email NOT LIKE '%.example.test'")
                .param(first).query(Long.class).single()).as("only reserved test domains").isZero();
        assertThat(ownerJdbc().sql("SELECT count(*) FROM audit_event WHERE tenant_id = ? AND action = 'seed.synthetic_data_generated'")
                .param(first).query(Long.class).single()).isEqualTo(1);
    }
}
