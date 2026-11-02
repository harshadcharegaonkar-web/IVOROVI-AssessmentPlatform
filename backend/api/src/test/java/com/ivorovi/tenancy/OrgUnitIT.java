package com.ivorovi.tenancy;

import static com.ivorovi.support.Fixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ivorovi.support.Fixture;
import com.ivorovi.support.IntegrationTest;
import com.ivorovi.support.TestTokens;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Increment 1.1: organization hierarchy. Each test builds its data in a fresh tenant (created through the schema
 * owner, the way provisioning will in later increments), so shared fixtures used by other suites stay untouched.
 */
class OrgUnitIT extends IntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID ORG_ADMIN_ROLE = UUID.fromString("01920000-0000-7000-8000-000000000002");
    private static final UUID INSTITUTION_ADMIN_ROLE = UUID.fromString("01920000-0000-7000-8000-000000000003");
    private static final UUID FACULTY_ROLE = UUID.fromString("01920000-0000-7000-8000-000000000007");

    @Autowired StringRedisTemplate redis;

    private UUID tenant;
    private Fixture.Caller admin;

    @BeforeEach
    void freshTenant() {
        tenant = UUID.randomUUID();
        ownerJdbc().sql("INSERT INTO tenant (id, code, name, status) VALUES (?, ?, 'Org test tenant', 'ACTIVE')")
                .params(tenant, "orgtest-" + tenant.toString().substring(0, 8)).update();
        admin = user(ORG_ADMIN_ROLE, "TENANT", tenant);
    }

    private Fixture.Caller user(UUID role, String scopeType, UUID scopeId) {
        UUID id = UUID.randomUUID();
        String subject = "sub-" + id;
        ownerJdbc().sql("INSERT INTO user_account (id, tenant_id, external_subject, email, display_name) VALUES (?, ?, ?, ?, ?)")
                .params(id, tenant, subject, id + "@orgtest.example.test", "User " + id.toString().substring(0, 4)).update();
        ownerJdbc().sql("""
                INSERT INTO role_assignment (id, tenant_id, user_account_id, role_id, scope_type, scope_id)
                VALUES (?, ?, ?, ?, ?, ?)""").params(UUID.randomUUID(), tenant, id, role, scopeType, scopeId).update();
        return new Fixture.Caller(subject, tenant);
    }

    /** Runs one statement as the application role with the tenant bound, as the API's connections are. */
    private static void appJdbcAsTenant(UUID tenantId, String sql) throws Exception {
        try (var c = java.sql.DriverManager.getConnection(com.ivorovi.support.Containers.jdbcUrl(), "ivorovi_app",
                com.ivorovi.support.Containers.APP_PASSWORD); var st = c.createStatement()) {
            st.execute("SELECT set_config('app.tenant_id', '" + tenantId + "', false)");
            st.executeUpdate(sql);
        }
    }

    private ResultActions as(Fixture.Caller c, MockHttpServletRequestBuilder r) throws Exception {
        return mvc.perform(r.header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.forCaller(c)));
    }

    private ResultActions create(Fixture.Caller c, String type, UUID parent, String code, String name) throws Exception {
        String body = "{\"type\":\"" + type + "\",\"code\":\"" + code + "\",\"name\":\"" + name + "\""
                + (parent == null ? "" : ",\"parentId\":\"" + parent + "\"") + "}";
        return as(c, post("/api/v1/org-units").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("Idempotency-Key", "k-" + UUID.randomUUID()));
    }

    private UUID created(ResultActions r) throws Exception {
        JsonNode n = JSON.readTree(r.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return UUID.fromString(n.path("unit").path("id").asString());
    }

    private long version(UUID id) {
        return ownerJdbc().sql("SELECT version FROM org_unit WHERE id = ?").param(id).query(Long.class).single();
    }

    private ResultActions statusChange(Fixture.Caller c, UUID id, String action) throws Exception {
        return as(c, post("/api/v1/org-units/" + id + "/" + action).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + version(id) + ",\"reason\":\"test\"}"));
    }

    private ResultActions move(Fixture.Caller c, UUID id, UUID newParent) throws Exception {
        return as(c, post("/api/v1/org-units/" + id + "/move").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + version(id) + (newParent == null ? "" : ",\"newParentId\":\"" + newParent + "\"") + "}"));
    }

    @Nested
    class Hierarchy {

        @Test
        void buildsTheFullHierarchyWithOptionalLevels() throws Exception {
            UUID university = created(create(admin, "UNIVERSITY", null, "AU", "Test University"));
            UUID college = created(create(admin, "COLLEGE", university, "ENG", "College of Engineering"));
            UUID standalone = created(create(admin, "COLLEGE", null, "ARTS", "Stand-alone Arts College"));
            UUID campus = created(create(admin, "CAMPUS", college, "NORTH", "North Campus"));
            UUID cse = created(create(admin, "DEPARTMENT", campus, "CSE", "Computer Science"));
            created(create(admin, "DEPARTMENT", standalone, "HIST", "History"));        // college → department (no campus)
            created(create(admin, "DEPARTMENT", university, "LAW", "School of Law"));    // university → department
            created(create(admin, "CAMPUS", university, "CITY", "City Campus"));         // university → campus

            as(admin, get("/api/v1/org-units/" + cse)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.ancestors[*].code").value(org.hamcrest.Matchers.contains("AU", "ENG", "NORTH")))
                    .andExpect(jsonPath("$.unit.parentName").value("North Campus"));
            as(admin, get("/api/v1/org-units/" + university)).andExpect(jsonPath("$.childCounts.COLLEGE").value(1))
                    .andExpect(jsonPath("$.childCounts.DEPARTMENT").value(1)).andExpect(jsonPath("$.childCounts.CAMPUS").value(1));
            as(admin, get("/api/v1/org-units").param("type", "DEPARTMENT")).andExpect(jsonPath("$.totalElements").value(3));
            as(admin, get("/api/v1/org-units/tree")).andExpect(jsonPath("$.length()").value(8));
            as(admin, get("/api/v1/org-units/summary"))
                    .andExpect(jsonPath("$.active.UNIVERSITY").value(1)).andExpect(jsonPath("$.active.COLLEGE").value(2))
                    .andExpect(jsonPath("$.active.CAMPUS").value(2)).andExpect(jsonPath("$.active.DEPARTMENT").value(3));
        }

        @Test
        void rejectsInvalidParentChildRelationships() throws Exception {
            UUID university = created(create(admin, "UNIVERSITY", null, "U1", "U1"));
            UUID college = created(create(admin, "COLLEGE", university, "C1", "C1"));
            UUID campus = created(create(admin, "CAMPUS", college, "K1", "K1"));
            UUID dept = created(create(admin, "DEPARTMENT", campus, "D1", "D1"));
            create(admin, "CAMPUS", null, "X", "Top-level campus").andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("invalid_hierarchy"));
            create(admin, "DEPARTMENT", null, "X", "Top-level department").andExpect(status().isUnprocessableContent());
            create(admin, "UNIVERSITY", college, "X", "University under college").andExpect(status().isUnprocessableContent());
            create(admin, "COLLEGE", campus, "X", "College under campus").andExpect(status().isUnprocessableContent());
            create(admin, "DEPARTMENT", dept, "X", "Department under department").andExpect(status().isUnprocessableContent());
            create(admin, "CAMPUS", dept, "X", "Campus under department").andExpect(status().isUnprocessableContent());
            // moving a unit under one of its own descendants is a cycle, detected before type rules
            move(admin, college, campus).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("hierarchy_cycle"));
            move(admin, dept, null).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("invalid_hierarchy"));
        }

        @Test
        void codesAreUniqueAmongSiblingsCaseInsensitively() throws Exception {
            UUID a = created(create(admin, "COLLEGE", null, "A", "College A"));
            UUID b = created(create(admin, "COLLEGE", null, "B", "College B"));
            created(create(admin, "DEPARTMENT", a, "CSE", "CSE at A"));
            created(create(admin, "DEPARTMENT", b, "CSE", "CSE at B"));          // same code, different parent: allowed
            create(admin, "DEPARTMENT", a, "cse", "Duplicate").andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("code_not_unique"));
            create(admin, "COLLEGE", null, "a", "Duplicate top-level").andExpect(status().isConflict());
        }

        @Test
        void aUnitCannotBecomeItsOwnAncestor() throws Exception {
            UUID college = created(create(admin, "COLLEGE", null, "C", "C"));
            move(admin, college, college).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("hierarchy_cycle"));
        }

        @Test
        void moveRewritesTheSubtreeAndRecordsHistory() throws Exception {
            UUID a = created(create(admin, "COLLEGE", null, "A", "A"));
            UUID b = created(create(admin, "COLLEGE", null, "B", "B"));
            UUID campus = created(create(admin, "CAMPUS", a, "K", "K"));
            UUID dept = created(create(admin, "DEPARTMENT", campus, "D", "D"));
            move(admin, campus, b).andExpect(status().isOk()).andExpect(jsonPath("$.unit.parentId").value(b.toString()));
            as(admin, get("/api/v1/org-units/" + dept)).andExpect(jsonPath("$.ancestors[*].code").value(org.hamcrest.Matchers.contains("B", "K")));
            as(admin, get("/api/v1/org-units/" + campus + "/history")).andExpect(jsonPath("$.items[0].changeType").value("MOVED"))
                    .andExpect(jsonPath("$.items[0].before.parentName").value("A")).andExpect(jsonPath("$.items[0].after.parentName").value("B"));
            assertThat(ownerJdbc().sql("SELECT count(*) FROM audit_event WHERE tenant_id = ? AND action = 'org_unit.moved'")
                    .param(tenant).query(Long.class).single()).isEqualTo(1);
            assertThat(ownerJdbc().sql("SELECT count(*) FROM outbox_event WHERE tenant_id = ? AND event_type = 'org_unit.moved'")
                    .param(tenant).query(Long.class).single()).isEqualTo(1);
        }
    }

    @Nested
    class Lifecycle {

        @Test
        void deactivationAndActivationFollowTheHierarchy() throws Exception {
            UUID college = created(create(admin, "COLLEGE", null, "C", "C"));
            UUID campus = created(create(admin, "CAMPUS", college, "K", "K"));
            UUID dept = created(create(admin, "DEPARTMENT", campus, "D", "D"));
            statusChange(admin, college, "deactivate").andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("has_active_children"));
            statusChange(admin, dept, "deactivate").andExpect(status().isOk()).andExpect(jsonPath("$.unit.status").value("INACTIVE"));
            statusChange(admin, campus, "deactivate").andExpect(status().isOk());
            create(admin, "DEPARTMENT", campus, "NEW", "Under inactive").andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("parent_inactive"));
            statusChange(admin, dept, "activate").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("parent_inactive"));
            UUID other = created(create(admin, "COLLEGE", null, "O", "O"));
            UUID activeDept = created(create(admin, "DEPARTMENT", other, "AD", "Active dept"));
            move(admin, activeDept, campus).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("parent_inactive"));
            statusChange(admin, campus, "activate").andExpect(status().isOk());
            statusChange(admin, dept, "activate").andExpect(status().isOk());
            as(admin, get("/api/v1/org-units/" + dept + "/history")).andExpect(jsonPath("$.items[0].changeType").value("ACTIVATED"))
                    .andExpect(jsonPath("$.items[1].changeType").value("DEACTIVATED"))
                    .andExpect(jsonPath("$.items[1].reason").value("test"));
        }

        @Test
        void onlyUnusedUnitsCanBeDeleted() throws Exception {
            UUID college = created(create(admin, "COLLEGE", null, "C", "C"));
            UUID dept = created(create(admin, "DEPARTMENT", college, "D", "D"));
            UUID assigned = created(create(admin, "DEPARTMENT", college, "A", "Assigned"));
            user(FACULTY_ROLE, "ORG_UNIT", assigned);
            as(admin, delete("/api/v1/org-units/" + college).param("version", String.valueOf(version(college))))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("org_unit_has_dependents"));
            as(admin, delete("/api/v1/org-units/" + assigned).param("version", String.valueOf(version(assigned))))
                    .andExpect(status().isConflict());
            as(admin, delete("/api/v1/org-units/" + dept).param("version", String.valueOf(version(dept))))
                    .andExpect(status().isNoContent());
            as(admin, get("/api/v1/org-units/" + dept)).andExpect(status().isNotFound());
            assertThat(ownerJdbc().sql("SELECT change_type FROM org_unit_history WHERE org_unit_id = ? ORDER BY changed_at DESC LIMIT 1")
                    .param(dept).query(String.class).single()).isEqualTo("DELETED");
        }

        @Test
        void staleVersionsAreRejected() throws Exception {
            UUID college = created(create(admin, "COLLEGE", null, "C", "C"));
            as(admin, patch("/api/v1/org-units/" + college).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"version\":" + version(college) + ",\"name\":\"Renamed\"}")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.unit.name").value("Renamed"));
            as(admin, patch("/api/v1/org-units/" + college).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"version\":0,\"name\":\"Lost update\"}")).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("concurrent_modification"));
            as(admin, get("/api/v1/org-units/" + college + "/history")).andExpect(jsonPath("$.items[0].changeType").value("UPDATED"))
                    .andExpect(jsonPath("$.items[0].before.name").value("C")).andExpect(jsonPath("$.items[0].after.name").value("Renamed"));
        }

        @Test
        void createIsIdempotent() throws Exception {
            String body = "{\"type\":\"COLLEGE\",\"code\":\"IDEM\",\"name\":\"Idempotent College\"}";
            String key = "k-" + UUID.randomUUID();
            String first = as(admin, post("/api/v1/org-units").contentType(MediaType.APPLICATION_JSON).content(body).header("Idempotency-Key", key))
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
            String second = as(admin, post("/api/v1/org-units").contentType(MediaType.APPLICATION_JSON).content(body).header("Idempotency-Key", key))
                    .andExpect(status().isCreated()).andExpect(header().string("Idempotency-Replayed", "true"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(second).isEqualTo(first);
            assertThat(ownerJdbc().sql("SELECT count(*) FROM org_unit WHERE tenant_id = ? AND code = 'IDEM'").param(tenant)
                    .query(Long.class).single()).isEqualTo(1);
        }

        @Test
        void validatesAttributesAndBranding() throws Exception {
            UUID college = created(create(admin, "COLLEGE", null, "C", "C"));
            UUID dept = created(create(admin, "DEPARTMENT", college, "D", "D"));
            as(admin, patch("/api/v1/org-units/" + college).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"version\":" + version(college) + ",\"attributes\":{\"city\":\"Pune\"},\"branding\":{\"accentColor\":\"#0F766E\"}}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.unit.attributes.city").value("Pune"))
                    .andExpect(jsonPath("$.unit.branding.accentColor").value("#0f766e"));
            as(admin, patch("/api/v1/org-units/" + college).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"version\":" + version(college) + ",\"attributes\":{\"password\":\"x\"}}")).andExpect(status().isBadRequest());
            as(admin, patch("/api/v1/org-units/" + college).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"version\":" + version(college) + ",\"branding\":{\"accentColor\":\"red;\"}}")).andExpect(status().isBadRequest());
            as(admin, patch("/api/v1/org-units/" + dept).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"version\":" + version(dept) + ",\"branding\":{\"accentColor\":\"#000000\"}}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("branding_not_supported"));
        }
    }

    @Nested
    class Authorization {

        @Test
        void scopedAdministratorsAreConfinedToTheirSubtree() throws Exception {
            UUID university = created(create(admin, "UNIVERSITY", null, "U", "U"));
            UUID mine = created(create(admin, "COLLEGE", university, "MINE", "My college"));
            UUID other = created(create(admin, "COLLEGE", university, "OTHER", "Other college"));
            Fixture.Caller scoped = user(INSTITUTION_ADMIN_ROLE, "ORG_UNIT", mine);

            UUID campus = created(create(scoped, "CAMPUS", mine, "K", "Campus in my college"));
            created(create(scoped, "DEPARTMENT", campus, "D", "Department in my campus"));
            create(scoped, "DEPARTMENT", other, "X", "Sibling college").andExpect(status().isForbidden());
            create(scoped, "COLLEGE", null, "X", "Top level").andExpect(status().isForbidden());
            as(scoped, patch("/api/v1/org-units/" + university).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"version\":" + version(university) + ",\"name\":\"Hijack\"}")).andExpect(status().isForbidden());
            move(scoped, campus, other).andExpect(status().isForbidden());
            as(scoped, get("/api/v1/org-units")).andExpect(jsonPath("$.totalElements").value(3));     // college, campus, department
            as(scoped, get("/api/v1/org-units/" + other)).andExpect(status().isForbidden());
            assertThat(ownerJdbc().sql("SELECT count(*) FROM audit_event WHERE tenant_id = ? AND action = 'security.access_denied'")
                    .param(tenant).query(Long.class).single()).isGreaterThanOrEqualTo(4);
        }

        @Test
        void movingAUnitOutOfAScopeRevokesAccessImmediately() throws Exception {
            UUID a = created(create(admin, "COLLEGE", null, "A", "A"));
            UUID b = created(create(admin, "COLLEGE", null, "B", "B"));
            UUID dept = created(create(admin, "DEPARTMENT", a, "D", "D"));
            Fixture.Caller scoped = user(INSTITUTION_ADMIN_ROLE, "ORG_UNIT", a);
            as(scoped, get("/api/v1/org-units/" + dept)).andExpect(status().isOk());          // path now cached
            move(admin, dept, b).andExpect(status().isOk());
            as(scoped, get("/api/v1/org-units/" + dept)).andExpect(status().isForbidden());   // cache evicted on move
        }

        @Test
        void readOnlyRolesCannotChangeTheHierarchy() throws Exception {
            UUID college = created(create(admin, "COLLEGE", null, "C", "C"));
            Fixture.Caller faculty = user(FACULTY_ROLE, "ORG_UNIT", college);
            as(faculty, get("/api/v1/org-units/" + college)).andExpect(status().isOk());
            create(faculty, "DEPARTMENT", college, "D", "D").andExpect(status().isForbidden());
            statusChange(faculty, college, "deactivate").andExpect(status().isForbidden());
            as(faculty, delete("/api/v1/org-units/" + college).param("version", String.valueOf(version(college)))).andExpect(status().isForbidden());
        }

        @Test
        void otherTenantsUnitsAreInvisibleAndCannotBeParents() throws Exception {
            UUID mine = created(create(admin, "COLLEGE", null, "C", "C"));
            create(admin, "DEPARTMENT", INST_B1, "X", "Under another tenant's college").andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("invalid_parent"));
            move(admin, mine, INST_B1).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("invalid_parent"));
            as(BELA_ORG_ADMIN_B, get("/api/v1/org-units/" + mine)).andExpect(status().isNotFound());
            as(BELA_ORG_ADMIN_B, get("/api/v1/org-units/" + mine + "/history")).andExpect(status().isNotFound());
            as(BELA_ORG_ADMIN_B, patch("/api/v1/org-units/" + mine).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"version\":0,\"name\":\"x\"}")).andExpect(status().isNotFound());
            as(BELA_ORG_ADMIN_B, delete("/api/v1/org-units/" + mine).param("version", "0")).andExpect(status().isNotFound());
            as(BELA_ORG_ADMIN_B, get("/api/v1/org-units").param("q", "C")).andExpect(jsonPath("$.items[*].id",
                    org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(mine.toString()))));
        }
    }

    /** Increment 1.1 acceptance review (2026-11-02): the six final verification checks. */
    @Nested
    class AcceptanceChecks {

        @Test
        void check1_moveRecalculatesInheritedPermissionsForTheWholeSubtree() throws Exception {
            UUID a = created(create(admin, "COLLEGE", null, "A", "A"));
            UUID b = created(create(admin, "COLLEGE", null, "B", "B"));
            UUID campus = created(create(admin, "CAMPUS", a, "K", "K"));
            UUID dept = created(create(admin, "DEPARTMENT", campus, "D", "D"));
            Fixture.Caller adminA = user(INSTITUTION_ADMIN_ROLE, "ORG_UNIT", a);
            Fixture.Caller adminB = user(INSTITUTION_ADMIN_ROLE, "ORG_UNIT", b);
            Fixture.Caller facultyB = user(FACULTY_ROLE, "ORG_UNIT", b);
            for (UUID id : new UUID[] {campus, dept}) {
                as(adminA, get("/api/v1/org-units/" + id)).andExpect(status().isOk());
                as(adminB, get("/api/v1/org-units/" + id)).andExpect(status().isForbidden());
                as(facultyB, get("/api/v1/org-units/" + id)).andExpect(status().isForbidden());
            }
            move(admin, campus, b).andExpect(status().isOk());
            for (UUID id : new UUID[] {campus, dept}) {               // the moved unit and its descendants
                as(adminA, get("/api/v1/org-units/" + id)).andExpect(status().isForbidden());
                as(adminB, get("/api/v1/org-units/" + id)).andExpect(status().isOk());
                as(facultyB, get("/api/v1/org-units/" + id)).andExpect(status().isOk());
            }
            // write permissions follow too: B's administrator can now manage the department, A's cannot
            statusChange(adminA, dept, "deactivate").andExpect(status().isForbidden());
            statusChange(adminB, dept, "deactivate").andExpect(status().isOk());
            as(adminB, get("/api/v1/org-units")).andExpect(jsonPath("$.totalElements").value(3));   // B, campus, dept
            as(adminA, get("/api/v1/org-units")).andExpect(jsonPath("$.totalElements").value(1));   // A only
        }

        @Test
        void check2_moveEvictsCachedPathsOfEveryAffectedUnit() throws Exception {
            UUID a = created(create(admin, "COLLEGE", null, "A", "A"));
            UUID b = created(create(admin, "COLLEGE", null, "B", "B"));
            UUID campus = created(create(admin, "CAMPUS", a, "K", "K"));
            UUID dept = created(create(admin, "DEPARTMENT", campus, "D", "D"));
            Fixture.Caller scoped = user(INSTITUTION_ADMIN_ROLE, "ORG_UNIT", a);
            as(scoped, get("/api/v1/org-units/" + campus)).andExpect(status().isOk());
            as(scoped, get("/api/v1/org-units/" + dept)).andExpect(status().isOk());
            String campusKey = "ivorovi:orgpath:t:" + tenant + ":" + campus;
            String deptKey = "ivorovi:orgpath:t:" + tenant + ":" + dept;
            String otherKey = "ivorovi:orgpath:t:" + tenant + ":" + a;
            as(scoped, get("/api/v1/org-units/" + a)).andExpect(status().isOk());
            assertThat(redis.hasKey(campusKey)).isTrue();
            assertThat(redis.hasKey(deptKey)).isTrue();                 // descendant path cached before the move

            move(admin, campus, b).andExpect(status().isOk());
            assertThat(redis.hasKey(campusKey)).isFalse();
            assertThat(redis.hasKey(deptKey)).isFalse();                // descendants evicted too
            assertThat(redis.hasKey(otherKey)).isTrue();                // unaffected units keep their entries

            // reloaded paths are the new ones
            as(scoped, get("/api/v1/org-units/" + dept)).andExpect(status().isForbidden());
            String reloaded = redis.opsForValue().get(deptKey);
            assertThat(reloaded).contains(b.toString().replace("-", "")).doesNotContain(a.toString().replace("-", ""));
        }

        @Test
        void check3_crossTenantMovesAreRejectedInBothDirections() throws Exception {
            UUID mine = created(create(admin, "COLLEGE", null, "C", "C"));
            UUID dept = created(create(admin, "DEPARTMENT", mine, "D", "D"));
            // my unit under another tenant's parent: the parent does not exist for this tenant
            move(admin, dept, INST_B1).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("invalid_parent"));
            // another tenant's unit moved under mine: not found (RLS), nothing changes
            as(admin, post("/api/v1/org-units/" + INST_B1 + "/move").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"version\":0,\"newParentId\":\"" + mine + "\"}")).andExpect(status().isNotFound());
            // and the other tenant cannot pull my unit under theirs
            as(BELA_ORG_ADMIN_B, post("/api/v1/org-units/" + dept + "/move").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"version\":0,\"newParentId\":\"" + INST_B1 + "\"}")).andExpect(status().isNotFound());
            assertThat(ownerJdbc().sql("SELECT parent_id FROM org_unit WHERE id = ?").param(dept).query(UUID.class).single()).isEqualTo(mine);
            assertThat(ownerJdbc().sql("SELECT tenant_id FROM org_unit WHERE id = ?").param(INST_B1).query(UUID.class).single()).isEqualTo(TENANT_B);
            // the database refuses a cross-tenant parent even if the service were bypassed (composite foreign key)
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> ownerJdbc()
                    .sql("UPDATE org_unit SET parent_id = ? WHERE id = ?").params(INST_B1, dept).update())
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }

        @Test
        void check4_historyAndAuditRemainIntactAfterMoveAndDeactivation() throws Exception {
            UUID a = created(create(admin, "COLLEGE", null, "A", "A"));
            UUID b = created(create(admin, "COLLEGE", null, "B", "B"));
            UUID dept = created(create(admin, "DEPARTMENT", a, "D", "D"));
            String historyBefore = ownerJdbc().sql("""
                    SELECT string_agg(id || change_type || coalesce(before::text,'') || coalesce(after::text,''), '|' ORDER BY changed_at)
                    FROM org_unit_history WHERE org_unit_id = ?""").param(dept).query(String.class).single();
            String auditBefore = ownerJdbc().sql("""
                    SELECT string_agg(seq || ':' || hash, '|' ORDER BY seq) FROM audit_event WHERE tenant_id = ?""")
                    .param(tenant).query(String.class).single();

            move(admin, dept, b).andExpect(status().isOk());
            statusChange(admin, dept, "deactivate").andExpect(status().isOk());

            String historyAfter = ownerJdbc().sql("""
                    SELECT string_agg(id || change_type || coalesce(before::text,'') || coalesce(after::text,''), '|' ORDER BY changed_at)
                    FROM org_unit_history WHERE org_unit_id = ?""").param(dept).query(String.class).single();
            String auditAfter = ownerJdbc().sql("""
                    SELECT string_agg(seq || ':' || hash, '|' ORDER BY seq) FROM audit_event WHERE tenant_id = ?""")
                    .param(tenant).query(String.class).single();
            assertThat(historyAfter).startsWith(historyBefore);           // earlier entries unchanged, new ones appended
            assertThat(auditAfter).startsWith(auditBefore);
            as(admin, get("/api/v1/org-units/" + dept + "/history")).andExpect(jsonPath("$.totalElements").value(3))
                    .andExpect(jsonPath("$.items[*].changeType").value(org.hamcrest.Matchers.contains("DEACTIVATED", "MOVED", "CREATED")));
            as(admin, get("/api/v1/audit-events/verification")).andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(true));
            // the application role cannot rewrite history
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> appJdbcAsTenant(tenant,
                    "UPDATE org_unit_history SET reason = 'tampered' WHERE org_unit_id = '" + dept + "'"))
                    .hasMessageContaining("permission denied");
        }

        @Test
        void check5_unitsWithAnyDependentCannotBeDeleted() throws Exception {
            UUID withChild = created(create(admin, "COLLEGE", null, "P", "Parent"));
            created(create(admin, "DEPARTMENT", withChild, "D", "Child"));
            UUID withGrant = created(create(admin, "COLLEGE", null, "G", "Granted"));
            user(FACULTY_ROLE, "ORG_UNIT", withGrant);
            UUID withOverride = created(create(admin, "COLLEGE", null, "F", "Feature override"));
            as(admin, put("/api/v1/features/module.proctoring/override").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"orgUnitId\":\"" + withOverride + "\",\"enabled\":false}")).andExpect(status().isOk());
            as(admin, get("/api/v1/org-units/" + withOverride)).andExpect(jsonPath("$.dependents.featureOverrides").value(1));
            for (UUID id : new UUID[] {withChild, withGrant, withOverride}) {
                as(admin, delete("/api/v1/org-units/" + id).param("version", String.valueOf(version(id))))
                        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("org_unit_has_dependents"));
                assertThat(ownerJdbc().sql("SELECT count(*) FROM org_unit WHERE id = ?").param(id).query(Long.class).single()).isEqualTo(1);
            }
            // deactivation is the alternative workflow for a unit in use
            statusChange(admin, withGrant, "deactivate").andExpect(status().isOk());
        }

        @Test
        void check6_theOrganizationIsTheTenantNotAUnitType() throws Exception {
            create(admin, "ORGANIZATION", null, "ORG", "Organization as a unit").andExpect(status().isBadRequest());
            assertThat(ownerJdbc().sql("""
                    SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid = 'org_unit'::regclass AND contype = 'c'
                      AND pg_get_constraintdef(oid) LIKE '%unit_type%' AND pg_get_constraintdef(oid) LIKE '%UNIVERSITY%'
                      AND pg_get_constraintdef(oid) NOT LIKE '%parent_id%'""").query(String.class).single())
                    .contains("UNIVERSITY", "COLLEGE", "CAMPUS", "DEPARTMENT").doesNotContain("ORGANIZATION");
            // every unit belongs to exactly one tenant; top-level units hang directly under the tenant
            UUID college = created(create(admin, "COLLEGE", null, "C", "C"));
            as(admin, get("/api/v1/org-units/" + college)).andExpect(jsonPath("$.unit.parentId").doesNotExist())
                    .andExpect(jsonPath("$.ancestors.length()").value(0));
            assertThat(ownerJdbc().sql("SELECT tenant_id FROM org_unit WHERE id = ?").param(college).query(UUID.class).single()).isEqualTo(tenant);
        }
    }

    @Nested
    class Settings {

        @Test
        void organizationsConfigureTheirOwnTerminology() throws Exception {
            as(admin, get("/api/v1/tenant/settings")).andExpect(jsonPath("$.labels.COLLEGE.singular").value("College"));
            as(admin, put("/api/v1/tenant/settings").contentType(MediaType.APPLICATION_JSON).content("""
                    {"version":-1,"timeZone":"Asia/Kolkata","labels":{"COLLEGE":{"singular":"School","plural":"Schools"}}}"""))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.labels.COLLEGE.plural").value("Schools"))
                    .andExpect(jsonPath("$.labels.CAMPUS.singular").value("Campus"));
            as(BELA_ORG_ADMIN_B, get("/api/v1/tenant/settings")).andExpect(jsonPath("$.labels.COLLEGE.singular").value("College"));
            as(admin, put("/api/v1/tenant/settings").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"version\":-1,\"timeZone\":\"Asia/Kolkata\"}")).andExpect(status().isConflict());
            as(admin, put("/api/v1/tenant/settings").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"version\":0,\"timeZone\":\"Mars/Olympus\"}")).andExpect(status().isBadRequest());
            Fixture.Caller faculty = user(FACULTY_ROLE, "TENANT", tenant);
            as(faculty, put("/api/v1/tenant/settings").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"version\":0,\"timeZone\":\"UTC\"}")).andExpect(status().isForbidden());
            // stored unit types are unaffected by labels
            created(create(admin, "COLLEGE", null, "S1", "A school"));
            as(admin, get("/api/v1/org-units").param("type", "COLLEGE")).andExpect(jsonPath("$.items[0].type").value("COLLEGE"));
        }
    }

    @Test
    void phase0InstitutionsWereMigratedWithIdentifiersGrantsAndHistory() {
        // dev/test fixtures created institutions and INSTITUTION-scoped grants before the 1.1 migration ran
        assertThat(ownerJdbc().sql("SELECT unit_type FROM org_unit WHERE id = ?").param(INST_A1).query(String.class).single())
                .isEqualTo("COLLEGE");
        assertThat(ownerJdbc().sql("SELECT count(*) FROM role_assignment WHERE scope_type = 'INSTITUTION'").query(Long.class).single()).isZero();
        assertThat(ownerJdbc().sql("SELECT count(*) FROM role_assignment WHERE scope_type = 'ORG_UNIT' AND scope_id = ?")
                .param(INST_A1).query(Long.class).single()).isEqualTo(3);
        assertThat(ownerJdbc().sql("SELECT count(*) FROM org_unit_history WHERE org_unit_id = ? AND reason = 'Migrated from Phase 0 institution'")
                .param(INST_A1).query(Long.class).single()).isEqualTo(1);
        assertThat(ownerJdbc().sql("SELECT count(*) FROM role WHERE 'institution.read' = ANY(permissions) OR 'institution.manage' = ANY(permissions)")
                .query(Long.class).single()).isZero();
        assertThat(ownerJdbc().sql("SELECT to_regclass('ivorovi.institution') IS NULL").query(Boolean.class).single()).isTrue();
        assertThat(ownerJdbc().sql("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_name = 'feature_flag_override' AND column_name = 'org_unit_id'""").query(Long.class).single()).isEqualTo(1);
    }
}
