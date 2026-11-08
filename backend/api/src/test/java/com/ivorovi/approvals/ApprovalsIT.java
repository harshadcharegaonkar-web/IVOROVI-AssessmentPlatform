package com.ivorovi.approvals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.support.IntegrationTest;
import com.ivorovi.support.TestApprovalHandler;
import com.ivorovi.support.TestOrg;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/** Four-eyes framework (C-2, Q-04): service rule, database constraint, live permission check, expiry, cancellation, audit. */
class ApprovalsIT extends IntegrationTest {

    @Autowired ApprovalService approvals;
    @Autowired TestApprovalHandler handler;

    private TestOrg org;
    private TestOrg.Account requester;
    private TestOrg.Account approver;

    @BeforeEach
    void setUp() {
        org = new TestOrg(ownerJdbc(), mvc, "approvals");
        requester = org.account("EXAM_CONTROLLER", null);
        approver = org.account("EXAM_CONTROLLER", null);
        handler.changed = false;
        handler.approved.clear();
        handler.rejected.clear();
        handler.closed.clear();
    }

    /** A pending request as the framework stores it (subject = random id). */
    private UUID pending(TestOrg.Account by, String permission) {
        UUID id = UUID.randomUUID();
        ownerJdbc().sql("""
                INSERT INTO approval_request (id, tenant_id, type, subject_type, subject_id, summary, payload_hash, required_permission, requested_by, expires_at)
                VALUES (?, ?, ?, 'test', ?, 'Test change', repeat('0', 64), ?, ?, now() + interval '1 day')""")
                .params(id, org.tenant, TestApprovalHandler.TYPE, UUID.randomUUID().toString(), permission, by.id()).update();
        return id;
    }

    private org.springframework.test.web.servlet.ResultActions decide(TestOrg.Account by, UUID id, boolean approve, String reason) throws Exception {
        return mvc.perform(post("/api/v1/approvals/" + id + "/decision").header(HttpHeaders.AUTHORIZATION, by.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"approve\":" + approve + (reason == null ? "" : ",\"reason\":\"" + reason + "\"") + "}"));
    }

