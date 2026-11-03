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
                    .andExpect(jsonPath("$[?(@.domain == 'STUDENTS')].permissions[0].availability").value("PLANNED"))
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
            createRole(admin, "Bad 2", null, "student.read").andExpect(status().isBadRequest())
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
