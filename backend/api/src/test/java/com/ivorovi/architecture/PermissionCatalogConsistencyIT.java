package com.ivorovi.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.ivorovi.platform.security.Permission;
import com.ivorovi.support.IntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;

class PermissionCatalogConsistencyIT extends IntegrationTest {

    @Test
    void rolesReferenceOnlyCatalogPermissions() {
        List<String> used = ownerJdbc().sql("SELECT DISTINCT unnest(permissions) FROM role").query(String.class).list();
        assertThat(used).allSatisfy(p -> assertThat(Permission.fromKey(p)).as(p).isPresent());
    }

    @Test
    void allFifteenSystemRolesExist() {
        assertThat(ownerJdbc().sql("SELECT count(*) FROM role WHERE system_role").query(Long.class).single()).isEqualTo(15);
    }
}
