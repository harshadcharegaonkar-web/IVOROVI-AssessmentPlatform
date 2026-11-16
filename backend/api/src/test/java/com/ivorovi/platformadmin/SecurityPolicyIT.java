package com.ivorovi.platformadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.support.FakeIdentityProvisioner;
import com.ivorovi.support.IntegrationTest;
import com.ivorovi.support.TestOrg;
import com.ivorovi.support.TestTokens;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
 * Mandatory module I: the organization security policy for staff sessions. Who may view and change it; stronger rules
 * apply at once and weaker ones only after a second administrator approves; a second factor can be required only by an
 * administrator who used one; enforcement on every staff call from token claims (second factor with one enrolment
 * request per user, idle and absolute session limits per identity-provider session); other organizations unaffected;
 * the policy tables are tenant-isolated.
 */
class SecurityPolicyIT extends IntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PATH = "/api/v1/tenant/security-policy";

    @Autowired FakeIdentityProvisioner idp;
    @Autowired JdbcClient jdbc;

    TestOrg org;
    TestOrg other;
    TestOrg.Account admin;
    TestOrg.Account admin2;
    TestOrg.Account instAdmin;
    TestOrg.Account faculty;
    TestOrg.Account foreignFaculty;

    @BeforeEach
    void setUp() {
        org = new TestOrg(ownerJdbc(), mvc, "secpol");
        UUID uni = org.unit("UNIVERSITY", null, "U");
        UUID college = org.unit("COLLEGE", uni, "C");
        admin = org.account("ORG_ADMIN", null);
        admin2 = org.account("ORG_ADMIN", null);
        instAdmin = org.account("INSTITUTION_ADMIN", college);
        faculty = org.account("FACULTY", college);
        other = new TestOrg(ownerJdbc(), mvc, "secpol-other");
        foreignFaculty = other.account("ORG_ADMIN", null);
    }

    /** A staff token with chosen sign-in claims (second factor, session id, sign-in time). */
    private String token(TestOrg.Account who, boolean secondFactor, String sid, Instant authTime) {
        return TestTokens.token(who.caller().subject(), who.caller().tenantId().toString(), b -> {
            List<String> amr = new ArrayList<>(List.of("pwd"));
            if (secondFactor) amr.add("otp");
            b.claim("amr", amr);
            if (sid != null) b.claim("sid", sid);
            if (authTime != null) b.claim("auth_time", authTime.getEpochSecond());
        });
    }

    /** Null fields are omitted from responses. */
    private static boolean absent(JsonNode n) {
        return n.isMissingNode() || n.isNull();
    }

    private ResultActions call(MockHttpServletRequestBuilder r, String token) throws Exception {
        return mvc.perform(r.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("Idempotency-Key", "k-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON));
    }

    private JsonNode json(ResultActions r) throws Exception {
        return JSON.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private ResultActions change(String token, boolean mfa, Integer idle, Integer absolute, long version) throws Exception {
        return call(put(PATH).content("{\"mfaRequired\":%s,\"idleMinutes\":%s,\"absoluteHours\":%s,\"version\":%d}".formatted(mfa, idle, absolute, version)), token);
    }

    private long version(String token) throws Exception {
        return json(call(get(PATH), token).andExpect(status().isOk())).path("version").asLong();
    }

    private ResultActions approve(String token, UUID approval) throws Exception {
        return call(post("/api/v1/approvals/" + approval + "/decision").content("{\"approve\":true,\"reason\":\"checked\"}"), token);
    }

    private long audits(String action) {
        return ownerJdbc().sql("SELECT count(*) FROM audit_event WHERE tenant_id = ? AND action = ?").params(org.tenant, action).query(Long.class).single();
    }

    @Test
    void onlyOrganizationAdministratorsManageItAndWeakeningNeedsASecondAdministrator() throws Exception {
        // session ids: once a session limit is set, calls without one are refused (session_untracked)
        String a = token(admin, false, "sid-a-" + UUID.randomUUID(), Instant.now());
        String a2 = token(admin2, false, "sid-a2-" + UUID.randomUUID(), Instant.now());
        JsonNode initial = json(call(get(PATH), a).andExpect(status().isOk()));
        assertThat(initial.path("mfaRequired").asBoolean()).isFalse();
        assertThat(absent(initial.path("idleMinutes"))).isTrue();
        assertThat(initial.path("version").asLong()).isZero();
        assertThat(initial.path("passwordPolicyScope").asString()).isEqualTo("PLATFORM");
        // institution administrators and faculty cannot view or change it
        for (TestOrg.Account who : List.of(instAdmin, faculty)) {
            call(get(PATH), token(who, false, null, null)).andExpect(status().isForbidden());
            change(token(who, false, null, null), false, 30, null, 0).andExpect(status().isForbidden());
        }

        // stronger rules apply at once
        change(a, false, 30, null, 0).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.policy.idleMinutes").value(30));
        change(a, false, 30, 8, 1).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPLIED"));
        assertThat(audits("tenant.security_policy.changed")).isEqualTo(2);
        // stale version and invalid limits are refused
        change(a, false, 20, 8, 1).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("concurrent_modification"));
        change(a, false, 3, 8, 2).andExpect(status().isBadRequest());
        change(a, false, 600, 8, 2).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_idle_limit"));

        // a weaker rule (longer idle limit, session limit removed) waits for a second administrator
        JsonNode pending = json(change(a, false, 60, null, 2).andExpect(status().isOk()));
        assertThat(pending.path("status").asString()).isEqualTo("PENDING_APPROVAL");
        UUID approval = UUID.fromString(pending.path("approvalRequestId").asString());
        JsonNode view = json(call(get(PATH), a).andExpect(status().isOk()));
        assertThat(view.path("idleMinutes").asInt()).as("not applied yet").isEqualTo(30);
        assertThat(view.path("pendingApprovalId").asString()).isEqualTo(approval.toString());
        assertThat(view.path("pendingChange").path("idleMinutes").asInt()).isEqualTo(60);
        assertThat(audits("tenant.security_policy.weakening_requested")).isEqualTo(1);
        approve(a, approval).andExpect(status().is4xxClientError());                          // never the requester
        approve(token(faculty, false, null, null), approval).andExpect(status().is4xxClientError());
        approve(a2, approval).andExpect(status().isOk());
        view = json(call(get(PATH), a).andExpect(status().isOk()));
        assertThat(view.path("idleMinutes").asInt()).isEqualTo(60);
        assertThat(absent(view.path("absoluteHours"))).isTrue();
        assertThat(absent(view.path("pendingApprovalId"))).isTrue();

        // the other organization is unaffected and cannot see this policy
        JsonNode foreign = json(call(get(PATH), token(foreignFaculty, false, null, null)).andExpect(status().isOk()));
        assertThat(absent(foreign.path("idleMinutes"))).isTrue();
        long visible = TenantContext.callInTenant(other.tenant, () -> jdbc.sql("SELECT count(*) FROM tenant_security_policy").query(Long.class).single());
        assertThat(visible).isZero();
        assertThat(rawAppJdbc().sql("SELECT count(*) FROM tenant_security_policy").query(Long.class).single()).isZero();
    }

    @Test
    void aSecondFactorIsRequiredOnlyByAnAdministratorWhoUsedOneAndThenEnforcedForEveryone() throws Exception {
        String withoutFactor = token(admin, false, null, null);
        // prove the factor works first: refused, and the administrator is asked to enrol
        change(withoutFactor, true, null, null, 0).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("second_factor_needed_first"));
        assertThat(idp.authenticatorSetupRequests).containsKey(admin.caller().subject());
        assertThat(version(withoutFactor)).isZero();
        String withFactor = token(admin, true, null, null);
        change(withFactor, true, null, null, 0).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPLIED"));

        // enforced on every staff call
        call(get(PATH), withoutFactor).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("mfa_required"));
        String f = token(faculty, false, null, null);
        call(get("/api/v1/me"), f).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("mfa_required"));
        call(get("/api/v1/me"), f).andExpect(status().isForbidden());
        assertThat(idp.authenticatorSetupRequests.get(faculty.caller().subject())).as("asked once at the provider").isEqualTo(1);
        assertThat(audits("tenant.security_policy.mfa_enrolment_requested")).isEqualTo(2);
        call(get("/api/v1/me"), token(faculty, true, null, null)).andExpect(status().isOk());
        // another organization's staff are not affected
        call(get("/api/v1/me"), token(foreignFaculty, false, null, null)).andExpect(status().isOk());

        // Keycloak's default flows leave amr empty: an enrolled authenticator (required at every sign-in) counts too
        TestOrg.Account enrolled = org.account("FACULTY", null);
        call(get("/api/v1/me"), token(enrolled, false, null, null)).andExpect(status().isForbidden());
        idp.withAuthenticator.add(enrolled.caller().subject());
        call(get("/api/v1/me"), token(enrolled, false, null, null)).andExpect(status().isOk());
        assertThat(idp.authenticatorSetupRequests.get(enrolled.caller().subject())).isEqualTo(1);
        // an identity provider that cannot answer is "no second factor" (fail closed)
        TestOrg.Account unknown = org.account("FACULTY", null);
        idp.withAuthenticator.add(unknown.caller().subject());
        idp.unavailable = true;
        try {
            call(get("/api/v1/me"), token(unknown, false, null, null)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("mfa_required"));
        } finally {
            idp.unavailable = false;
        }

        // turning it off is a weakening: two administrators, both signed in with the factor
        JsonNode pending = json(change(withFactor, false, null, null, 1).andExpect(status().isOk()));
        assertThat(pending.path("status").asString()).isEqualTo("PENDING_APPROVAL");
        call(get("/api/v1/me"), f).andExpect(status().isForbidden());
        approve(token(admin2, true, null, null), UUID.fromString(pending.path("approvalRequestId").asString())).andExpect(status().isOk());
        call(get("/api/v1/me"), f).andExpect(status().isOk());
    }

    @Test
    void anAdministratorWithAnEnrolledAuthenticatorCanRequireIt() throws Exception {
        idp.withAuthenticator.add(admin.caller().subject());
        String a = token(admin, false, null, null);                                          // amr empty, as from Keycloak's default flows
        assertThat(json(call(get(PATH), a).andExpect(status().isOk())).path("callerHasSecondFactor").asBoolean()).isTrue();
        change(a, true, null, null, 0).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPLIED"));
        call(get(PATH), a).andExpect(status().isOk());
        assertThat(idp.authenticatorSetupRequests).doesNotContainKey(admin.caller().subject());
        call(get("/api/v1/me"), token(faculty, false, null, null)).andExpect(status().isForbidden());
        // back to the baseline through a second administrator
        idp.withAuthenticator.add(admin2.caller().subject());
        JsonNode pending = json(change(a, false, null, null, 1).andExpect(status().isOk()));
        approve(token(admin2, false, null, null), UUID.fromString(pending.path("approvalRequestId").asString())).andExpect(status().isOk());
        call(get("/api/v1/me"), token(faculty, false, null, null)).andExpect(status().isOk());
    }

    @Test
    void idleAndAbsoluteLimitsEndSessionsForGood() throws Exception {
        change(token(admin, false, "admin-setup", null), false, 10, 2, 0).andExpect(status().isOk());
        String sid = "sid-" + UUID.randomUUID();
        String f = token(faculty, false, sid, Instant.now());
        call(get("/api/v1/me"), f).andExpect(status().isOk());
        // a session limit cannot be enforced without a session id
        call(get("/api/v1/me"), token(faculty, false, null, null)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("session_untracked"));

        // inactive for longer than the idle limit: ended, and it stays ended
        ownerJdbc().sql("UPDATE staff_session SET last_seen = now() - interval '11 minutes' WHERE tenant_id = ? AND session_id = ?").params(org.tenant, sid).update();
        call(get("/api/v1/me"), f).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("session_idle_timeout"));
        call(get("/api/v1/me"), f).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("session_idle_timeout"));
        assertThat(audits("tenant.security_policy.session_ended")).isEqualTo(1);
        // a new sign-in (new session) works
        call(get("/api/v1/me"), token(faculty, false, "sid-" + UUID.randomUUID(), Instant.now())).andExpect(status().isOk());

        // older than the absolute limit (from the sign-in time)
        String old = token(faculty, false, "sid-" + UUID.randomUUID(), Instant.now().minusSeconds(3 * 3600));
        call(get("/api/v1/me"), old).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("session_expired"));
        // ... or from the first call seen, when the token has no sign-in time
        String sid2 = "sid-" + UUID.randomUUID();
        String noAuthTime = token(faculty, false, sid2, null);
        call(get("/api/v1/me"), noAuthTime).andExpect(status().isOk());
        ownerJdbc().sql("UPDATE staff_session SET first_seen = now() - interval '3 hours' WHERE tenant_id = ? AND session_id = ?").params(org.tenant, sid2).update();
        call(get("/api/v1/me"), noAuthTime).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("session_expired"));

        // the other organization has no limits: no session id needed there
        call(get("/api/v1/me"), token(foreignFaculty, false, null, null)).andExpect(status().isOk());
        long foreignSessions = TenantContext.callInTenant(other.tenant, () -> jdbc.sql("SELECT count(*) FROM staff_session").query(Long.class).single());
        assertThat(foreignSessions).isZero();
        long ownSessions = TenantContext.callInTenant(org.tenant, () -> jdbc.sql("SELECT count(*) FROM staff_session").query(Long.class).single());
        assertThat(ownSessions).isGreaterThanOrEqualTo(4);
        assertThat(rawAppJdbc().sql("SELECT count(*) FROM staff_session").query(Long.class).single()).isZero();
    }
}