    @Test
    void theRequesterCanNeverDecideTheirOwnRequest() throws Exception {
        UUID id = pending(requester, "exam.incident.decide");
        decide(requester, id, true, null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("four_eyes_required"));
        decide(requester, id, false, "no").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("four_eyes_required"));
        // nor is it offered to them
        mvc.perform(get("/api/v1/approvals?view=decide").header(HttpHeaders.AUTHORIZATION, requester.bearer())).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/v1/approvals/" + id).header(HttpHeaders.AUTHORIZATION, requester.bearer())).andExpect(jsonPath("$.canDecide").value(false))
                .andExpect(jsonPath("$.eligibleApprovers").value(1));
        assertThat(ownerJdbc().sql("SELECT count(*) FROM audit_event WHERE tenant_id = ? AND action = 'approval.self_decision_denied' AND outcome = 'DENIED'")
                .param(org.tenant).query(Long.class).single()).isEqualTo(2);
        // the second person can
        mvc.perform(get("/api/v1/approvals?view=decide").header(HttpHeaders.AUTHORIZATION, approver.bearer())).andExpect(jsonPath("$[0].canDecide").value(true));
        decide(approver, id, true, "looks right").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        assertThat(handler.approved).hasSize(1);
        assertThat(ownerJdbc().sql("SELECT count(*) FROM audit_event WHERE tenant_id = ? AND action = 'approval.approved' AND actor_id = ?")
                .params(org.tenant, approver.id()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void theDatabaseRefusesSelfApprovalEvenForADirectWrite() {
        UUID id = pending(requester, "exam.incident.decide");
        assertThatThrownBy(() -> ownerJdbc().sql("UPDATE approval_request SET status = 'APPROVED', decided_by = requested_by, decided_at = now() WHERE id = ?")
                .param(id).update()).hasMessageContaining("approval_four_eyes");
        assertThatThrownBy(() -> ownerJdbc().sql("""
                INSERT INTO approval_request (id, tenant_id, type, subject_type, subject_id, summary, payload_hash, required_permission, requested_by, expires_at,
                                              status, decided_by, decided_at)
                VALUES (?, ?, 'test.change', 'test', 'x', 'x', repeat('0', 64), 'exam.incident.decide', ?, now() + interval '1 day', 'APPROVED', ?, now())""")
                .params(UUID.randomUUID(), org.tenant, requester.id(), requester.id()).update()).hasMessageContaining("approval_four_eyes");
        // an approval without a recorded decider is also impossible
        assertThatThrownBy(() -> ownerJdbc().sql("UPDATE approval_request SET status = 'APPROVED' WHERE id = ?").param(id).update())
                .hasMessageContaining("approval_decision_consistency");
    }

    @Test
    void theApproverMustHoldThePermissionAtDecisionTime() throws Exception {
        UUID id = pending(requester, "exam.incident.decide");
        TestOrg.Account faculty = org.account("FACULTY", null);
        decide(faculty, id, true, null).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/approvals/" + id).header(HttpHeaders.AUTHORIZATION, faculty.bearer())).andExpect(status().isNotFound());
        // the approver's role is revoked after the request was made: refused live
        ownerJdbc().sql("UPDATE role_assignment SET revoked_at = now() WHERE user_account_id = ?").param(approver.id()).update();
        TenantContext.runInTenant(org.tenant, () -> principals.evictSubject(org.tenant, approver.caller().subject()));
        decide(approver, id, true, null).andExpect(status().isForbidden());
        assertThat(ownerJdbc().sql("SELECT status FROM approval_request WHERE id = ?").param(id).query(String.class).single()).isEqualTo("PENDING");
    }

    @Autowired com.ivorovi.platform.security.PrincipalCache principals;

    @Test
    void expiredCancelledAndChangedRequestsCannotBeApproved() throws Exception {
        UUID expired = pending(requester, "exam.incident.decide");
        ownerJdbc().sql("UPDATE approval_request SET expires_at = now() - interval '1 minute' WHERE id = ?").param(expired).update();
        decide(approver, expired, true, null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("approval_expired"));
        assertThat(ownerJdbc().sql("SELECT status FROM approval_request WHERE id = ?").param(expired).query(String.class).single()).isEqualTo("EXPIRED");

        UUID cancelled = pending(requester, "exam.incident.decide");
        mvc.perform(post("/api/v1/approvals/" + cancelled + "/cancel").header(HttpHeaders.AUTHORIZATION, approver.bearer())).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/approvals/" + cancelled + "/cancel").header(HttpHeaders.AUTHORIZATION, requester.bearer())).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        decide(approver, cancelled, true, null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("approval_not_pending"));
        assertThat(handler.closed).hasSize(2);

        UUID changed = pending(requester, "exam.incident.decide");
        handler.changed = true;
        decide(approver, changed, true, null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("subject_changed"));
        decide(approver, changed, false, null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("reason_required"));
        decide(approver, changed, false, "subject was edited").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
        // scheduled expiry
        UUID later = pending(requester, "exam.incident.decide");
        ownerJdbc().sql("UPDATE approval_request SET expires_at = ? WHERE id = ?").params(Timestamp.from(Instant.now().minusSeconds(5)), later).update();
        int n = TenantContext.callInTenant(org.tenant, () -> approvals.expireDue(Instant.now()));
        assertThat(n).isEqualTo(1);
        assertThat(ownerJdbc().sql("SELECT string_agg(action, ',' ORDER BY seq) FROM audit_event WHERE tenant_id = ? AND action LIKE 'approval.%'")
                .param(org.tenant).query(String.class).single()).contains("approval.cancelled", "approval.rejected", "approval.expired");
    }

    @Test
    void aSingleApproverOrganizationStaysPendingAndCanEscalate() throws Exception {
        TestOrg solo = new TestOrg(ownerJdbc(), mvc, "solo");
        TestOrg.Account only = solo.account("EXAM_CONTROLLER", null);
        UUID id = UUID.randomUUID();
        ownerJdbc().sql("""
                INSERT INTO approval_request (id, tenant_id, type, subject_type, subject_id, summary, payload_hash, required_permission, requested_by, expires_at)
                VALUES (?, ?, 'test.change', 'test', 'x', 'Test', repeat('0', 64), 'exam.incident.decide', ?, now() + interval '1 day')""")
                .params(id, solo.tenant, only.id()).update();
        mvc.perform(get("/api/v1/approvals/" + id).header(HttpHeaders.AUTHORIZATION, only.bearer())).andExpect(jsonPath("$.eligibleApprovers").value(0));
        decide(only, id, true, null).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/approvals/" + id + "/escalate").header(HttpHeaders.AUTHORIZATION, only.bearer())).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
        // a new organization-level holder (e.g. appointed after escalation) is a valid second person
        TestOrg.Account second = solo.account("ORG_ADMIN", null);
        decide(second, id, true, "escalated and checked").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void requestsAreInvisibleAcrossOrganizations() throws Exception {
        UUID id = pending(requester, "exam.incident.decide");
        TestOrg other = new TestOrg(ownerJdbc(), mvc, "approvals-other");
        TestOrg.Account outsider = other.account("ORG_ADMIN", null);
        mvc.perform(get("/api/v1/approvals/" + id).header(HttpHeaders.AUTHORIZATION, outsider.bearer())).andExpect(status().isNotFound());
        decide(outsider, id, true, null).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/approvals?view=decide").header(HttpHeaders.AUTHORIZATION, outsider.bearer())).andExpect(jsonPath("$.length()").value(0));
    }
}
