package com.ivorovi.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ivorovi.identity.internal.PrivilegedRoleBootstrap;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.support.IntegrationTest;
import com.ivorovi.support.TestOrg;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * P2-08: roles with question.approve or question.key.read are assigned only through four-eyes approval by an existing
 * holder at the scope (service and database), with live checks and invalidation; administrators keep assigning
 * non-privileged roles under the unchanged 1.3 ceiling; the first holder is bootstrapped by an operator only.
 */
class PrivilegedRoleAssignmentIT extends IntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired JdbcClient jdbc;
    @Autowired PrivilegedRoleBootstrap bootstrap;

    private TestOrg org;
    private UUID uni, college, otherCollege;
    private TestOrg.Account orgAdmin, instAdmin, controller, otherController, designer, target, target2;

    @BeforeEach
    void setUp() {
        org = new TestOrg(ownerJdbc(), mvc, "p208");
        uni = org.unit("UNIVERSITY", null, "U");
        college = org.unit("COLLEGE", uni, "C1");
        otherCollege = org.unit("COLLEGE", uni, "C2");
        orgAdmin = org.account("ORG_ADMIN", null);
        instAdmin = org.account("INSTITUTION_ADMIN", college);
        controller = org.account("EXAM_CONTROLLER", college);          // existing holder (fixture, schema owner)
        otherController = org.account("EXAM_CONTROLLER", otherCollege);
        designer = org.account("ASSESSMENT_DESIGNER", college);
        target = org.account("FACULTY", college);
        target2 = org.account("FACULTY", college);
    }

    private UUID roleId(String code) {
        return ownerJdbc().sql("SELECT id FROM role WHERE code = ? AND tenant_id IS NULL").param(code).query(UUID.class).single();
    }

    private ResultActions as(TestOrg.Account who, MockHttpServletRequestBuilder r) throws Exception {
        return mvc.perform(r.header(HttpHeaders.AUTHORIZATION, who.bearer()).header("Idempotency-Key", "k-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON));
    }

    private ResultActions assign(TestOrg.Account by, TestOrg.Account to, String role, UUID unit) throws Exception {
        return as(by, post("/api/v1/users/" + to.id() + "/role-assignments")
                .content("{\"roleId\":\"" + roleId(role) + "\"" + (unit == null ? "" : ",\"orgUnitId\":\"" + unit + "\"") + "}"));
    }

    private UUID pending(TestOrg.Account by, TestOrg.Account to, String role, UUID unit) throws Exception {
        String body = assign(by, to, role, unit).andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JSON.readTree(body).path("approvalRequestId").asString());
    }

    private ResultActions decide(TestOrg.Account by, UUID approval, boolean approve) throws Exception {
        return as(by, post("/api/v1/approvals/" + approval + "/decision").content("{\"approve\":" + approve + ",\"reason\":\"checked\"}"));
    }

    private long privilegedAssignments(TestOrg.Account to) {
        return ownerJdbc().sql("""
                SELECT count(*) FROM role_assignment ra JOIN role r ON r.id = ra.role_id
                WHERE ra.user_account_id = ? AND ra.revoked_at IS NULL AND r.permissions && ARRAY['question.approve', 'question.key.read']""")
                .param(to.id()).query(Long.class).single();
    }

    private long audits(String action) {
        return ownerJdbc().sql("SELECT count(*) FROM audit_event WHERE tenant_id = ? AND action = ?").params(org.tenant, action).query(Long.class).single();
    }

    private void alsoRole(TestOrg.Account a, String role, UUID unit) {
        ownerJdbc().sql("INSERT INTO role_assignment (id, tenant_id, user_account_id, role_id, scope_type, scope_id) VALUES (?, ?, ?, ?, ?, ?)")
                .params(UUID.randomUUID(), org.tenant, a.id(), roleId(role), unit == null ? "TENANT" : "ORG_UNIT", unit == null ? org.tenant : unit).update();
    }

    @Test
    void administratorsStillAssignFacultyAndAssessmentDesignerDirectly() throws Exception {
        assign(instAdmin, target, "ASSESSMENT_DESIGNER", college).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("ACTIVE"));
        assign(orgAdmin, target2, "FACULTY", otherCollege).andExpect(status().isCreated());
        // the 1.3 ceiling is unchanged for everything else: outside scope and roles beyond the admin's own permissions
        assign(instAdmin, target2, "FACULTY", otherCollege).andExpect(status().isForbidden());
        assign(instAdmin, target2, "ORG_ADMIN", college).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("privilege_escalation"));
    }

    @Test
    void examControllerNeedsAnIndependentHolderToApproveAndOnlyThenBecomesActive() throws Exception {
        UUID approval = pending(instAdmin, target, "EXAM_CONTROLLER", college);
        assertThat(privilegedAssignments(target)).as("nothing assigned before approval").isZero();
        assertThat(audits("role.privileged_assignment_requested")).isEqualTo(1);
        // the holder sees it as decidable; others do not
        assertThat(as(controller, get("/api/v1/approvals/" + approval)).andReturn().getResponse().getContentAsString()).contains("\"canDecide\":true");
        assertThat(as(designer, get("/api/v1/approvals/" + approval)).andReturn().getResponse().getContentAsString()).doesNotContain("\"canDecide\":true");
        decide(controller, approval, true).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        assertThat(privilegedAssignments(target)).isEqualTo(1);
        assertThat(ownerJdbc().sql("SELECT approval_request_id FROM role_assignment WHERE user_account_id = ? AND approval_request_id IS NOT NULL")
                .param(target.id()).query(UUID.class).single()).isEqualTo(approval);
        assertThat(audits("approval.approved")).isEqualTo(1);
        assertThat(audits("role.privileged_assignment_activated")).isEqualTo(1);
        // rejection path: nothing assigned, audited
        UUID second = pending(orgAdmin, target2, "EXAM_CONTROLLER", college);
        decide(controller, second, false).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
        assertThat(privilegedAssignments(target2)).isZero();
        assertThat(audits("approval.rejected")).isEqualTo(1);
        // administrators can still remove an Exam Controller (revocation never grants anything)
        UUID assignment = ownerJdbc().sql("SELECT id FROM role_assignment WHERE approval_request_id = ?").param(approval).query(UUID.class).single();
        as(instAdmin, post("/api/v1/users/" + target.id() + "/role-assignments/" + assignment + "/revoke").content("{\"reason\":\"rotation\"}"))
                .andExpect(status().isNoContent());
        assertThat(privilegedAssignments(target)).isZero();
    }

    @Test
    void selfApprovalAndApprovalOfOwnElevationAreRejected() throws Exception {
        alsoRole(orgAdmin, "EXAM_CONTROLLER", null);                 // a requester who also holds the privileged permissions
        UUID approval = pending(orgAdmin, target, "EXAM_CONTROLLER", college);
        decide(orgAdmin, approval, true).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("four_eyes_required"));
        assertThat(audits("approval.self_decision_denied")).isEqualTo(1);
        // the person being elevated cannot approve it either, even when they already hold the permissions elsewhere
        alsoRole(target2, "EXAM_CONTROLLER", null);
        UUID own = pending(instAdmin, target2, "EXAM_CONTROLLER", college);
        decide(target2, own, true).andExpect(status().isForbidden());
        assertThat(privilegedAssignments(target)).isZero();
        assertThat(privilegedAssignments(target2)).isEqualTo(1);            // only the fixture's organization-wide grant
        // and the database refuses a self-decision outright
        assertThatThrownBy(() -> ownerJdbc().sql("UPDATE approval_request SET status = 'APPROVED', decided_by = requested_by, decided_at = now() WHERE id = ?")
                .param(approval).update()).hasMessageContaining("approval_four_eyes");
    }

    @Test
    void unauthorizedAndCrossInstitutionApproversAreRejected() throws Exception {
        UUID approval = pending(instAdmin, target, "EXAM_CONTROLLER", college);
        decide(designer, approval, true).andExpect(status().isForbidden());      // no question.approve
        decide(orgAdmin, approval, true).andExpect(status().isForbidden());      // administrators do not hold it
        decide(otherController, approval, true).andExpect(status().isForbidden()); // holds it, but at another institution
        assertThat(privilegedAssignments(target)).isZero();
        assertThat(audits("approval.decision_denied") + audits("security.access_denied")).isGreaterThanOrEqualTo(3);
        // cross-institution request: an institution admin cannot even ask outside their scope
        assign(instAdmin, target2, "EXAM_CONTROLLER", otherCollege).andExpect(status().isForbidden());
        decide(controller, approval, true).andExpect(status().isOk());
    }

    @Test
    void revokedPermissionsInvalidatePendingApprovals() throws Exception {
        // approver revoked before deciding: refused
        UUID a1 = pending(instAdmin, target, "EXAM_CONTROLLER", college);
        ownerJdbc().sql("UPDATE role_assignment SET revoked_at = now() WHERE user_account_id = ?").param(controller.id()).update();
        decide(controller, a1, true).andExpect(status().isForbidden());
        assertThat(privilegedAssignments(target)).isZero();
        // requester revoked while pending: the approval cannot take effect and is withdrawn
        TestOrg.Account approver = org.account("EXAM_CONTROLLER", uni);
        UUID a2 = pending(instAdmin, target2, "EXAM_CONTROLLER", college);
        ownerJdbc().sql("UPDATE role_assignment SET revoked_at = now() WHERE user_account_id = ?").param(instAdmin.id()).update();
        decide(approver, a2, true).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("approval_invalidated"));
        assertThat(ownerJdbc().sql("SELECT status FROM approval_request WHERE id = ?").param(a2).query(String.class).single()).isEqualTo("CANCELLED");
        assertThat(privilegedAssignments(target2)).isZero();
        assertThat(audits("approval.invalidated")).isEqualTo(1);
        // role changed after the request (permissions differ from what the approver was shown): refused
        UUID custom = UUID.randomUUID();
        ownerJdbc().sql("INSERT INTO role (id, tenant_id, code, name, permissions, system_role, status) VALUES (?, ?, 'KEYREADER', 'Key reader', ARRAY['question.read','question.key.read'], false, 'ACTIVE')")
                .params(custom, org.tenant).update();
        TestOrg.Account target3 = org.account("FACULTY", college);   // one pending privileged request per person
        String body = as(orgAdmin, post("/api/v1/users/" + target3.id() + "/role-assignments").content("{\"roleId\":\"" + custom + "\",\"orgUnitId\":\"" + college + "\"}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        UUID a3 = UUID.fromString(JSON.readTree(body).path("approvalRequestId").asString());
        ownerJdbc().sql("UPDATE role SET permissions = ARRAY['question.read','question.key.read','question.approve'] WHERE id = ?").param(custom).update();
        decide(approver, a3, true).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("subject_changed"));
    }

    @Test
    void directDatabaseWritesCannotBypassTheApproval() throws Exception {
        UUID ec = roleId("EXAM_CONTROLLER");
        assertThatThrownBy(() -> TenantContext.runInTenant(org.tenant, () -> jdbc.sql(
                "INSERT INTO role_assignment (id, tenant_id, user_account_id, role_id, scope_type, scope_id) VALUES (?, ?, ?, ?, 'ORG_UNIT', ?)")
                .params(UUID.randomUUID(), org.tenant, target.id(), ec, college).update())).hasMessageContaining("needs an approved request");
        // an approval of something else (here: rejected, other user) does not authorize it
        UUID approval = pending(instAdmin, target2, "EXAM_CONTROLLER", college);
        decide(controller, approval, false).andExpect(status().isOk());
        assertThatThrownBy(() -> TenantContext.runInTenant(org.tenant, () -> jdbc.sql(
                "INSERT INTO role_assignment (id, tenant_id, user_account_id, role_id, scope_type, scope_id, approval_request_id) VALUES (?, ?, ?, ?, 'ORG_UNIT', ?, ?)")
                .params(UUID.randomUUID(), org.tenant, target.id(), ec, college, approval).update())).hasMessageContaining("does not authorize");
        // bootstrap rows are refused for the application role
        assertThatThrownBy(() -> TenantContext.runInTenant(org.tenant, () -> jdbc.sql(
                "INSERT INTO role_assignment (id, tenant_id, user_account_id, role_id, scope_type, scope_id, bootstrap_reference) VALUES (?, ?, ?, ?, 'ORG_UNIT', ?, 'TICKET-1')")
                .params(UUID.randomUUID(), org.tenant, target.id(), ec, college).update())).hasMessageContaining("operator procedure");
        // an existing non-privileged assignment cannot be re-pointed to the privileged role
        UUID faculty = ownerJdbc().sql("SELECT id FROM role_assignment WHERE user_account_id = ?").param(target.id()).query(UUID.class).single();
        assertThatThrownBy(() -> TenantContext.runInTenant(org.tenant, () -> jdbc.sql("UPDATE role_assignment SET role_id = ? WHERE id = ?")
                .params(ec, faculty).update())).hasMessageContaining("re-pointed");
        // a custom role in use cannot gain a privileged permission through the application role (system roles are not updatable at all)
        UUID custom = UUID.randomUUID();
        ownerJdbc().sql("INSERT INTO role (id, tenant_id, code, name, permissions, system_role, status) VALUES (?, ?, 'READERS', 'Readers', ARRAY['question.read'], false, 'ACTIVE')")
                .params(custom, org.tenant).update();
        ownerJdbc().sql("INSERT INTO role_assignment (id, tenant_id, user_account_id, role_id, scope_type, scope_id) VALUES (?, ?, ?, ?, 'TENANT', ?)")
                .params(UUID.randomUUID(), org.tenant, target2.id(), custom, org.tenant).update();
        assertThatThrownBy(() -> TenantContext.runInTenant(org.tenant, () -> jdbc.sql(
                "UPDATE role SET permissions = permissions || ARRAY['question.key.read'] WHERE id = ?").param(custom).update()))
                .hasMessageContaining("cannot gain a privileged permission");
        assertThat(TenantContext.callInTenant(org.tenant, () -> jdbc.sql("UPDATE role SET permissions = permissions || ARRAY['question.key.read'] WHERE id = ?")
                .param(roleId("FACULTY")).update())).as("system roles are not writable by the application").isZero();
        assertThat(privilegedAssignments(target)).isZero();
    }

    @Test
    void customRolesInUseCannotGainPrivilegedPermissionsThroughTheApi() throws Exception {
        alsoRole(orgAdmin, "EXAM_CONTROLLER", null);
        String created = as(orgAdmin, post("/api/v1/roles").content("{\"name\":\"Readers " + UUID.randomUUID().toString().substring(0, 6)
                + "\",\"permissions\":[\"question.read\"]}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        JsonNode role = JSON.readTree(created).path("role");
        UUID id = UUID.fromString(role.path("id").asString());
        as(orgAdmin, post("/api/v1/users/" + target.id() + "/role-assignments").content("{\"roleId\":\"" + id + "\"}")).andExpect(status().isCreated());
        as(orgAdmin, patch("/api/v1/roles/" + id).content("{\"permissions\":[\"question.read\",\"question.key.read\"],\"version\":" + role.path("version").asLong() + "}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("privileged_role_in_use"));
    }

    @Test
    void operatorBootstrapCreatesTheFirstHolderOnlyAndIsAudited() throws Exception {
        TestOrg fresh = new TestOrg(ownerJdbc(), mvc, "p208-boot");
        UUID unit = fresh.unit("COLLEGE", null, "B1");
        TestOrg.Account admin = fresh.account("ORG_ADMIN", null);
        TestOrg.Account first = fresh.account("FACULTY", unit);
        TestOrg.Account second = fresh.account("FACULTY", unit);
        String firstEmail = first.id() + "@org.test";
        // without any holder an administrator's request has no eligible approver (stays pending; no self-approval exception)
        String body = as(admin, post("/api/v1/users/" + second.id() + "/role-assignments").content("{\"roleId\":\"" + roleId("EXAM_CONTROLLER")
                + "\",\"orgUnitId\":\"" + unit + "\"}")).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        UUID waiting = UUID.fromString(JSON.readTree(body).path("approvalRequestId").asString());
        as(admin, post("/api/v1/approvals/" + waiting + "/decision").content("{\"approve\":true}")).andExpect(status().is4xxClientError());

        PrivilegedRoleBootstrap.Result r = bootstrap.bootstrap(new PrivilegedRoleBootstrap.Command(fresh.tenant, firstEmail, "EXAM_CONTROLLER", unit,
                "ops.jane", "SUP-2026-0042", "First exam controller for the institution"));
        assertThat(r.privileged()).containsExactly("assessment.publish", "exam.results.approve", "question.approve", "question.key.read");   // 2.2: Q-2.2-1, R-1
        String actor = ownerJdbc().sql("SELECT actor_subject FROM audit_event WHERE tenant_id = ? AND action = 'role.privileged_bootstrap'").param(fresh.tenant)
                .query(String.class).single();
        assertThat(actor).isEqualTo("operator:ops.jane");
        assertThat(ownerJdbc().sql("SELECT details::text FROM audit_event WHERE tenant_id = ? AND action = 'role.privileged_bootstrap'").param(fresh.tenant)
                .query(String.class).single()).contains("SUP-2026-0042");
        // a second bootstrap at that scope is refused: a holder exists, so the normal approval applies
        assertThatThrownBy(() -> bootstrap.bootstrap(new PrivilegedRoleBootstrap.Command(fresh.tenant, second.id() + "@org.test", "EXAM_CONTROLLER", unit,
                "ops.jane", "SUP-2026-0043", "Second one"))).hasMessageContaining("already held");
        // the bootstrapped holder can now approve the waiting request
        as(first, post("/api/v1/approvals/" + waiting + "/decision").content("{\"approve\":true}")).andExpect(status().isOk());
        // bootstrap needs a ticket and a reason
        assertThatThrownBy(() -> bootstrap.bootstrap(new PrivilegedRoleBootstrap.Command(fresh.tenant, firstEmail, "EXAM_CONTROLLER", null,
                "ops.jane", "", "x"))).isInstanceOf(IllegalArgumentException.class);
    }
}
