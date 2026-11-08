package com.ivorovi.exams;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ivorovi.support.FakeCandidateMfa;
import com.ivorovi.support.IntegrationTest;
import com.ivorovi.support.RecordingEmailSender;
import com.ivorovi.support.TestOrg;
import com.ivorovi.support.TestTokens;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Staff administration on the administrative process: portals, sessions, codes, candidates; boundary towards delivery. */
class ExamAdminIT extends IntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    @Autowired FakeCandidateMfa mfa;

    private TestOrg org;
    private TestOrg.Account controller;

    @BeforeEach
    void setUp() {
        org = new TestOrg(ownerJdbc(), mvc, "examadmin");
        controller = org.account("EXAM_CONTROLLER", null);
    }

    @Test
    void theAdministrativeProcessDoesNotServeDeliveryAndRejectsCandidateTokens() throws Exception {
        mvc.perform(get("/api/v1/delivery/time")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/delivery/portals/x/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.candidate("c1", "c@c.test")))
                .andExpect(status().isNotFound());
        // a candidate-realm token is not a staff token: wrong issuer, key and audience
        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.candidate(controller.caller().subject(), "c@c.test")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/exam/portals").header(HttpHeaders.AUTHORIZATION, "Bearer "
                        + TestTokens.signedByCandidateKey(controller.caller().subject(), org.tenant.toString(), b -> {})))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void portalsSessionsAndCodes() throws Exception {
        String key = "meridian-" + UUID.randomUUID().toString().substring(0, 6);
        JsonNode portal = body(mvc.perform(post("/api/v1/exam/portals").header(HttpHeaders.AUTHORIZATION, controller.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"portalKey\":\"" + key + "\",\"name\":\"Entrance tests\",\"mfaPolicy\":\"OPTIONAL\",\"selfRegistration\":true}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertThat(portal.path("url").asString()).endsWith("/p/" + key);
        mvc.perform(post("/api/v1/exam/portals").header(HttpHeaders.AUTHORIZATION, controller.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"portalKey\":\"" + key + "\",\"name\":\"Again\",\"mfaPolicy\":\"OFF\",\"selfRegistration\":true}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("portal_key_taken"));
        mvc.perform(put("/api/v1/exam/portals/" + portal.path("id").asString()).header(HttpHeaders.AUTHORIZATION, controller.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Entrance tests\",\"mfaPolicy\":\"REQUIRED\",\"selfRegistration\":true,\"status\":\"ACTIVE\",\"version\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mfaPolicy").value("REQUIRED"));

        Instant start = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(3600);
        JsonNode session = body(mvc.perform(post("/api/v1/exam/sessions").header(HttpHeaders.AUTHORIZATION, controller.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"portalId\":\"" + portal.path("id").asString() + "\",\"name\":\"Readiness check\",\"startsAt\":\"" + start + "\",\"endsAt\":\""
                        + start.plusSeconds(7200) + "\"}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String sid = session.path("id").asString();
        JsonNode codes = body(mvc.perform(post("/api/v1/exam/sessions/" + sid + "/codes").header(HttpHeaders.AUTHORIZATION, controller.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"count\":3}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertThat(codes.size()).isEqualTo(3);
        String plain = codes.get(0).path("code").asString();
        assertThat(plain).matches("[A-HJKMNP-Z2-9]{5}-[A-HJKMNP-Z2-9]{5}");
        assertThat(codes.get(0).path("validFrom").asString()).isEqualTo(start.minusSeconds(1800).toString());
        // the list never shows codes again, only hints and states
        String list = mvc.perform(get("/api/v1/exam/sessions/" + sid + "/codes").header(HttpHeaders.AUTHORIZATION, controller.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3)).andExpect(jsonPath("$[0].status").value("NOT_YET_VALID"))
                .andReturn().getResponse().getContentAsString();
        assertThat(list).doesNotContain(plain).doesNotContain(plain.replace("-", ""));
        assertThat(ownerJdbc().sql("SELECT count(*) FROM session_code WHERE code_hash LIKE ? OR hint = ?").params("%" + plain.replace("-", "") + "%",
                plain.replace("-", "")).query(Long.class).single()).isZero();
        mvc.perform(post("/api/v1/exam/sessions/" + sid + "/codes/" + codes.get(1).path("id").asString() + "/revoke")
                .header(HttpHeaders.AUTHORIZATION, controller.bearer())).andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/exam/sessions/" + sid + "/codes/" + codes.get(1).path("id").asString() + "/revoke")
                .header(HttpHeaders.AUTHORIZATION, controller.bearer())).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/exam/sessions/" + sid + "/cancel").header(HttpHeaders.AUTHORIZATION, controller.bearer())).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(ownerJdbc().sql("SELECT count(*) FROM session_code WHERE session_id = CAST(? AS uuid) AND revoked_at IS NULL").param(sid)
                .query(Long.class).single()).isZero();
        // audited, without codes
        String audit = ownerJdbc().sql("SELECT string_agg(action || details::text, ' ') FROM audit_event WHERE tenant_id = ? AND action LIKE 'exam.%'")
                .param(org.tenant).query(String.class).single();
        assertThat(audit).contains("exam.portal.created", "exam.portal.updated", "exam.session.created", "exam.session_codes.generated",
                "exam.session_code.revoked", "exam.session.cancelled").doesNotContain(plain.replace("-", ""));
    }

    @Test
    void permissionsAndTenantIsolation() throws Exception {
        TestOrg.Account faculty = org.account("FACULTY", null);
        mvc.perform(get("/api/v1/exam/portals").header(HttpHeaders.AUTHORIZATION, faculty.bearer())).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/exam/portals").header(HttpHeaders.AUTHORIZATION, faculty.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"portalKey\":\"nope-portal\",\"name\":\"x\",\"mfaPolicy\":\"OFF\",\"selfRegistration\":true}")).andExpect(status().isForbidden());
        UUID portal = DeliveryIT.portal(org.tenant, "iso-" + UUID.randomUUID().toString().substring(0, 6), "OFF", true);
        UUID session = DeliveryIT.session(org.tenant, portal, Instant.now(), Instant.now().plusSeconds(3600));
        TestOrg other = new TestOrg(ownerJdbc(), mvc, "examother");
        TestOrg.Account otherController = other.account("EXAM_CONTROLLER", null);
        mvc.perform(get("/api/v1/exam/sessions/" + session).header(HttpHeaders.AUTHORIZATION, otherController.bearer())).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/exam/sessions/" + session + "/codes").header(HttpHeaders.AUTHORIZATION, otherController.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"count\":1}")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/exam/portals").header(HttpHeaders.AUTHORIZATION, otherController.bearer())).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(post("/api/v1/exam/sessions").header(HttpHeaders.AUTHORIZATION, otherController.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"portalId\":\"" + portal + "\",\"name\":\"x\",\"startsAt\":\"" + Instant.now() + "\",\"endsAt\":\"" + Instant.now().plusSeconds(60) + "\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void mfaResetRemovesFactorsAndTellsTheCandidate(@Autowired RecordingEmailSender mail) throws Exception {
        UUID portal = DeliveryIT.portal(org.tenant, "mfa-" + UUID.randomUUID().toString().substring(0, 6), "REQUIRED", true);
        UUID candidate = UUID.randomUUID();
        String sub = UUID.randomUUID().toString();
        ownerJdbc().sql("INSERT INTO candidate_account (id, tenant_id, subject, email_enc, display_name, portal_id) VALUES (?, ?, ?, ?, ?, ?)")
                .params(candidate, org.tenant, sub, encrypt("lost@cand.test"), "Lost Device", portal).update();
        mfa.enrolled.add(sub);
        mvc.perform(post("/api/v1/exam/candidates/" + candidate + "/mfa-reset").header(HttpHeaders.AUTHORIZATION, controller.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/exam/candidates/" + candidate + "/mfa-reset").header(HttpHeaders.AUTHORIZATION, controller.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Phone lost; identity checked at the exam office\"}")).andExpect(status().isNoContent());
        assertThat(mfa.resets).contains(sub);
        assertThat(mfa.enrolled).doesNotContain(sub);
        assertThat(ownerJdbc().sql("SELECT subject FROM email_delivery WHERE tenant_id = ? AND category = 'exams'").param(org.tenant).query(String.class).list())
                .containsExactly("Your exam sign-in security was reset");
        mvc.perform(get("/api/v1/exam/candidates").header(HttpHeaders.AUTHORIZATION, controller.bearer())).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].email").value("lost@cand.test"));
    }

    @Test
    void aPasswordResetEndsEverySessionOfTheCandidateAndIsAudited(@Autowired com.ivorovi.exams.internal.RecoveryGuard guard) {
        UUID portal = DeliveryIT.portal(org.tenant, "rec-" + UUID.randomUUID().toString().substring(0, 6), "OPTIONAL", true);
        String kept = UUID.randomUUID().toString();
        String forgot = UUID.randomUUID().toString();
        UUID id = UUID.randomUUID();
        ownerJdbc().sql("INSERT INTO candidate_account (id, tenant_id, subject, email_enc, display_name, portal_id) VALUES (?, ?, ?, ?, ?, ?)")
                .params(id, org.tenant, forgot, encrypt("f@cand.test"), "Forgot", portal).update();
        Instant resetAt = Instant.now();
        mfa.passwordResets.add(new CandidateMfa.PasswordReset(forgot, resetAt, false, "reset-session-1"));   // did not tick "sign out other devices"
        mfa.passwordResets.add(new CandidateMfa.PasswordReset(kept, Instant.now(), true));      // did
        assertThat(guard.enforce()).isEqualTo(1);
        assertThat(mfa.signedOut).containsExactly(forgot);
        // KI-79: only sessions older than the reset end; the session the reset flow signed in is kept
        assertThat(mfa.signedOutBefore).containsExactly(new com.ivorovi.support.FakeCandidateMfa.SignOutBefore(forgot, resetAt, "reset-session-1"));
        mfa.signedOutBefore.clear();
        assertThat(guard.enforce()).isZero();                                                  // handled once
        assertThat(ownerJdbc().sql("SELECT details->>'sessionsEnded' FROM audit_event WHERE tenant_id = ? AND action = 'exam.candidate.recovered' AND resource_id = ?")
                .params(org.tenant, id.toString()).query(String.class).single()).isEqualTo("enforced");
        mfa.passwordResets.clear();
    }

    @Autowired com.ivorovi.platform.crypto.FieldCipher cipher;

    private String encrypt(String email) {
        return cipher.encrypt(email, "candidate_account.email", org.tenant);
    }

    private static JsonNode body(String s) {
        return JSON.readTree(s);
    }
}
