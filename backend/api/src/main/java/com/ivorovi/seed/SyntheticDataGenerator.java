package com.ivorovi.seed;

import com.ivorovi.platform.audit.AuditLog;
import com.ivorovi.platform.config.DeploymentProperties;
import com.ivorovi.platform.ids.Ids;
import com.ivorovi.platform.tenancy.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.datafaker.Faker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Generates fully synthetic organisations, institutions, users and role assignments. Data is reproducible for a
 * given seed. Writes go through the application role, so Row Level Security applies exactly as in production:
 * tenants are created in platform context, everything else inside each tenant's own context.
 *
 * <p>Generated accounts have subjects {@code synthetic|<uuid>} and cannot sign in (no IdP account); they exist
 * for volume, isolation and performance testing.
 */
@Component
public class SyntheticDataGenerator {

    public record Request(int tenants, int institutionsPerTenant, int usersPerTenant, long seed) {
        public Request {
            if (tenants < 1 || tenants > 1000) throw new IllegalArgumentException("tenants must be 1..1000");
            if (institutionsPerTenant < 1 || institutionsPerTenant > 200) throw new IllegalArgumentException("institutionsPerTenant must be 1..200");
            if (usersPerTenant < 1 || usersPerTenant > 100_000) throw new IllegalArgumentException("usersPerTenant must be 1..100000");
        }
    }

    public record Result(List<UUID> tenantIds, int institutions, int users, int roleAssignments) {}

    private static final Logger log = LoggerFactory.getLogger(SyntheticDataGenerator.class);
    private static final UUID ORG_ADMIN = UUID.fromString("01920000-0000-7000-8000-000000000002");
    private static final UUID INSTITUTION_ADMIN = UUID.fromString("01920000-0000-7000-8000-000000000003");
    private static final UUID FACULTY = UUID.fromString("01920000-0000-7000-8000-000000000007");
    private static final UUID STUDENT = UUID.fromString("01920000-0000-7000-8000-00000000000e");

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final AuditLog audit;
    private final DeploymentProperties deployment;

    SyntheticDataGenerator(JdbcClient jdbc, TransactionTemplate tx, AuditLog audit, DeploymentProperties deployment) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.audit = audit;
        this.deployment = deployment;
    }

    public Result generate(Request request) {
        if (deployment.environment() != DeploymentProperties.Environment.DEVELOPMENT
                && deployment.environment() != DeploymentProperties.Environment.TEST) {
            throw new IllegalStateException("Synthetic data generation is only allowed in DEVELOPMENT or TEST environments");
        }
        Faker faker = new Faker(Locale.of("en", "IND"), new Random(request.seed()));
        List<UUID> tenantIds = new ArrayList<>();
        int institutions = 0, users = 0, assignments = 0;

        for (int t = 0; t < request.tenants(); t++) {
            UUID tenantId = Ids.newId();
            String code = "syn-" + Long.toHexString(request.seed()) + "-" + t;
            String orgName = faker.university().name() + " Group (synthetic)";
            TenantContext.callAsPlatform(() -> tx.execute(s -> jdbc.sql("""
                    INSERT INTO tenant (id, code, name, status, branding)
                    VALUES (?, ?, ?, 'ACTIVE', CAST(? AS jsonb))""")
                    .params(tenantId, code, orgName, "{\"displayName\":\"" + orgName.replace("\"", "") + "\"}")
                    .update()));
            tenantIds.add(tenantId);

            int[] counts = TenantContext.callInTenant(tenantId, () -> tx.execute(s -> populateTenant(tenantId, code, faker, request)));
            institutions += counts[0];
            users += counts[1];
            assignments += counts[2];
            log.info("Synthetic tenant {} ({}) generated: {} institutions, {} users", code, tenantId, counts[0], counts[1]);
        }
        return new Result(tenantIds, institutions, users, assignments);
    }

    private int[] populateTenant(UUID tenantId, String code, Faker faker, Request request) {
        List<UUID> institutionIds = new ArrayList<>();
        for (int i = 0; i < request.institutionsPerTenant(); i++) {
            UUID id = Ids.newId();
            jdbc.sql("INSERT INTO institution (id, tenant_id, code, name) VALUES (?, ?, ?, ?)")
                    .params(id, tenantId, "INST-" + (i + 1), faker.university().name() + " (synthetic)").update();
            institutionIds.add(id);
        }
        int assignments = 0;
        for (int u = 0; u < request.usersPerTenant(); u++) {
            UUID userId = Ids.newId();
            String first = faker.name().firstName();
            String last = faker.name().lastName();
            String email = (first + "." + last + "." + u + "@" + code + ".example.test").toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z0-9.@-]", "");
            jdbc.sql("""
                    INSERT INTO user_account (id, tenant_id, external_subject, email, display_name)
                    VALUES (?, ?, ?, ?, ?)""")
                    .params(userId, tenantId, "synthetic|" + userId, email, first + " " + last).update();
            UUID institution = institutionIds.get(u % institutionIds.size());
            UUID role; String scopeType; UUID scopeId;
            if (u == 0) { role = ORG_ADMIN; scopeType = "TENANT"; scopeId = tenantId; }
            else if (u <= institutionIds.size()) { role = INSTITUTION_ADMIN; scopeType = "INSTITUTION"; scopeId = institution; }
            else if (u % 10 == 0) { role = FACULTY; scopeType = "INSTITUTION"; scopeId = institution; }
            else { role = STUDENT; scopeType = "INSTITUTION"; scopeId = institution; }
            jdbc.sql("""
                    INSERT INTO role_assignment (id, tenant_id, user_account_id, role_id, scope_type, scope_id)
                    VALUES (?, ?, ?, ?, ?, ?)""")
                    .params(Ids.newId(), tenantId, userId, role, scopeType, scopeId).update();
            assignments++;
        }
        audit.recordSuccess("seed.synthetic_data_generated", "tenant", tenantId.toString(), Map.of(
                "institutions", String.valueOf(institutionIds.size()),
                "users", String.valueOf(request.usersPerTenant()),
                "seed", String.valueOf(request.seed())));
        return new int[] {institutionIds.size(), request.usersPerTenant(), assignments};
    }
}
