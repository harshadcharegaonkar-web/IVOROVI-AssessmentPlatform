package com.ivorovi.identity;

import static com.ivorovi.support.Fixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ivorovi.platform.security.Permission;
import com.ivorovi.support.Fixture;
import com.ivorovi.support.IntegrationTest;
import com.ivorovi.support.TestTokens;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Increment 1.3: custom roles and permission management. Fresh tenant per test with university U →
 * colleges A and B, a campus and a department under A, and an organization administrator.
 */
class RoleManagementIT extends IntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID ORG_ADMIN = UUID.fromString("01920000-0000-7000-8000-000000000002");
    private static final UUID INSTITUTION_ADMIN = UUID.fromString("01920000-0000-7000-8000-000000000003");

    private UUID tenant;
    private Fixture.Caller admin;
    private UUID adminId;
    private UUID university;
    private UUID collegeA;
    private UUID collegeB;
    private UUID campusA;
    private UUID deptA;

    @BeforeEach
    void freshTenant() {
        tenant = UUID.randomUUID();
        ownerJdbc().sql("INSERT INTO tenant (id, code, name, status) VALUES (?, ?, 'Roles test org', 'ACTIVE')")
                .params(tenant, "roles-" + tenant.toString().substring(0, 8)).update();
        university = unit("UNIVERSITY", null, "U");
        collegeA = unit("COLLEGE", university, "A");
        collegeB = unit("COLLEGE", university, "B");
        campusA = unit("CAMPUS", collegeA, "K");
        deptA = unit("DEPARTMENT", campusA, "D");
        adminId = UUID.randomUUID();
        admin = account(adminId, ORG_ADMIN, null);
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private UUID unit(String type, UUID parent, String code) {
        UUID id = UUID.randomUUID();
        String label = id.toString().replace("-", "");
        String path = parent == null ? label
                : ownerJdbc().sql("SELECT path::text FROM org_unit WHERE id = ?").param(parent).query(String.class).single() + "." + label;
        ownerJdbc().sql("INSERT INTO org_unit (id, tenant_id, parent_id, unit_type, code, name, path) VALUES (?, ?, ?, ?, ?, ?, CAST(? AS ltree))")
                .params(id, tenant, parent, type, code, "Unit " + code, path).update();
        return id;
    }

    private Fixture.Caller account(UUID id, UUID role, UUID unit) {
        String subject = "sub-" + id;
        ownerJdbc().sql("INSERT INTO user_account (id, tenant_id, external_subject, email, display_name, given_name) VALUES (?, ?, ?, ?, ?, 'User')")
                .params(id, tenant, subject, id + "@roles.test", "User " + id.toString().substring(0, 6)).update();
        ownerJdbc().sql("INSERT INTO org_assignment (id, tenant_id, user_account_id, org_unit_id, is_primary) VALUES (?, ?, ?, ?, true)")
                .params(UUID.randomUUID(), tenant, id, unit).update();
        if (role != null) grant(id, role, unit);
        return new Fixture.Caller(subject, tenant);
    }

    private void grant(UUID user, UUID role, UUID unit) {
        ownerJdbc().sql("INSERT INTO role_assignment (id, tenant_id, user_account_id, role_id, scope_type, scope_id) VALUES (?, ?, ?, ?, ?, ?)")
                .params(UUID.randomUUID(), tenant, user, role, unit == null ? "TENANT" : "ORG_UNIT", unit == null ? tenant : unit).update();
    }

    private ResultActions as(Fixture.Caller c, MockHttpServletRequestBuilder r) throws Exception {
        return mvc.perform(r.header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.forCaller(c)));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder r, String body) {
        return r.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String perms(String... p) {
        return Arrays.stream(p).map(x -> "\"" + x + "\"").reduce((a, b) -> a + "," + b).map(x -> "[" + x + "]").orElse("[]");
    }

    private ResultActions createRole(Fixture.Caller c, String name, UUID scope, String... permissions) throws Exception {
        String body = "{\"name\":\"" + name + "\",\"description\":\"Test role\",\"permissions\":" + perms(permissions)
                + (scope == null ? "" : ",\"scopeOrgUnitId\":\"" + scope + "\"") + "}";
        return as(c, json(post("/api/v1/roles"), body).header("Idempotency-Key", "k-" + UUID.randomUUID()));
    }

    private UUID created(ResultActions r) throws Exception {
        JsonNode n = JSON.readTree(r.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return UUID.fromString(n.path("role").path("id").asString());
    }

    private long version(UUID role) {
        return ownerJdbc().sql("SELECT version FROM role WHERE id = ?").param(role).query(Long.class).single();
    }

    private ResultActions assign(Fixture.Caller c, UUID user, UUID role, UUID unit) throws Exception {
        return as(c, json(post("/api/v1/users/" + user + "/role-assignments"),
                "{\"roleId\":\"" + role + "\"" + (unit == null ? "" : ",\"orgUnitId\":\"" + unit + "\"") + "}"));
    }

    private ResultActions setStatus(Fixture.Caller c, UUID role, String status, String reason) throws Exception {
        return as(c, json(post("/api/v1/roles/" + role + "/status"),
                "{\"status\":\"" + status + "\",\"version\":" + version(role) + (reason == null ? "" : ",\"reason\":\"" + reason + "\"") + "}"));
    }

    private ResultActions edit(Fixture.Caller c, UUID role, String... permissions) throws Exception {
        return as(c, json(patch("/api/v1/roles/" + role), "{\"version\":" + version(role) + ",\"permissions\":" + perms(permissions) + "}"));
    }

    private UUID roleIdByCode(String code) {
        return ownerJdbc().sql("SELECT id FROM role WHERE code = ? AND tenant_id IS NULL").param(code).query(UUID.class).single();
    }

    private int[] concurrently(Callable<Integer> a, Callable<Integer> b) throws Exception {
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var fa = pool.submit(() -> { start.await(); return a.call(); });
            var fb = pool.submit(() -> { start.await(); return b.call(); });
            start.countDown();
            return new int[] {fa.get(30, TimeUnit.SECONDS), fb.get(30, TimeUnit.SECONDS)};
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------------------------------------ tests

    @Nested
    class Catalogue {

        @Test
        void permissionsAreGroupedByDomainWithStableCodesAndMetadata() throws Exception {
            as(admin, get("/api/v1/permissions")).andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].label").value("Organization management"))
                    .andExpect(jsonPath("$[*].domain", Matchers.hasItems("USERS", "STAFF", "ROLES", "ADMISSIONS", "AI", "AUDIT")))
                    .andExpect(jsonPath("$[*].domain", Matchers.not(Matchers.hasItem("PLATFORM"))))
                    .andExpect(jsonPath("$[?(@.domain == 'USERS')].permissions[*].code", Matchers.contains("user.read", "user.manage")))
                    .andExpect(jsonPath("$[?(@.domain == 'ADMISSIONS')].permissions[0].availability").value("PLANNED"))
                    .andExpect(jsonPath("$[?(@.domain == 'AUDIT')].permissions[0].scope").value("ORGANIZATION"))
                    .andExpect(jsonPath("$[?(@.domain == 'USERS')].permissions[1].level").value("MANAGE"));
            for (Permission p : Permission.values()) {
                assertThat(p.displayName()).as(p.key()).isNotBlank();
                assertThat(p.key()).matches("^[a-z_]+(\\.[a-z_]+)+$");
            }
            // system roles only use available permissions (the platform role uses its platform permission)
            List<String> used = ownerJdbc().sql("SELECT DISTINCT unnest(permissions) FROM role WHERE system_role AND code <> 'PLATFORM_SUPER_ADMIN'")
                    .query(String.class).list();
            assertThat(used).allSatisfy(k -> assertThat(Permission.fromKey(k).orElseThrow().grantable()).as(k).isTrue());
        }

        @Test
        void unknownPlannedPlatformAndEmptyPermissionsAreRefused() throws Exception {
            createRole(admin, "Bad 1", null, "student.delete_everything").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("unknown_permission"));
            createRole(admin, "Bad 2", null, "admission.read").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("permission_not_grantable"));
            createRole(admin, "Bad 3", null, "platform.tenant.manage").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("permission_not_grantable"));
            createRole(admin, "Bad 4", null).andExpect(status().isBadRequest());
            createRole(admin, "Bad 5", collegeA, "audit.read").andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("organization_only_permission"));
            for (String reserved : List.of("PLATFORM_SUPER_ADMIN", "STUDENT", "ORG_ADMIN", "PLATFORM_OPERATOR")) {
                as(admin, json(post("/api/v1/roles"), "{\"code\":\"" + reserved + "\",\"name\":\"x " + reserved + "\",\"permissions\":[\"user.read\"]}")
                        .header("Idempotency-Key", "k-" + UUID.randomUUID())).andExpect(status().isUnprocessableContent())
                        .andExpect(jsonPath("$.code").value("reserved_role_code"));
            }
            assertThat(ownerJdbc().sql("SELECT count(*) FROM audit_event WHERE tenant_id = ? AND action = 'role.change_denied'").param(tenant)
                    .query(Long.class).single()).isGreaterThanOrEqualTo(3);
        }
    }

    @Nested
    class Lifecycle {

        @Test
        void createEditCloneListAndDetail() throws Exception {
            UUID role = created(createRole(admin, "Exam Cell Assistant", null, "org_unit.read", "user.read", "staff.read"));
            as(admin, get("/api/v1/roles/" + role)).andExpect(jsonPath("$.role.code").value("CUSTOM_EXAM_CELL_ASSISTANT"))
                    .andExpect(jsonPath("$.role.system").value(false)).andExpect(jsonPath("$.role.scopeName").value("Whole organization"))
                    .andExpect(jsonPath("$.permissions", Matchers.contains("org_unit.read", "user.read", "staff.read")))
                    .andExpect(jsonPath("$.canEdit").value(true));
            createRole(admin, "Exam Cell Assistant", null, "user.read").andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("role_code_in_use"));
            edit(admin, role, "org_unit.read", "user.read", "role.read").andExpect(status().isOk())
                    .andExpect(jsonPath("$.permissions", Matchers.contains("org_unit.read", "user.read", "role.read")));
            as(admin, json(patch("/api/v1/roles/" + role), "{\"version\":0,\"name\":\"Lost update\"}")).andExpect(status().isConflict());
            String audit = ownerJdbc().sql("SELECT details::text FROM audit_event WHERE tenant_id = ? AND action = 'role.updated'").param(tenant)
                    .query(String.class).single();
            assertThat(audit).contains("\"permissionsAdded\": \"role.read\"").contains("\"permissionsRemoved\": \"staff.read\"");

            // clone a system role as a starting point
            String body = "{\"name\":\"College Admin (no audit)\",\"clonedFromRoleId\":\"" + INSTITUTION_ADMIN + "\",\"permissions\":"
                    + perms("org_unit.read", "org_unit.manage", "user.read", "user.manage") + "}";
            UUID clone = created(as(admin, json(post("/api/v1/roles"), body).header("Idempotency-Key", "k-" + UUID.randomUUID())));
            as(admin, get("/api/v1/roles/" + clone)).andExpect(jsonPath("$.clonedFromName").value("Institution Administrator"));
            as(admin, get("/api/v1/roles").param("type", "custom")).andExpect(jsonPath("$.totalElements").value(2));
            as(admin, get("/api/v1/roles").param("q", "exam")).andExpect(jsonPath("$.items[0].name").value("Exam Cell Assistant"));
            as(admin, get("/api/v1/roles").param("type", "system")).andExpect(jsonPath("$.items[*].code", Matchers.not(Matchers.hasItem("STUDENT"))));
            assertThat(ownerJdbc().sql("SELECT array_agg(action ORDER BY seq)::text FROM audit_event WHERE tenant_id = ? AND resource_type = 'role'")
                    .param(tenant).query(String.class).single()).contains("role.created", "role.updated", "role.cloned");
        }

        @Test
        void systemRolesAreProtected() throws Exception {
            UUID orgAdmin = roleIdByCode("ORG_ADMIN");
            as(admin, json(patch("/api/v1/roles/" + orgAdmin), "{\"version\":0,\"permissions\":[\"user.read\"]}"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("system_role_protected"));
            as(admin, json(post("/api/v1/roles/" + orgAdmin + "/status"), "{\"status\":\"INACTIVE\",\"version\":0,\"reason\":\"x\"}"))
                    .andExpect(status().isForbidden());
            as(admin, delete("/api/v1/roles/" + orgAdmin).param("version", "0")).andExpect(status().isForbidden());
            as(admin, get("/api/v1/roles/" + orgAdmin)).andExpect(jsonPath("$.canEdit").value(false));
            assertThat(ownerJdbc().sql("SELECT count(*) FROM audit_event WHERE tenant_id = ? AND action = 'role.change_denied' AND details::text LIKE '%system_role%'")
                    .param(tenant).query(Long.class).single()).isEqualTo(3);
            // the last organization administrator safeguard therefore cannot be bypassed by deactivating ORG_ADMIN
            assertThat(ownerJdbc().sql("SELECT status FROM role WHERE id = ?").param(orgAdmin).query(String.class).single()).isEqualTo("ACTIVE");
        }

        @Test
        void deactivationSuspendsAssignmentsAndArchivingNeedsNoActiveAssignments() throws Exception {
            UUID role = created(createRole(admin, "Viewer", null, "user.read", "org_unit.read"));
            UUID u1 = UUID.randomUUID();
            Fixture.Caller c1 = account(u1, null, collegeA);
            UUID u2 = UUID.randomUUID();
            Fixture.Caller c2 = account(u2, null, collegeB);
            assign(admin, u1, role, collegeA).andExpect(status().isCreated());
            assign(admin, u2, role, null).andExpect(status().isCreated());
            as(c1, get("/api/v1/users")).andExpect(status().isOk());          // principals cached
            as(c2, get("/api/v1/users")).andExpect(status().isOk());

            setStatus(admin, role, "INACTIVE", null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("reason_required"));
            setStatus(admin, role, "INACTIVE", "Under review").andExpect(status().isOk()).andExpect(jsonPath("$.role.status").value("INACTIVE"));
            as(c1, get("/api/v1/users")).andExpect(status().isForbidden());   // no stale permissions
            as(c2, get("/api/v1/users")).andExpect(status().isForbidden());
            as(admin, get("/api/v1/users/" + u1 + "/access")).andExpect(jsonPath("$.permissions").isEmpty())
                    .andExpect(jsonPath("$.roles[0].roleStatus").value("INACTIVE"));
            UUID u3 = UUID.randomUUID();
            account(u3, null, collegeA);
            assign(admin, u3, role, collegeA).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("role_inactive"));
            as(admin, get("/api/v1/roles/assignable")).andExpect(jsonPath("$[*].id", Matchers.not(Matchers.hasItem(role.toString()))));
            assertThat(ownerJdbc().sql("SELECT details::text FROM audit_event WHERE tenant_id = ? AND action = 'role.deactivated'")
                    .param(tenant).query(String.class).single()).contains("\"affectedAssignments\": \"2\"").contains("Under review");

            setStatus(admin, role, "ACTIVE", null).andExpect(status().isOk());
            as(c1, get("/api/v1/users")).andExpect(status().isOk());          // assignments restored

            as(admin, delete("/api/v1/roles/" + role).param("version", String.valueOf(version(role))))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("role_in_use"));
            for (String ra : ownerJdbc().sql("SELECT id::text FROM role_assignment WHERE role_id = ?").param(role).query(String.class).list()) {
                UUID holder = ownerJdbc().sql("SELECT user_account_id FROM role_assignment WHERE id = ?::uuid").param(ra).query(UUID.class).single();
                as(admin, json(post("/api/v1/users/" + holder + "/role-assignments/" + ra + "/revoke"), "{\"reason\":\"role retired\"}"))
                        .andExpect(status().isNoContent());
            }
            as(admin, delete("/api/v1/roles/" + role).param("version", String.valueOf(version(role)))).andExpect(status().isNoContent());
            as(admin, get("/api/v1/roles/" + role)).andExpect(status().isNotFound());
            as(admin, get("/api/v1/users/" + u1 + "/role-assignments")).andExpect(jsonPath("$[0].roleName").value("Viewer"));   // history kept
        }

        @Test
        void editingARoleChangesHoldersAccessOnTheirNextRequest() throws Exception {
            UUID role = created(createRole(admin, "Reader", null, "user.read"));
            UUID u = UUID.randomUUID();
            Fixture.Caller c = account(u, null, collegeA);
            assign(admin, u, role, collegeA).andExpect(status().isCreated());
            as(c, get("/api/v1/users")).andExpect(status().isOk());
            as(c, get("/api/v1/staff")).andExpect(status().isForbidden());
            edit(admin, role, "staff.read").andExpect(status().isOk());
            as(c, get("/api/v1/users")).andExpect(status().isForbidden());    // removed permission gone at once
            as(c, get("/api/v1/staff")).andExpect(status().isOk());           // added permission available
            String ra = ownerJdbc().sql("SELECT id::text FROM role_assignment WHERE user_account_id = ?").param(u).query(String.class).single();
            as(admin, json(post("/api/v1/users/" + u + "/role-assignments/" + ra + "/revoke"), "{\"reason\":\"x\"}")).andExpect(status().isNoContent());
            as(c, get("/api/v1/staff")).andExpect(status().isForbidden());
        }
    }

    @Nested
    class CeilingAndScope {

        @Test
        void aCollegeAdministratorCannotCreateOrganizationWideOrHigherPrivilegeRoles() throws Exception {
            UUID collegeAdminId = UUID.randomUUID();
            Fixture.Caller collegeAdmin = account(collegeAdminId, INSTITUTION_ADMIN, collegeA);
            UUID local = created(createRole(collegeAdmin, "Lab Assistant", collegeA, "org_unit.read", "staff.read"));
            as(collegeAdmin, get("/api/v1/roles/" + local)).andExpect(jsonPath("$.role.scopeName").value("Unit A"));
            createRole(collegeAdmin, "Org Viewer", null, "user.read").andExpect(status().isForbidden());          // organization-wide role
            createRole(collegeAdmin, "Elsewhere", collegeB, "user.read").andExpect(status().isForbidden());      // another college
            createRole(collegeAdmin, "Settings", collegeA, "tenant.settings.manage").andExpect(status().isUnprocessableContent());
            // clone of ORG_ADMIN into the college: organization-only permissions are refused
            String body = "{\"name\":\"Mini org admin\",\"scopeOrgUnitId\":\"" + collegeA + "\",\"clonedFromRoleId\":\"" + ORG_ADMIN
                    + "\",\"permissions\":" + perms("user.manage", "tenant.settings.manage") + "}";
            as(collegeAdmin, json(post("/api/v1/roles"), body).header("Idempotency-Key", "k-" + UUID.randomUUID())).andExpect(status().isUnprocessableContent());

            // a narrower delegate (role.manage + user.read only) cannot put staff.manage into a role
            UUID delegateRole = created(createRole(admin, "Role editor", null, "role.manage", "role.read", "user.read"));
            UUID delegateId = UUID.randomUUID();
            Fixture.Caller delegate = account(delegateId, null, collegeA);
            assign(admin, delegateId, delegateRole, collegeA).andExpect(status().isCreated());
            createRole(delegate, "Too much", collegeA, "user.read", "staff.manage").andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("privilege_escalation"));
            UUID mine = created(createRole(delegate, "Just readers", collegeA, "user.read"));
            // self-escalation through editing a role is impossible: only permissions already held may be added
            edit(delegate, mine, "user.read", "user.manage").andExpect(status().isForbidden());
            edit(delegate, delegateRole, "role.manage", "role.read", "user.read", "user.manage").andExpect(status().isForbidden()); // org-wide role
            // and through assignment: no self-assignment, no assignment beyond the ceiling
            assign(delegate, delegateId, mine, collegeA).andExpect(status().isForbidden());
            assertThat(ownerJdbc().sql("SELECT count(*) FROM audit_event WHERE tenant_id = ? AND action = 'role.change_denied'").param(tenant)
                    .query(Long.class).single()).isGreaterThanOrEqualTo(4);
        }

        @Test
        void rolesAreAssignableAtEveryLevelWithinTheirScopeRules() throws Exception {
            UUID orgWide = created(createRole(admin, "Hierarchy reader", null, "org_unit.read"));
            UUID[] levels = {null, university, collegeA, campusA, deptA};
            for (UUID level : levels) {
                UUID u = UUID.randomUUID();
                Fixture.Caller c = account(u, null, level);
                assign(admin, u, orgWide, level).andExpect(status().isCreated());
                if (level != null) {
                    as(c, get("/api/v1/org-units/" + level)).andExpect(status().isOk());
                    as(c, get("/api/v1/org-units/" + collegeB)).andExpect(level == university ? status().isOk() : status().isForbidden());
                } else {
                    as(c, get("/api/v1/org-units/" + collegeB)).andExpect(status().isOk());
                }
            }
            UUID collegeOnly = created(createRole(admin, "College A reader", collegeA, "org_unit.read"));
            UUID u = UUID.randomUUID();
            account(u, null, collegeA);
            assign(admin, u, collegeOnly, collegeB).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("role_scope_mismatch"));
            assign(admin, u, collegeOnly, null).andExpect(status().isUnprocessableContent());
            assign(admin, u, collegeOnly, deptA).andExpect(status().isCreated());                 // below its scope is fine
            UUID orgOnly = created(createRole(admin, "Auditor", null, "audit.read"));
            assign(admin, u, orgOnly, collegeA).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("organization_only_role"));
            assign(admin, u, orgOnly, null).andExpect(status().isCreated());
            as(admin, get("/api/v1/roles/" + collegeOnly + "/holders")).andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.items[0].scopeName").value("Unit D"));
            as(admin, get("/api/v1/role-assignments").param("roleId", orgWide.toString())).andExpect(jsonPath("$.totalElements").value(5));
            as(admin, get("/api/v1/role-assignments").param("orgUnitId", campusA.toString())).andExpect(jsonPath("$.totalElements").value(3));
        }
    }

    @Nested
    class Concurrency {

        @Test
        void concurrentEditsCreatesAndArchiveVersusAssign() throws Exception {
            UUID role = created(createRole(admin, "Race", null, "user.read"));
            long v = version(role);
            int[] edits = concurrently(
                    () -> as(admin, json(patch("/api/v1/roles/" + role), "{\"version\":" + v + ",\"permissions\":[\"staff.read\"]}")).andReturn().getResponse().getStatus(),
                    () -> as(admin, json(patch("/api/v1/roles/" + role), "{\"version\":" + v + ",\"permissions\":[\"org_unit.read\"]}")).andReturn().getResponse().getStatus());
            assertThat(edits).as(Arrays.toString(edits)).containsExactlyInAnyOrder(200, 409);

            int[] creates = concurrently(
                    () -> createRole(admin, "Twin", null, "user.read").andReturn().getResponse().getStatus(),
                    () -> createRole(admin, "Twin", null, "user.read").andReturn().getResponse().getStatus());
            assertThat(creates).as(Arrays.toString(creates)).containsExactlyInAnyOrder(201, 409);

            for (int round = 0; round < 5; round++) {
                UUID r = created(createRole(admin, "Archive race " + round, null, "user.read"));
                UUID u = UUID.randomUUID();
                account(u, null, collegeA);
                long rv = version(r);
                int[] codes = concurrently(
                        () -> as(admin, delete("/api/v1/roles/" + r).param("version", String.valueOf(rv))).andReturn().getResponse().getStatus(),
                        () -> assign(admin, u, r, collegeA).andReturn().getResponse().getStatus());
                long activeOnArchived = ownerJdbc().sql("""
                        SELECT count(*) FROM role_assignment ra JOIN role ro ON ro.id = ra.role_id
                        WHERE ra.role_id = ? AND ro.archived_at IS NOT NULL AND ra.revoked_at IS NULL""").param(r).query(Long.class).single();
                assertThat(activeOnArchived).as("round %d: %s", round, Arrays.toString(codes)).isZero();
                assertThat(codes[0] == 204 || codes[1] == 201).as(Arrays.toString(codes)).isTrue();
            }

            // deactivate while assigning: no assignment of an inactive role is ever created afterwards
            UUID r = created(createRole(admin, "Deactivate race", null, "user.read"));
            UUID u = UUID.randomUUID();
            Fixture.Caller c = account(u, null, collegeA);
            long rv = version(r);
            concurrently(
                    () -> as(admin, json(post("/api/v1/roles/" + r + "/status"), "{\"status\":\"INACTIVE\",\"version\":" + rv + ",\"reason\":\"race\"}"))
                            .andReturn().getResponse().getStatus(),
                    () -> assign(admin, u, r, collegeA).andReturn().getResponse().getStatus());
            as(c, get("/api/v1/users")).andExpect(status().isForbidden());    // whichever order: the inactive role grants nothing
        }
    }

    @Nested
    class Isolation {

        @Test
        void customRolesOfOneOrganizationAreInvisibleToOthers() throws Exception {
            UUID role = created(createRole(admin, "Private", null, "user.read"));
            as(BELA_ORG_ADMIN_B, get("/api/v1/roles/" + role)).andExpect(status().isNotFound());
            as(BELA_ORG_ADMIN_B, json(patch("/api/v1/roles/" + role), "{\"version\":0,\"name\":\"x\"}")).andExpect(status().isNotFound());
            as(BELA_ORG_ADMIN_B, json(post("/api/v1/roles/" + role + "/status"), "{\"status\":\"INACTIVE\",\"version\":0,\"reason\":\"x\"}"))
                    .andExpect(status().isNotFound());
            as(BELA_ORG_ADMIN_B, get("/api/v1/roles").param("type", "custom")).andExpect(jsonPath("$.items[*].id", Matchers.not(Matchers.hasItem(role.toString()))));
            UUID bUser = UUID.randomUUID();
            ownerJdbc().sql("INSERT INTO user_account (id, tenant_id, external_subject, email, display_name, given_name) VALUES (?, ?, ?, ?, 'B user', 'B')")
                    .params(bUser, TENANT_B, "sub-" + bUser, bUser + "@b.test").update();
            ownerJdbc().sql("INSERT INTO org_assignment (id, tenant_id, user_account_id, org_unit_id, is_primary) VALUES (?, ?, ?, NULL, true)")
                    .params(UUID.randomUUID(), TENANT_B, bUser).update();
            as(BELA_ORG_ADMIN_B, json(post("/api/v1/users/" + bUser + "/role-assignments"), "{\"roleId\":\"" + role + "\"}"))
                    .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("invalid_role"));
            as(BELA_ORG_ADMIN_B, get("/api/v1/roles/" + role + "/holders")).andExpect(status().isNotFound());
        }
    }

    /** Increment 1.3 acceptance review (2026-11-03): scope coverage and college administrator safeguards. */
    @Nested
    class AcceptanceVerification {

        private UUID holder(UUID role, UUID scope) throws Exception {
            UUID u = UUID.randomUUID();
            account(u, null, scope);
            assign(admin, u, role, scope).andExpect(status().isCreated());
            return u;
        }

        private Fixture.Caller callerOf(UUID user) {
            return new Fixture.Caller("sub-" + user, tenant);
        }

        @Test
        void universityAndCampusScopesWorkForCreationAssignmentEvaluationInheritanceAndSummaries() throws Exception {
            UUID campusB = unit("CAMPUS", collegeB, "KB");
            UUID deptB = unit("DEPARTMENT", campusB, "DB");
            // creation at university and campus level
            UUID uniRole = created(createRole(admin, "University reader", university, "org_unit.read", "user.read"));
            UUID campusRole = created(createRole(admin, "Campus reader", campusA, "org_unit.read"));
            as(admin, get("/api/v1/roles/" + uniRole)).andExpect(jsonPath("$.role.scopeOrgUnitId").value(university.toString()))
                    .andExpect(jsonPath("$.role.scopeName").value("Unit U"));
            as(admin, get("/api/v1/roles/" + campusRole)).andExpect(jsonPath("$.role.scopeName").value("Unit K"));

            // assignment: at the role's own level and below; never above or beside it
            UUID atUniversity = holder(uniRole, university);
            UUID uniRoleAtCampus = holder(uniRole, campusA);
            holder(uniRole, collegeB);
            UUID atCampus = holder(campusRole, campusA);
            holder(campusRole, deptA);
            UUID other = UUID.randomUUID();
            account(other, null, collegeA);
            assign(admin, other, campusRole, collegeA).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("role_scope_mismatch"));
            assign(admin, other, campusRole, university).andExpect(status().isUnprocessableContent());
            assign(admin, other, campusRole, campusB).andExpect(status().isUnprocessableContent());
            assign(admin, other, uniRole, null).andExpect(status().isUnprocessableContent());

            // evaluation and inheritance
            Fixture.Caller u = callerOf(atUniversity);
            for (UUID unit : new UUID[] {university, collegeA, collegeB, campusA, deptA, campusB, deptB}) {
                as(u, get("/api/v1/org-units/" + unit)).andExpect(status().isOk());              // university covers its whole subtree
            }
            Fixture.Caller c = callerOf(atCampus);
            as(c, get("/api/v1/org-units/" + campusA)).andExpect(status().isOk());
            as(c, get("/api/v1/org-units/" + deptA)).andExpect(status().isOk());               // campus covers its departments
            for (UUID unit : new UUID[] {university, collegeA, collegeB, campusB, deptB}) {
                as(c, get("/api/v1/org-units/" + unit)).andExpect(status().isForbidden());       // but nothing above or beside
            }
            Fixture.Caller uc = callerOf(uniRoleAtCampus);
            as(uc, get("/api/v1/org-units/" + deptA)).andExpect(status().isOk());
            as(uc, get("/api/v1/org-units/" + collegeA)).andExpect(status().isForbidden());    // a university role assigned at a campus covers the campus only
            as(uc, get("/api/v1/users")).andExpect(status().isOk());

            // summaries: scope counts, holders, access overview and comparison data name the level
            as(admin, get("/api/v1/roles/" + uniRole)).andExpect(jsonPath("$.assignmentsByScope.UNIVERSITY").value(1))
                    .andExpect(jsonPath("$.assignmentsByScope.CAMPUS").value(1)).andExpect(jsonPath("$.assignmentsByScope.COLLEGE").value(1));
            as(admin, get("/api/v1/roles/" + campusRole)).andExpect(jsonPath("$.assignmentsByScope.CAMPUS").value(1))
                    .andExpect(jsonPath("$.assignmentsByScope.DEPARTMENT").value(1));
            as(admin, get("/api/v1/roles/" + uniRole + "/holders")).andExpect(jsonPath("$.items[*].scopeName", Matchers.hasItems("Unit U", "Unit K", "Unit B")));
            as(admin, get("/api/v1/users/" + atUniversity + "/access"))
                    .andExpect(jsonPath("$.permissions[?(@.permission == 'org_unit.read')].scopes[0].scopeName").value("Unit U"));
            as(admin, get("/api/v1/users/" + atCampus + "/access"))
                    .andExpect(jsonPath("$.permissions[?(@.permission == 'org_unit.read')].scopes[0].scopeName").value("Unit K"));
            as(admin, get("/api/v1/roles/assignable")).andExpect(jsonPath("$[?(@.id == '" + campusRole + "')].scopeName").value("Unit K"));
            as(admin, get("/api/v1/role-assignments").param("orgUnitId", university.toString())).andExpect(jsonPath("$.totalElements").value(5));
            as(admin, get("/api/v1/role-assignments").param("orgUnitId", campusA.toString())).andExpect(jsonPath("$.totalElements").value(3));
        }

        @Test
        void collegeAdministratorSafeguards() throws Exception {
            UUID campusB = unit("CAMPUS", collegeB, "KB");
            UUID collegeAdminId = UUID.randomUUID();
            Fixture.Caller ca = account(collegeAdminId, INSTITUTION_ADMIN, collegeA);

            // 1. only within the authorized college subtree
            created(createRole(ca, "College role", collegeA, "org_unit.read"));
            created(createRole(ca, "Campus role", campusA, "org_unit.read"));
            created(createRole(ca, "Department role", deptA, "org_unit.read"));
            // 3. never organization-wide or cross-college (including above the college)
            createRole(ca, "Org-wide", null, "org_unit.read").andExpect(status().isForbidden());
            createRole(ca, "University", university, "org_unit.read").andExpect(status().isForbidden());
            createRole(ca, "Other college", collegeB, "org_unit.read").andExpect(status().isForbidden());
            createRole(ca, "Other campus", campusB, "org_unit.read").andExpect(status().isForbidden());
            UUID bRole = created(createRole(admin, "College B role", collegeB, "org_unit.read"));
            edit(ca, bRole, "org_unit.read", "user.read").andExpect(status().isForbidden());
            setStatus(ca, bRole, "INACTIVE", "x").andExpect(status().isForbidden());
            as(ca, delete("/api/v1/roles/" + bRole).param("version", String.valueOf(version(bRole)))).andExpect(status().isForbidden());
            UUID aUser = UUID.randomUUID();
            account(aUser, null, collegeA);
            assign(ca, aUser, bRole, collegeA).andExpect(status().isUnprocessableContent());              // B's role cannot be used in A
            UUID aRole = created(createRole(ca, "Assistant", collegeA, "org_unit.read"));
            UUID bUser = UUID.randomUUID();
            account(bUser, null, collegeB);
            assign(ca, bUser, aRole, collegeB).andExpect(status().isForbidden());                        // B's people are not even visible
            assign(admin, bUser, aRole, collegeB).andExpect(status().isUnprocessableContent())              // and A's role cannot be used in B,
                    .andExpect(jsonPath("$.code").value("role_scope_mismatch"));                            // even by the organization admin
            // 4. never platform-level or organization-only permissions
            createRole(ca, "Platform", collegeA, "platform.tenant.manage").andExpect(status().isBadRequest());
            createRole(ca, "Settings", collegeA, "tenant.settings.manage").andExpect(status().isUnprocessableContent());
            createRole(ca, "Audit", collegeA, "audit.read").andExpect(status().isUnprocessableContent());

            // 2. only permissions held at that scope: a college-level role editor without user.manage
            UUID editorRole = created(createRole(admin, "College role editor", collegeA, "role.manage", "role.read", "role.assign", "user.read"));
            UUID editorId = UUID.randomUUID();
            Fixture.Caller editor = account(editorId, null, collegeA);
            assign(admin, editorId, editorRole, collegeA).andExpect(status().isCreated());
            createRole(editor, "Too strong", collegeA, "user.read", "user.manage").andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("privilege_escalation"));
            String clone = "{\"name\":\"Clone of college admin\",\"scopeOrgUnitId\":\"" + collegeA + "\",\"clonedFromRoleId\":\"" + INSTITUTION_ADMIN
                    + "\",\"permissions\":" + perms("org_unit.read", "org_unit.manage", "user.read", "user.manage") + "}";
            as(editor, json(post("/api/v1/roles"), clone).header("Idempotency-Key", "k-" + UUID.randomUUID())).andExpect(status().isForbidden());

            // 5. no self-elevation: not by editing the role you hold, not by assigning to yourself, not by reactivation
            edit(editor, editorRole, "role.manage", "role.read", "role.assign", "user.read", "user.manage").andExpect(status().isForbidden());
            UUID mine = created(createRole(editor, "Readers", collegeA, "user.read"));
            assign(editor, editorId, mine, collegeA).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("self_assignment_forbidden"));
            UUID strong = created(createRole(admin, "Strong A", collegeA, "user.read", "user.manage"));
            setStatus(admin, strong, "INACTIVE", "parked").andExpect(status().isOk());
            setStatus(editor, strong, "ACTIVE", null).andExpect(status().isForbidden());                  // reactivation is bounded by the ceiling too
            edit(ca, editorRole, "role.manage", "role.read", "role.assign", "user.read", "user.manage").andExpect(status().isOk()); // the college admin may (holds it)
            as(editor, get("/api/v1/users")).andExpect(status().isOk());
            as(ca, json(post("/api/v1/users/" + collegeAdminId + "/role-assignments"), "{\"roleId\":\"" + ORG_ADMIN + "\"}"))
                    .andExpect(status().isForbidden());                                                   // and cannot elevate themselves

            assertThat(ownerJdbc().sql("SELECT count(*) FROM audit_event WHERE tenant_id = ? AND outcome = 'DENIED' AND action IN "
                    + "('role.change_denied', 'role.self_assignment_denied', 'security.access_denied', 'role.assignment_denied')")
                    .param(tenant).query(Long.class).single()).isGreaterThanOrEqualTo(10);
        }
    }

    @Test
    void migrationKeepsSystemRolesActiveAndGivesUnitAdministratorsRoleManagement() {
        assertThat(ownerJdbc().sql("SELECT count(*) FROM role WHERE system_role AND status = 'ACTIVE' AND archived_at IS NULL").query(Long.class).single())
                .isEqualTo(15);
        assertThat(ownerJdbc().sql("SELECT 'role.manage' = ANY(permissions) FROM role WHERE code = 'INSTITUTION_ADMIN' AND system_role")
                .query(Boolean.class).single()).isTrue();
        assertThat(ownerJdbc().sql("SELECT count(*) FROM role_assignment WHERE tenant_id IN (?, ?)").params(TENANT_A, TENANT_B)
                .query(Long.class).single()).isGreaterThan(0);
    }
}
