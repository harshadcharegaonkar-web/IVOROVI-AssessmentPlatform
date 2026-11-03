package com.ivorovi.identity;

import static com.ivorovi.support.Fixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ivorovi.support.FakeIdentityProvisioner;
import com.ivorovi.support.Fixture;
import com.ivorovi.support.IntegrationTest;
import com.ivorovi.support.RecordingEmailSender;
import com.ivorovi.support.TestTokens;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Increment 1.2: users, invitations, staff, organizational and role assignments. Each test runs in a fresh tenant
 * with its own organization administrator and a small hierarchy (university → two colleges).
 */
class UserManagementIT extends IntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID ORG_ADMIN = UUID.fromString("01920000-0000-7000-8000-000000000002");
    private static final UUID INSTITUTION_ADMIN = UUID.fromString("01920000-0000-7000-8000-000000000003");
    private static final UUID FACULTY = UUID.fromString("01920000-0000-7000-8000-000000000007");

    @Autowired FakeIdentityProvisioner idp;
    @Autowired RecordingEmailSender mail;

    private UUID tenant;
    private Fixture.Caller admin;
    private UUID adminId;
    private UUID university;
    private UUID collegeA;
    private UUID collegeB;

    @BeforeEach
    void freshTenant() {
        idp.unavailable = false;
        tenant = UUID.randomUUID();
        ownerJdbc().sql("INSERT INTO tenant (id, code, name, status) VALUES (?, ?, 'People test org', 'ACTIVE')")
                .params(tenant, "people-" + tenant.toString().substring(0, 8)).update();
        university = unit("UNIVERSITY", null, "U");
        collegeA = unit("COLLEGE", university, "A");
        collegeB = unit("COLLEGE", university, "B");
        adminId = UUID.randomUUID();
        admin = account(adminId, "admin@people.test", ORG_ADMIN, null);
    }

    // ------------------------------------------------------------------------------------------------ fixtures

    private UUID unit(String type, UUID parent, String code) {
        UUID id = UUID.randomUUID();
        String label = id.toString().replace("-", "");
        String path = parent == null ? label
                : ownerJdbc().sql("SELECT path::text FROM org_unit WHERE id = ?").param(parent).query(String.class).single() + "." + label;
        ownerJdbc().sql("INSERT INTO org_unit (id, tenant_id, parent_id, unit_type, code, name, path) VALUES (?, ?, ?, ?, ?, ?, CAST(? AS ltree))")
                .params(id, tenant, parent, type, code, "Unit " + code, path).update();
        return id;
    }

    /** An active account with one role assignment (TENANT scope when unit is null) and a matching org assignment. */
    private Fixture.Caller account(UUID id, String email, UUID role, UUID unit) {
        String subject = "sub-" + id;
        ownerJdbc().sql("INSERT INTO user_account (id, tenant_id, external_subject, email, display_name, given_name) VALUES (?, ?, ?, ?, ?, ?)")
                .params(id, tenant, subject, email, "User " + email, "User").update();
        ownerJdbc().sql("INSERT INTO role_assignment (id, tenant_id, user_account_id, role_id, scope_type, scope_id) VALUES (?, ?, ?, ?, ?, ?)")
                .params(UUID.randomUUID(), tenant, id, role, unit == null ? "TENANT" : "ORG_UNIT", unit == null ? tenant : unit).update();
        ownerJdbc().sql("INSERT INTO org_assignment (id, tenant_id, user_account_id, org_unit_id, is_primary) VALUES (?, ?, ?, ?, true)")
                .params(UUID.randomUUID(), tenant, id, unit).update();
        return new Fixture.Caller(subject, tenant);
    }

    private ResultActions as(Fixture.Caller c, MockHttpServletRequestBuilder r) throws Exception {
        return mvc.perform(r.header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.forCaller(c)));
    }

    private ResultActions anonymous(MockHttpServletRequestBuilder r) throws Exception {
        return mvc.perform(r);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder r, String body) {
        return r.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private UUID createUser(Fixture.Caller c, String email, UUID unit) throws Exception {
        String body = "{\"email\":\"" + email + "\",\"givenName\":\"Priya\",\"familyName\":\"Nair\""
                + (unit == null ? "" : ",\"orgUnitId\":\"" + unit + "\"") + "}";
        JsonNode n = JSON.readTree(as(c, json(post("/api/v1/users"), body).header("Idempotency-Key", "k-" + UUID.randomUUID()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return UUID.fromString(n.path("user").path("user").path("id").asString());
    }

    private long version(UUID userId) {
        return ownerJdbc().sql("SELECT version FROM user_account WHERE id = ?").param(userId).query(Long.class).single();
    }

    private ResultActions accept(String token, String password) throws Exception {
        return anonymous(json(post("/api/v1/public/invitations/accept"),
                "{\"token\":\"" + token + "\"" + (password == null ? "" : ",\"password\":\"" + password + "\"") + "}"));
    }

    private Fixture.Caller callerFor(UUID userId) {
        String subject = ownerJdbc().sql("SELECT external_subject FROM user_account WHERE id = ?").param(userId).query(String.class).single();
        return new Fixture.Caller(subject, tenant);
    }

    private ResultActions changeStatus(Fixture.Caller c, UUID userId, String action, String reason) throws Exception {
        return as(c, json(post("/api/v1/users/" + userId + "/status"),
                "{\"action\":\"" + action + "\",\"version\":" + version(userId) + (reason == null ? "" : ",\"reason\":\"" + reason + "\"") + "}"));
    }

    private ResultActions assignRole(Fixture.Caller c, UUID userId, UUID role, UUID unit) throws Exception {
        return as(c, json(post("/api/v1/users/" + userId + "/role-assignments"),
                "{\"roleId\":\"" + role + "\"" + (unit == null ? "" : ",\"orgUnitId\":\"" + unit + "\"") + "}"));
    }

    private UUID activeUser(String email, UUID unit) throws Exception {
        UUID id = createUser(admin, email, unit);
        accept(mail.lastTokenFor(email), "correct-horse-battery").andExpect(status().isNoContent());
        return id;
    }

    // ------------------------------------------------------------------------------------------------- tests

    @Nested
    class Invitations {

        @Test
        void createInviteAcceptAndSignIn() throws Exception {
            UUID id = createUser(admin, "  Priya.Nair@People.TEST ", collegeA);
            as(admin, get("/api/v1/users/" + id)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.user.email").value("priya.nair@people.test"))            // normalised
                    .andExpect(jsonPath("$.user.status").value("INVITED"))
                    .andExpect(jsonPath("$.user.invitationStatus").value("PENDING"))
                    .andExpect(jsonPath("$.invitation.deliveryStatus").value(Matchers.oneOf("SENT", "FAILED")));
            String token = mail.lastTokenFor("priya.nair@people.test");
            assertThat(mail.sent.getLast().textBody()).contains("/invitation#token=" + token).contains("People test org");

            anonymous(json(post("/api/v1/public/invitations/lookup"), "{\"token\":\"" + token + "\"}")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.organizationName").value("People test org"))
                    .andExpect(jsonPath("$.email").value("priya.nair@people.test"))
                    .andExpect(jsonPath("$.existingLogin").value(false));
            accept(token, "short").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("weak_password"));
            accept(token, "correct-horse-battery").andExpect(status().isNoContent());
            accept(token, "correct-horse-battery").andExpect(status().isNotFound())                   // single use
                    .andExpect(jsonPath("$.code").value("invitation_invalid"));

            as(admin, get("/api/v1/users/" + id)).andExpect(jsonPath("$.user.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.user.invitationStatus").value("ACCEPTED"));
            String subject = callerFor(id).subject();
            assertThat(idp.byId.get(subject).tenantId()).isEqualTo(tenant);
            assertThat(idp.byId.get(subject).email()).isEqualTo("priya.nair@people.test");
            as(callerFor(id), get("/api/v1/me")).andExpect(status().isOk()).andExpect(jsonPath("$.email").value("priya.nair@people.test"));
        }

        @Test
        void tokensAreStoredOnlyAsHashesAndNeverAudited() throws Exception {
            UUID id = createUser(admin, "hash@people.test", null);
            String token = mail.lastTokenFor("hash@people.test");
            String stored = ownerJdbc().sql("SELECT token_hash FROM user_invitation WHERE user_account_id = ?").param(id).query(String.class).single();
            assertThat(stored).hasSize(64).isNotEqualTo(token).doesNotContain(token);
            assertThat(ownerJdbc().sql("SELECT count(*) FROM audit_event WHERE tenant_id = ? AND details::text LIKE ?")
                    .params(tenant, "%" + token + "%").query(Long.class).single()).isZero();
            assertThat(ownerJdbc().sql("SELECT count(*) FROM outbox_event WHERE tenant_id = ? AND payload::text LIKE ?")
                    .params(tenant, "%" + token + "%").query(Long.class).single()).isZero();
            assertThat(ownerJdbc().sql("SELECT count(*) FROM audit_event WHERE tenant_id = ? AND action = 'user.invitation.sent'")
                    .param(tenant).query(Long.class).single()).isEqualTo(1);
        }

        @Test
        void expiredRevokedAndReplacedTokensCannotBeAccepted() throws Exception {
            UUID id = createUser(admin, "expiry@people.test", collegeA);
            String first = mail.lastTokenFor("expiry@people.test");
            // re-send replaces the token: the old link stops working
            as(admin, post("/api/v1/users/" + id + "/invitation")).andExpect(status().isOk()).andExpect(jsonPath("$.sendCount").value(2));
            String second = mail.lastTokenFor("expiry@people.test");
            assertThat(second).isNotEqualTo(first);
            accept(first, "correct-horse-battery").andExpect(status().isNotFound());
            // expired
            ownerJdbc().sql("UPDATE user_invitation SET expires_at = now() - interval '1 minute' WHERE user_account_id = ?").param(id).update();
            accept(second, "correct-horse-battery").andExpect(status().isNotFound());
            as(admin, get("/api/v1/users/" + id)).andExpect(jsonPath("$.user.invitationStatus").value("EXPIRED"));
            // a new invitation after expiry works; revoking it makes it unusable
            as(admin, post("/api/v1/users/" + id + "/invitation")).andExpect(status().isOk());
            String third = mail.lastTokenFor("expiry@people.test");
            UUID invitation = ownerJdbc().sql("SELECT id FROM user_invitation WHERE user_account_id = ? AND status = 'PENDING'").param(id)
                    .query(UUID.class).single();
            as(admin, post("/api/v1/invitations/" + invitation + "/revoke")).andExpect(status().isNoContent());
            accept(third, "correct-horse-battery").andExpect(status().isNotFound());
            as(admin, get("/api/v1/invitations").param("status", "REVOKED")).andExpect(jsonPath("$.totalElements").value(1));
            as(admin, get("/api/v1/invitations").param("status", "EXPIRED")).andExpect(jsonPath("$.totalElements").value(1));
            assertThat(idp.byId.values().stream().noneMatch(u -> u.email().equals("expiry@people.test"))).isTrue();
        }

        @Test
        void duplicateAndConflictingInvitationsAreRefused() throws Exception {
            UUID id = createUser(admin, "dup@people.test", null);
            as(admin, json(post("/api/v1/users"), "{\"email\":\"DUP@people.test\",\"givenName\":\"X\"}")
                    .header("Idempotency-Key", "k-" + UUID.randomUUID())).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("email_in_use"));
            // only one open invitation per user (re-send rotates it instead of adding one)
            as(admin, post("/api/v1/users/" + id + "/invitation")).andExpect(status().isOk());
            assertThat(ownerJdbc().sql("SELECT count(*) FROM user_invitation WHERE user_account_id = ? AND status = 'PENDING'")
                    .param(id).query(Long.class).single()).isEqualTo(1);
            // a login that belongs to another organization cannot be claimed
            idp.addExisting("elsewhere-" + UUID.randomUUID(), "dup@people.test", TENANT_B);
            accept(mail.lastTokenFor("dup@people.test"), "correct-horse-battery").andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("email_registered_elsewhere"));
            as(admin, get("/api/v1/users/" + id)).andExpect(jsonPath("$.user.status").value("INVITED"));
        }

        @Test
        void identityProviderOutageChangesNothing() throws Exception {
            UUID id = createUser(admin, "outage@people.test", null);
            idp.unavailable = true;
            accept(mail.lastTokenFor("outage@people.test"), "correct-horse-battery").andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.code").value("identity_provider_unavailable"));
            idp.unavailable = false;
            as(admin, get("/api/v1/users/" + id)).andExpect(jsonPath("$.user.status").value("INVITED"))
                    .andExpect(jsonPath("$.user.invitationStatus").value("PENDING"));
            accept(mail.lastTokenFor("outage@people.test"), "correct-horse-battery").andExpect(status().isNoContent());
        }
    }

    @Nested
    class Lifecycle {

        @Test
        void suspendReactivateDeactivateActivate() throws Exception {
            UUID id = activeUser("life@people.test", collegeA);
            Fixture.Caller user = callerFor(id);
            as(user, get("/api/v1/me")).andExpect(status().isOk());                          // principal now cached
            changeStatus(admin, id, "SUSPEND", null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("reason_required"));
            changeStatus(admin, id, "SUSPEND", "Investigation").andExpect(status().isOk()).andExpect(jsonPath("$.user.status").value("SUSPENDED"));
            as(user, get("/api/v1/me")).andExpect(status().isForbidden());                  // cache evicted, access blocked
            assertThat(idp.byId.get(user.subject()).enabled()).isFalse();                   // login disabled, sessions ended
            changeStatus(admin, id, "SUSPEND", "again").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("invalid_status_transition"));
            changeStatus(admin, id, "REACTIVATE", null).andExpect(status().isOk()).andExpect(jsonPath("$.user.status").value("ACTIVE"));
            as(user, get("/api/v1/me")).andExpect(status().isOk());
            assertThat(idp.byId.get(user.subject()).enabled()).isTrue();
            changeStatus(admin, id, "DEACTIVATE", "Left the college").andExpect(status().isOk()).andExpect(jsonPath("$.user.status").value("DEACTIVATED"));
            as(user, get("/api/v1/me")).andExpect(status().isForbidden());
            changeStatus(admin, id, "ACTIVATE", null).andExpect(status().isOk()).andExpect(jsonPath("$.user.status").value("ACTIVE"));
            assertThat(ownerJdbc().sql("SELECT array_agg(action ORDER BY seq) FROM audit_event WHERE tenant_id = ? AND resource_id = ? AND action LIKE 'user.%d'")
                    .params(tenant, id.toString()).query(String.class).single())
                    .contains("user.suspended", "user.reactivated", "user.deactivated", "user.activated");
        }

        @Test
        void deactivatingAnInvitedUserRevokesTheInvitation() throws Exception {
            UUID id = createUser(admin, "gone@people.test", null);
            String token = mail.lastTokenFor("gone@people.test");
            changeStatus(admin, id, "DEACTIVATE", "Offer withdrawn").andExpect(status().isOk());
            accept(token, "correct-horse-battery").andExpect(status().isNotFound());
            changeStatus(admin, id, "ACTIVATE", null).andExpect(jsonPath("$.user.status").value("INVITED"));
        }

        @Test
        void selfChangesAndTheLastOrganizationAdminAreProtected() throws Exception {
            changeStatus(admin, adminId, "SUSPEND", "x").andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("self_modification_forbidden"));
            UUID other = activeUser("second-admin@people.test", null);
            changeStatus(callerFor(other), adminId, "DEACTIVATE", "x").andExpect(status().isForbidden());   // no role yet
            assignRole(admin, other, ORG_ADMIN, null).andExpect(status().isCreated());
            changeStatus(callerFor(other), adminId, "DEACTIVATE", "Handover").andExpect(status().isOk());
            // "other" is now the last active organization administrator
            UUID third = createUser(callerFor(other), "third@people.test", null);       // the first admin is deactivated now
            accept(mail.lastTokenFor("third@people.test"), "correct-horse-battery").andExpect(status().isNoContent());
            ownerJdbc().sql("INSERT INTO role_assignment (id, tenant_id, user_account_id, role_id, scope_type, scope_id) VALUES (?, ?, ?, ?, 'TENANT', ?)")
                    .params(UUID.randomUUID(), tenant, third, INSTITUTION_ADMIN, tenant).update();
            changeStatus(callerFor(third), other, "SUSPEND", "x").andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("last_organization_admin"));
            String assignment = ownerJdbc().sql("SELECT ra.id::text FROM role_assignment ra WHERE ra.user_account_id = ? AND ra.role_id = ?")
                    .params(other, ORG_ADMIN).query(String.class).single();
            as(callerFor(third), json(post("/api/v1/users/" + other + "/role-assignments/" + assignment + "/revoke"), "{\"reason\":\"x\"}"))
                    .andExpect(status().isForbidden());          // revoking ORG_ADMIN needs ORG_ADMIN's permissions
        }

        @Test
        void profileEditsAreVersionedAndEmailLocksAfterSignIn() throws Exception {
            UUID id = activeUser("edit@people.test", collegeA);
            as(admin, json(patch("/api/v1/users/" + id), "{\"version\":" + version(id) + ",\"givenName\":\"Priyanka\",\"phone\":\"+91 98765 43210\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.user.displayName").value("Priyanka Nair"));
            as(admin, json(patch("/api/v1/users/" + id), "{\"version\":0,\"givenName\":\"Lost\"}")).andExpect(status().isConflict());
            as(admin, json(patch("/api/v1/users/" + id), "{\"version\":" + version(id) + ",\"email\":\"new@people.test\"}"))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("email_locked"));
        }
    }

    @Nested
    class Staff {

        @Test
        void staffProfilesExistWithoutLoginsAndCanBeLinked() throws Exception {
            String body = "{\"staffNo\":\"EMP-001\",\"givenName\":\"Ravi\",\"familyName\":\"Kumar\",\"category\":\"FACULTY\","
                    + "\"designation\":\"Assistant Professor\",\"joinedOn\":\"2024-07-01\",\"orgUnitId\":\"" + collegeA + "\"}";
            JsonNode created = JSON.readTree(as(admin, json(post("/api/v1/staff"), body).header("Idempotency-Key", "k-" + UUID.randomUUID()))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.staff.userId").doesNotExist())
                    .andExpect(jsonPath("$.staff.primaryUnitName").value("Unit A"))
                    .andReturn().getResponse().getContentAsString());
            UUID staff = UUID.fromString(created.path("staff").path("id").asString());
            as(admin, json(post("/api/v1/staff"), body.replace("EMP-001", "emp-001")).header("Idempotency-Key", "k-" + UUID.randomUUID()))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("staff_no_in_use"));
            as(admin, get("/api/v1/staff").param("linked", "false")).andExpect(jsonPath("$.totalElements").value(1));

            UUID user = activeUser("ravi@people.test", collegeA);
            as(admin, json(post("/api/v1/staff/" + staff + "/link"), "{\"userId\":\"" + user + "\"}")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.staff.userEmail").value("ravi@people.test"));
            as(admin, get("/api/v1/users/" + user)).andExpect(jsonPath("$.staffProfile.staffNo").value("EMP-001"));
            UUID second = UUID.fromString(JSON.readTree(as(admin, json(post("/api/v1/staff"), body.replace("EMP-001", "EMP-002"))
                    .header("Idempotency-Key", "k-" + UUID.randomUUID())).andReturn().getResponse().getContentAsString())
                    .path("staff").path("id").asString());
            as(admin, json(post("/api/v1/staff/" + second + "/link"), "{\"userId\":\"" + user + "\"}")).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("user_has_profile"));
            as(admin, post("/api/v1/staff/" + staff + "/unlink")).andExpect(status().isOk()).andExpect(jsonPath("$.staff.userId").doesNotExist());
            long version = ownerJdbc().sql("SELECT version FROM staff_profile WHERE id = ?").param(staff).query(Long.class).single();
            as(admin, json(put("/api/v1/staff/" + staff), body.replace("}", ",\"employmentStatus\":\"ON_LEAVE\",\"version\":" + version + "}")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.staff.employmentStatus").value("ON_LEAVE"));
        }
    }

    @Nested
    class Assignments {

        @Test
        void multipleOrganizationalAssignmentsDriveVisibility() throws Exception {
            UUID user = activeUser("multi@people.test", collegeA);
            Fixture.Caller adminA = account(UUID.randomUUID(), "admin-a@people.test", INSTITUTION_ADMIN, collegeA);
            Fixture.Caller adminB = account(UUID.randomUUID(), "admin-b@people.test", INSTITUTION_ADMIN, collegeB);
            as(adminB, get("/api/v1/users/" + user)).andExpect(status().isForbidden());
            as(admin, json(post("/api/v1/users/" + user + "/org-assignments"), "{\"orgUnitId\":\"" + collegeB + "\",\"title\":\"Visiting\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.orgUnitName").value("Unit B"));
            as(admin, get("/api/v1/users/" + user + "/org-assignments")).andExpect(jsonPath("$.length()").value(2));
            as(adminB, get("/api/v1/users/" + user)).andExpect(status().isOk())        // now visible to B's admin
                    .andExpect(jsonPath("$.canManage").value(false));                     // but A is outside B's scope
            as(adminA, get("/api/v1/users/" + user)).andExpect(jsonPath("$.canManage").value(false));
            changeStatus(adminA, user, "SUSPEND", "x").andExpect(status().isForbidden());
            as(admin, get("/api/v1/users").param("orgUnitId", collegeB.toString())).andExpect(jsonPath("$.items[*].email", Matchers.hasItem("multi@people.test")));
            String primary = ownerJdbc().sql("SELECT id::text FROM org_assignment WHERE user_account_id = ? AND is_primary").param(user).query(String.class).single();
            String other = ownerJdbc().sql("SELECT id::text FROM org_assignment WHERE user_account_id = ? AND NOT is_primary").param(user).query(String.class).single();
            as(admin, post("/api/v1/users/" + user + "/org-assignments/" + other + "/primary")).andExpect(status().isNoContent());
            as(admin, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/users/" + user + "/org-assignments/" + primary))
                    .andExpect(status().isNoContent());
            as(admin, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/users/" + user + "/org-assignments/" + other))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("last_assignment"));
        }

        @Test
        void roleAssignmentsRespectScopeAndEffectivePermissionsFollow() throws Exception {
            UUID user = activeUser("scoped@people.test", collegeA);
            Fixture.Caller u = callerFor(user);
            as(u, get("/api/v1/users")).andExpect(status().isForbidden());
            assignRole(admin, user, INSTITUTION_ADMIN, collegeA).andExpect(status().isCreated())
                    .andExpect(jsonPath("$.scopeName").value("Unit A"));
            as(admin, get("/api/v1/users/" + user + "/access")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.permissions[?(@.permission == 'user.manage')].scopes[0].scopeName").value("Unit A"))
                    .andExpect(jsonPath("$.permissions[?(@.permission == 'tenant.settings.manage')]").isEmpty());
            as(u, get("/api/v1/users")).andExpect(status().isOk());                      // cache evicted on assignment
            as(u, get("/api/v1/org-units/" + collegeA)).andExpect(status().isOk());
            as(u, get("/api/v1/org-units/" + collegeB)).andExpect(status().isForbidden());
            assignRole(admin, user, FACULTY, collegeA).andExpect(status().isCreated());
            assignRole(admin, user, FACULTY, collegeA).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("role_already_assigned"));
            // revoke: access ends on the next request, history kept with the reason
            String ra = ownerJdbc().sql("SELECT ra.id::text FROM role_assignment ra WHERE ra.user_account_id = ? AND ra.role_id = ?")
                    .params(user, INSTITUTION_ADMIN).query(String.class).single();
            as(admin, json(post("/api/v1/users/" + user + "/role-assignments/" + ra + "/revoke"), "{\"reason\":\"Term ended\"}"))
                    .andExpect(status().isNoContent());
            as(u, get("/api/v1/users")).andExpect(status().isForbidden());
            as(admin, get("/api/v1/users/" + user + "/role-assignments"))
                    .andExpect(jsonPath("$[?(@.roleCode == 'INSTITUTION_ADMIN')].revocationReason").value("Term ended"))
                    .andExpect(jsonPath("$[?(@.roleCode == 'INSTITUTION_ADMIN')].active").value(false));
        }

        @Test
        void privilegeEscalationIsPreventedAndAudited() throws Exception {
            UUID scopedId = UUID.randomUUID();
            Fixture.Caller scoped = account(scopedId, "inst@people.test", INSTITUTION_ADMIN, collegeA);
            UUID target = activeUser("target@people.test", collegeA);
            assignRole(scoped, target, FACULTY, collegeA).andExpect(status().isCreated());              // within own powers
            assignRole(scoped, target, ORG_ADMIN, null).andExpect(status().isForbidden());              // tenant scope
            assignRole(scoped, target, ORG_ADMIN, collegeA).andExpect(status().isForbidden())           // more permissions than held
                    .andExpect(jsonPath("$.code").value("privilege_escalation"));
            assignRole(scoped, target, FACULTY, collegeB).andExpect(status().isForbidden());           // outside scope
            assignRole(scoped, scopedId, INSTITUTION_ADMIN, university).andExpect(status().isForbidden()); // self
            as(scoped, get("/api/v1/roles/assignable")).andExpect(status().isOk())
                    .andExpect(jsonPath("$[*].code", Matchers.not(Matchers.hasItem("PLATFORM_SUPER_ADMIN"))));
            UUID superAdmin = ownerJdbc().sql("SELECT id FROM role WHERE code = 'PLATFORM_SUPER_ADMIN'").query(UUID.class).single();
            assignRole(admin, target, superAdmin, null).andExpect(status().isUnprocessableContent());
            assertThat(ownerJdbc().sql("SELECT count(*) FROM audit_event WHERE tenant_id = ? AND outcome = 'DENIED' AND action IN "
                    + "('role.assignment_denied', 'role.self_assignment_denied', 'security.access_denied')").param(tenant)
                    .query(Long.class).single()).isGreaterThanOrEqualTo(4);
        }
    }

    @Nested
    class Isolation {

        @Test
        void otherOrganizationsCannotSeeOrChangeUsersOrStaff() throws Exception {
            UUID user = activeUser("iso@people.test", collegeA);
            as(BELA_ORG_ADMIN_B, get("/api/v1/users/" + user)).andExpect(status().isNotFound());
            as(BELA_ORG_ADMIN_B, json(post("/api/v1/users/" + user + "/status"), "{\"action\":\"SUSPEND\",\"version\":0,\"reason\":\"x\"}"))
                    .andExpect(status().isNotFound());
            as(BELA_ORG_ADMIN_B, json(post("/api/v1/users/" + user + "/role-assignments"), "{\"roleId\":\"" + FACULTY + "\"}"))
                    .andExpect(status().isNotFound());
            as(BELA_ORG_ADMIN_B, get("/api/v1/users").param("q", "iso")).andExpect(jsonPath("$.totalElements").value(0));
            as(BELA_ORG_ADMIN_B, get("/api/v1/invitations")).andExpect(jsonPath("$.items[*].email", Matchers.not(Matchers.hasItem("iso@people.test"))));
            // a unit of another tenant cannot be used as an assignment target
            as(admin, json(post("/api/v1/users/" + user + "/org-assignments"), "{\"orgUnitId\":\"" + INST_B1 + "\"}"))
                    .andExpect(status().isUnprocessableContent());
            as(admin, json(post("/api/v1/users/" + user + "/role-assignments"), "{\"roleId\":\"" + FACULTY + "\",\"orgUnitId\":\"" + INST_B1 + "\"}"))
                    .andExpect(status().isUnprocessableContent());
        }

        @Test
        void publicEndpointsRevealNothingForUnknownTokens() throws Exception {
            anonymous(json(post("/api/v1/public/invitations/lookup"), "{\"token\":\"" + "x".repeat(43) + "\"}"))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("invitation_invalid"));
            anonymous(json(post("/api/v1/public/invitations/lookup"), "{\"token\":\"short\"}")).andExpect(status().isBadRequest());
            anonymous(get("/api/v1/users")).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void phase0AndIncrement11AccountsWereMigratedWithAssignments() {
        assertThat(ownerJdbc().sql("""
                SELECT count(*) FROM user_account u WHERE u.tenant_id IN (?, ?)
                  AND NOT EXISTS (SELECT 1 FROM org_assignment a WHERE a.user_account_id = u.id)""")
                .params(TENANT_A, TENANT_B).query(Long.class).single()).isZero();
        assertThat(ownerJdbc().sql("SELECT org_unit_id FROM org_assignment a JOIN user_account u ON u.id = a.user_account_id "
                + "WHERE u.email = 'ines.instadmin@aurora.test' AND a.is_primary").query(UUID.class).single())
                .isEqualTo(UUID.fromString("0192a000-0000-7000-8000-00000000a101"));
        assertThat(ownerJdbc().sql("SELECT count(*) FROM role_assignment WHERE revoked_at IS NOT NULL AND tenant_id IN (?, ?)")
                .params(TENANT_A, TENANT_B).query(Long.class).single()).isZero();
    }

    /** Increment 1.2 acceptance review (2026-11-03): security verification items 1–6. */
    @Nested
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    class SecurityVerification {

        private long activeOrgAdmins() {
            return ownerJdbc().sql("""
                    SELECT count(DISTINCT ra.user_account_id) FROM role_assignment ra JOIN user_account u ON u.id = ra.user_account_id
                    WHERE ra.tenant_id = ? AND ra.role_id = ? AND ra.scope_type = 'TENANT' AND ra.revoked_at IS NULL AND u.status = 'ACTIVE'""")
                    .params(tenant, ORG_ADMIN).query(Long.class).single();
        }

        private int[] concurrently(java.util.concurrent.Callable<Integer> a, java.util.concurrent.Callable<Integer> b) throws Exception {
            var start = new java.util.concurrent.CountDownLatch(1);
            var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                var fa = pool.submit(() -> { start.await(); return a.call(); });
                var fb = pool.submit(() -> { start.await(); return b.call(); });
                start.countDown();
                return new int[] {fa.get(30, java.util.concurrent.TimeUnit.SECONDS), fb.get(30, java.util.concurrent.TimeUnit.SECONDS)};
            } finally {
                pool.shutdownNow();
            }
        }

        @Test
        void item1_concurrentRemovalsCannotLeaveTheOrganizationWithoutAnAdministrator() throws Exception {
            for (int round = 0; round < 5; round++) {
                // start every round with exactly one administrator (the fixture admin, active)
                ownerJdbc().sql("UPDATE role_assignment SET revoked_at = now(), revoked_by = ? WHERE tenant_id = ? AND role_id = ? AND user_account_id <> ? AND revoked_at IS NULL")
                        .params(adminId, tenant, ORG_ADMIN, adminId).update();
                assertThat(activeOrgAdmins()).isEqualTo(1);
                UUID other = activeUser("co-admin-" + round + "@people.test", null);
                assignRole(admin, other, ORG_ADMIN, null).andExpect(status().isCreated());
                Fixture.Caller second = callerFor(other);
                Fixture.Caller first = admin;
                UUID firstId = adminId;
                long v1 = version(firstId), v2 = version(other);
                // the two administrators try to deactivate each other at the same moment
                int[] codes = concurrently(
                        () -> as(first, json(post("/api/v1/users/" + other + "/status"),
                                "{\"action\":\"DEACTIVATE\",\"version\":" + v2 + ",\"reason\":\"race\"}")).andReturn().getResponse().getStatus(),
                        () -> as(second, json(post("/api/v1/users/" + firstId + "/status"),
                                "{\"action\":\"DEACTIVATE\",\"version\":" + v1 + ",\"reason\":\"race\"}")).andReturn().getResponse().getStatus());
                assertThat(java.util.Arrays.stream(codes).filter(c -> c == 200).count()).as("exactly one succeeds: %s", java.util.Arrays.toString(codes)).isEqualTo(1);
                assertThat(activeOrgAdmins()).isEqualTo(1);
                // restore: whoever was deactivated is reactivated by the survivor
                Fixture.Caller survivor = codes[0] == 200 ? first : second;
                UUID loser = codes[0] == 200 ? other : firstId;
                changeStatus(survivor, loser, "ACTIVATE", null).andExpect(status().isOk());
            }
            // concurrent revocation of each other's ORG_ADMIN role
            UUID other = activeUser("co-admin-revoke@people.test", null);
            assignRole(admin, other, ORG_ADMIN, null).andExpect(status().isCreated());
            ownerJdbc().sql("UPDATE role_assignment SET revoked_at = now(), revoked_by = ? WHERE tenant_id = ? AND role_id = ? AND user_account_id <> ? AND user_account_id <> ?")
                    .params(adminId, tenant, ORG_ADMIN, adminId, other).update();       // leave exactly two administrators
            String raAdmin = ownerJdbc().sql("SELECT id::text FROM role_assignment WHERE user_account_id = ? AND role_id = ? AND revoked_at IS NULL")
                    .params(adminId, ORG_ADMIN).query(String.class).single();
            String raOther = ownerJdbc().sql("SELECT id::text FROM role_assignment WHERE user_account_id = ? AND role_id = ? AND revoked_at IS NULL")
                    .params(other, ORG_ADMIN).query(String.class).single();
            Fixture.Caller second = callerFor(other);
            int[] codes = concurrently(
                    () -> as(admin, json(post("/api/v1/users/" + other + "/role-assignments/" + raOther + "/revoke"), "{\"reason\":\"race\"}"))
                            .andReturn().getResponse().getStatus(),
                    () -> as(second, json(post("/api/v1/users/" + adminId + "/role-assignments/" + raAdmin + "/revoke"), "{\"reason\":\"race\"}"))
                            .andReturn().getResponse().getStatus());
            assertThat(java.util.Arrays.stream(codes).filter(c -> c == 204).count()).as(java.util.Arrays.toString(codes)).isEqualTo(1);
            assertThat(activeOrgAdmins()).isEqualTo(1);
        }

        @Test
        void item2_administratorsCannotManagePeopleWhoseAssignmentsLeaveTheirScope() throws Exception {
            UUID user = activeUser("wide@people.test", collegeA);
            Fixture.Caller adminA = account(UUID.randomUUID(), "scope-a@people.test", INSTITUTION_ADMIN, collegeA);
            changeStatus(adminA, user, "SUSPEND", "in scope").andExpect(status().isOk());
            changeStatus(admin, user, "REACTIVATE", null).andExpect(status().isOk());
            // an assignment to college B, or a role there, takes the person outside A's sole authority
            as(admin, json(post("/api/v1/users/" + user + "/org-assignments"), "{\"orgUnitId\":\"" + collegeB + "\"}")).andExpect(status().isOk());
            for (var r : java.util.List.of(
                    json(post("/api/v1/users/" + user + "/status"), "{\"action\":\"SUSPEND\",\"version\":" + version(user) + ",\"reason\":\"x\"}"),
                    json(patch("/api/v1/users/" + user), "{\"version\":" + version(user) + ",\"givenName\":\"X\"}"),
                    json(post("/api/v1/users/" + user + "/org-assignments"), "{\"orgUnitId\":\"" + collegeA + "\",\"title\":\"x\"}"),
                    post("/api/v1/users/" + user + "/invitation"))) {
                as(adminA, r).andExpect(status().isForbidden());
            }
            UUID other = activeUser("role-elsewhere@people.test", collegeA);
            assignRole(admin, other, FACULTY, collegeB).andExpect(status().isCreated());
            changeStatus(adminA, other, "SUSPEND", "x").andExpect(status().isForbidden());
        }

        @Test
        void item3_tokensAndPasswordsNeverReachTheLogs(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
            UUID id = createUser(admin, "logs@people.test", collegeA);
            String first = mail.lastTokenFor("logs@people.test");
            as(admin, post("/api/v1/users/" + id + "/invitation")).andExpect(status().isOk());
            String second = mail.lastTokenFor("logs@people.test");
            accept(first, "first-secret-password").andExpect(status().isNotFound());         // replaced
            accept(second, "the-chosen-secret-password").andExpect(status().isNoContent());
            accept(second, "the-chosen-secret-password").andExpect(status().isNotFound());   // single use
            ownerJdbc().sql("UPDATE user_invitation SET status = 'PENDING', expires_at = now() - interval '1 second' WHERE user_account_id = ?").param(id).update();
            anonymous(json(post("/api/v1/public/invitations/lookup"), "{\"token\":\"" + second + "\"}")).andExpect(status().isNotFound()); // expired
            assertThat(output.getAll()).doesNotContain(first).doesNotContain(second)
                    .doesNotContain("first-secret-password").doesNotContain("the-chosen-secret-password");
            assertThat(ownerJdbc().sql("SELECT count(*) FROM user_invitation WHERE token_hash IN (?, ?)").params(first, second).query(Long.class).single()).isZero();
        }

        @Test
        void item4_provisioningServiceAccountIsLeastPrivilege() throws Exception {
            JsonNode realm = JSON.readTree(java.nio.file.Files.readString(java.nio.file.Path.of("../../deploy/compose/keycloak/realm-ivorovi.json")));
            JsonNode client = null;
            for (JsonNode c : realm.path("clients")) if (c.path("clientId").asString().equals("ivorovi-provisioner")) client = c;
            assertThat(client).isNotNull();
            assertThat(client.path("publicClient").asBoolean()).isFalse();
            assertThat(client.path("serviceAccountsEnabled").asBoolean()).isTrue();
            assertThat(client.path("standardFlowEnabled").asBoolean()).isFalse();
            assertThat(client.path("directAccessGrantsEnabled").asBoolean()).isFalse();
            assertThat(client.path("secret").asString()).isEqualTo("${KEYCLOAK_PROVISIONER_SECRET}");       // never a literal
            JsonNode sa = null;
            for (JsonNode u : realm.path("users")) if ("ivorovi-provisioner".equals(u.path("serviceAccountClientId").asString())) sa = u;
            assertThat(sa).isNotNull();
            java.util.List<String> roles = new java.util.ArrayList<>();
            sa.path("clientRoles").path("realm-management").forEach(r -> roles.add(r.asString()));
            assertThat(roles).containsExactlyInAnyOrder("manage-users", "view-users", "query-users");
            assertThat(sa.path("clientRoles").size()).isEqualTo(1);
            assertThat(sa.path("realmRoles").isMissingNode() || sa.path("realmRoles").isEmpty()).isTrue();
        }

        @Test
        void item5_revokedExpiredAndNotYetValidRolesGrantNothing() throws Exception {
            UUID user = activeUser("dated@people.test", collegeA);
            Fixture.Caller u = callerFor(user);
            // ends in 3 seconds: works now (principal cached), stops at the end time, not a cache lifetime later
            as(admin, json(post("/api/v1/users/" + user + "/role-assignments"), "{\"roleId\":\"" + INSTITUTION_ADMIN + "\",\"orgUnitId\":\""
                    + collegeA + "\",\"validTo\":\"" + java.time.Instant.now().plusSeconds(3) + "\"}")).andExpect(status().isCreated());
            as(u, get("/api/v1/users")).andExpect(status().isOk());
            Thread.sleep(3500);
            as(u, get("/api/v1/users")).andExpect(status().isForbidden());
            // starts in 3 seconds: denied now (cached), granted once it starts
            as(admin, json(post("/api/v1/users/" + user + "/role-assignments"), "{\"roleId\":\"" + INSTITUTION_ADMIN + "\",\"orgUnitId\":\""
                    + collegeA + "\",\"validFrom\":\"" + java.time.Instant.now().plusSeconds(3) + "\"}")).andExpect(status().isCreated());
            as(u, get("/api/v1/users")).andExpect(status().isForbidden());
            Thread.sleep(3500);
            as(u, get("/api/v1/users")).andExpect(status().isOk());
            // revoked: immediately
            String ra = ownerJdbc().sql("SELECT id::text FROM role_assignment WHERE user_account_id = ? AND revoked_at IS NULL AND valid_to IS NULL")
                    .param(user).query(String.class).single();
            as(admin, json(post("/api/v1/users/" + user + "/role-assignments/" + ra + "/revoke"), "{\"reason\":\"done\"}")).andExpect(status().isNoContent());
            as(u, get("/api/v1/users")).andExpect(status().isForbidden());
        }

        @Test
        void item6_lifecycleAndPermissionChangesAreAuditedWithActorAndTarget() throws Exception {
            UUID user = activeUser("audited@people.test", collegeA);
            assignRole(admin, user, FACULTY, collegeA).andExpect(status().isCreated());
            String ra = ownerJdbc().sql("SELECT id::text FROM role_assignment WHERE user_account_id = ?").param(user).query(String.class).single();
            as(admin, json(post("/api/v1/users/" + user + "/role-assignments/" + ra + "/revoke"), "{\"reason\":\"r\"}")).andExpect(status().isNoContent());
            changeStatus(admin, user, "SUSPEND", "s").andExpect(status().isOk());
            changeStatus(admin, user, "REACTIVATE", null).andExpect(status().isOk());
            changeStatus(admin, user, "DEACTIVATE", "d").andExpect(status().isOk());
            var rows = ownerJdbc().sql("SELECT action, actor_id, details::text FROM audit_event WHERE tenant_id = ? ORDER BY seq").param(tenant)
                    .query((rs, n) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3)}).list();
            java.util.List<String> actions = rows.stream().map(r -> r[0]).toList();
            assertThat(actions).contains("user.created", "user.invitation.sent", "user.invitation.accepted", "role.assigned", "role.revoked",
                    "user.suspended", "user.reactivated", "user.deactivated");
            for (String[] r : rows) {
                if (r[0].startsWith("role.") || r[0].startsWith("user.suspended") || r[0].startsWith("user.deactivated")) {
                    assertThat(r[1]).as(r[0] + " actor").isEqualTo(adminId.toString());
                }
            }
            assertThat(rows.stream().filter(r -> r[0].equals("role.revoked")).findFirst().orElseThrow()[2]).contains("\"reason\": \"r\"").contains("FACULTY");
            as(admin, get("/api/v1/audit-events/verification")).andExpect(jsonPath("$.valid").value(true));
        }
    }
}
