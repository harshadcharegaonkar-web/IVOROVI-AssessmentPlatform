package com.ivorovi.exams;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ivorovi.exams.internal.CandidateService;
import com.ivorovi.exams.internal.OutageDetector;
import com.ivorovi.platform.crypto.FieldCipher;
import com.ivorovi.platform.security.CandidateAuthentication;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.platform.web.ApiException;
import com.ivorovi.support.IntegrationTest;
import com.ivorovi.support.TestOrg;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * C-3 end to end: delivery heartbeats stop → platform incident and organization impact → delivery returns → affected
 * candidates recorded → compensation proposed → the proposer cannot approve → a second controller approves → candidates
 * get a bound check-in entitlement and an email → the candidate uses it exactly once. Also rejection and manual reports.
 */
class IncidentWorkflowIT extends IntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    @Autowired OutageDetector detector;
    @Autowired CandidateService candidates;
    @Autowired FieldCipher cipher;

    private TestOrg org;
    private TestOrg.Account proposer;
    private TestOrg.Account approver;
    private String portalKey;
    private UUID session;
    private final String subA = UUID.randomUUID().toString();
    private final String subB = UUID.randomUUID().toString();
    private UUID candA;
    private UUID candB;

    @BeforeEach
    void setUp() {
        // a clean platform state: no heartbeats, no open incident
        ownerJdbc().sql("DELETE FROM process_heartbeat").update();
        ownerJdbc().sql("UPDATE platform_incident SET status = 'RESOLVED', ended_at = now() WHERE status = 'OPEN'").update();
        org = new TestOrg(ownerJdbc(), mvc, "incidents");
        proposer = org.account("EXAM_CONTROLLER", null);
        approver = org.account("EXAM_CONTROLLER", null);
        portalKey = "inc-" + UUID.randomUUID().toString().substring(0, 8);
        UUID portal = DeliveryIT.portal(org.tenant, portalKey, "OPTIONAL", true);
        session = DeliveryIT.session(org.tenant, portal, Instant.now().minus(Duration.ofMinutes(20)), Instant.now().plus(Duration.ofMinutes(40)));
        candA = candidate(subA, "a@cand.test", portal);
        candB = candidate(subB, "b@cand.test", portal);
        for (UUID c : List.of(candA, candB)) {
            ownerJdbc().sql("INSERT INTO check_in (id, tenant_id, session_id, candidate_id, code_id, checked_in_at) VALUES (?, ?, ?, ?, ?, now() - interval '10 minutes')")
                    .params(UUID.randomUUID(), org.tenant, session, c, UUID.randomUUID()).update();
            ownerJdbc().sql("INSERT INTO delivery_presence (tenant_id, candidate_id, session_id, first_seen_at, last_seen_at) VALUES (?, ?, ?, now() - interval '10 minutes', now() - interval '2 minutes')")
                    .params(org.tenant, c, session).update();
        }
    }

    @Test
    void outageToApprovedCompensationEndToEnd() throws Exception {
        Instant t0 = Instant.now();
        // delivery stopped beating 2 minutes ago (no graceful shutdown)
        ownerJdbc().sql("INSERT INTO process_heartbeat (instance_id, role, started_at, last_beat_at) VALUES ('delivery-1', 'DELIVERY', now() - interval '1 hour', now() - interval '2 minutes')").update();
        OutageDetector.Result opened = detector.check(Instant.now());
        assertThat(opened.change()).isEqualTo("OPENED");
        assertThat(detector.check(Instant.now()).change()).isEqualTo("ONGOING");
        UUID impact = ownerJdbc().sql("SELECT id FROM exam_incident_impact WHERE tenant_id = ? AND platform_incident_id = ?").params(org.tenant, opened.platformIncidentId())
                .query(UUID.class).single();
        assertThat(org.events(t0).stream().map(e -> e.eventType())).contains("exam.incident.started");
        // ongoing: compensation cannot be proposed yet
        mvc.perform(propose(proposer, impact, "NEW_CHECK_IN", null, 1)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("incident_ongoing"));

        // delivery is back
        ownerJdbc().sql("UPDATE process_heartbeat SET last_beat_at = now() WHERE instance_id = 'delivery-1'").update();
        assertThat(detector.check(Instant.now()).change()).isEqualTo("RESOLVED");
        assertThat(ownerJdbc().sql("SELECT status FROM platform_incident WHERE id = ?").param(opened.platformIncidentId()).query(String.class).single()).isEqualTo("RESOLVED");
        JsonNode incident = json(mvc.perform(get("/api/v1/exam/incidents/" + impact).header(HttpHeaders.AUTHORIZATION, proposer.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(incident.path("affected").asLong()).isEqualTo(2);
        assertThat(incident.path("endedAt").isNull()).isFalse();
        assertThat(org.events(t0).stream().map(e -> e.eventType())).contains("exam.incident.detected");

        // proposal
        mvc.perform(propose(proposer, impact, "EXTEND_SESSION", 500, incident.path("version").asLong())).andExpect(status().isBadRequest());
        JsonNode proposed = json(mvc.perform(propose(proposer, impact, "EXTEND_SESSION", 20, incident.path("version").asLong())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(proposed.path("status").asString()).isEqualTo("COMPENSATION_PENDING");
        UUID approval = UUID.fromString(proposed.path("compensations").get(0).path("approvalId").asString());
        // nothing applied yet; the server deadline is untouched
        assertThat(ownerJdbc().sql("SELECT count(*) FROM candidate_compensation WHERE tenant_id = ?").param(org.tenant).query(Long.class).single()).isZero();

        // four-eyes: the proposer cannot approve; someone without the permission cannot either
        decide(proposer, approval, true).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("four_eyes_required"));
        decide(org.account("FACULTY", null), approval, true).andExpect(status().isForbidden());
        decide(approver, approval, true).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));

        // applied: one bound entitlement per affected candidate, candidates emailed, incident compensated
        assertThat(ownerJdbc().sql("SELECT count(*) FROM candidate_compensation WHERE tenant_id = ? AND extension_minutes = 20 AND notified_at IS NOT NULL")
                .param(org.tenant).query(Long.class).single()).isEqualTo(2);
        assertThat(ownerJdbc().sql("SELECT count(*) FROM session_code WHERE tenant_id = ? AND issued_for IS NOT NULL").param(org.tenant).query(Long.class).single())
                .isEqualTo(2);
        List<String> mails = ownerJdbc().sql("SELECT subject || '|' || body FROM email_delivery WHERE tenant_id = ? AND category = 'exams'").param(org.tenant)
                .query(String.class).list();
        assertThat(mails).hasSize(2).allMatch(m -> m.startsWith("Compensation for an exam service interruption") && m.contains("an extra 20 minutes")
                && m.contains("/p/" + portalKey));
        mvc.perform(get("/api/v1/exam/incidents/" + impact).header(HttpHeaders.AUTHORIZATION, approver.bearer()))
                .andExpect(jsonPath("$.status").value("COMPENSATED")).andExpect(jsonPath("$.compensations[0].applied").value(2));

        // the candidate sees and uses it, exactly once; another candidate cannot use it
        CandidateService.Me me = asCandidate(subA, "a@cand.test", () -> candidates.me(portalKey));
        assertThat(me.compensations()).hasSize(1);
        UUID entitlement = me.compensations().get(0).id();
        assertThat(me.compensations().get(0).validUntil()).isAfter(Instant.now().plus(Duration.ofMinutes(55)));
        CandidateService.CheckInResult used = asCandidate(subA, "a@cand.test", () -> candidates.compensationCheckIn(portalKey, entitlement, 0, Map.of()));
        assertThat(used.sessionId()).isEqualTo(session);
        assertThatThrownBy(() -> asCandidate(subA, "a@cand.test", () -> candidates.compensationCheckIn(portalKey, entitlement, 0, Map.of())))
                .isInstanceOf(ApiException.class).hasMessageContaining("not valid");
        assertThatThrownBy(() -> asCandidate(subB, "b@cand.test", () -> candidates.compensationCheckIn(portalKey, entitlement, 0, Map.of())))
                .isInstanceOf(ApiException.class);

        // the whole trail is audited
        String trail = ownerJdbc().sql("SELECT string_agg(action, ',' ORDER BY seq) FROM audit_event WHERE tenant_id = ?").param(org.tenant).query(String.class).single();
        assertThat(trail).contains("exam.incident.opened", "exam.incident.detected", "exam.compensation.proposed", "approval.requested",
                "approval.self_decision_denied", "exam.compensation.applied", "approval.approved", "exam.check_in.compensation", "exam.session_code.refused");
        assertThat(trail.indexOf("approval.self_decision_denied")).isLessThan(trail.indexOf("approval.approved"));
    }

    @Test
    void rejectionReopensTheIncidentAndInstitutionsCanReportOutages() throws Exception {
        Instant start = Instant.now().minus(Duration.ofMinutes(8));
        Instant end = Instant.now().minus(Duration.ofMinutes(3));
        JsonNode reported = json(mvc.perform(post("/api/v1/exam/incidents").header(HttpHeaders.AUTHORIZATION, proposer.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"startedAt\":\"" + start + "\",\"endedAt\":\"" + end + "\",\"description\":\"Campus network down in hall B\",\"sessionId\":\"" + session + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertThat(reported.path("source").asString()).isEqualTo("REPORTED");
        assertThat(reported.path("affected").asLong()).isEqualTo(2);
        UUID impact = UUID.fromString(reported.path("id").asString());
        JsonNode p = json(mvc.perform(propose(proposer, impact, "NEW_CHECK_IN", null, reported.path("version").asLong())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        UUID approval = UUID.fromString(p.path("compensations").get(0).path("approvalId").asString());
        mvc.perform(propose(proposer, impact, "NEW_CHECK_IN", null, p.path("version").asLong())).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/approvals/" + approval + "/decision").header(HttpHeaders.AUTHORIZATION, approver.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"approve\":false,\"reason\":\"Only hall B was affected; re-sit policy applies\"}")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/exam/incidents/" + impact).header(HttpHeaders.AUTHORIZATION, proposer.bearer()))
                .andExpect(jsonPath("$.status").value("OPEN")).andExpect(jsonPath("$.compensations[0].status").value("REJECTED"));
        assertThat(ownerJdbc().sql("SELECT count(*) FROM candidate_compensation WHERE tenant_id = ?").param(org.tenant).query(Long.class).single()).isZero();
        // close with no action (still four-eyes)
        JsonNode again = json(mvc.perform(get("/api/v1/exam/incidents/" + impact).header(HttpHeaders.AUTHORIZATION, proposer.bearer())).andReturn().getResponse().getContentAsString());
        JsonNode none = json(mvc.perform(propose(proposer, impact, "NO_ACTION", null, again.path("version").asLong())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        UUID approval2 = UUID.fromString(none.path("compensations").get(0).path("approvalId").asString());
        decide(approver, approval2, true).andExpect(status().isOk());
        mvc.perform(get("/api/v1/exam/incidents/" + impact).header(HttpHeaders.AUTHORIZATION, proposer.bearer())).andExpect(jsonPath("$.status").value("CLOSED_NO_ACTION"));
        // permissions and isolation
        mvc.perform(get("/api/v1/exam/incidents").header(HttpHeaders.AUTHORIZATION, org.account("FACULTY", null).bearer())).andExpect(status().isForbidden());
        TestOrg other = new TestOrg(ownerJdbc(), mvc, "incidents-other");
        mvc.perform(get("/api/v1/exam/incidents/" + impact).header(HttpHeaders.AUTHORIZATION, other.account("EXAM_CONTROLLER", null).bearer()))
                .andExpect(status().isNotFound());
    }

    @Test
    void aGracefulShutdownIsNotAnOutageAndRequestsNeedTheDecidePermission() throws Exception {
        // no recent delivery instance at all (deregistered on shutdown) → nothing to detect
        assertThat(detector.check(Instant.now()).change()).isEqualTo("NONE");
        // a custom role that may manage incidents but not decide compensation cannot ask for a decision it could not make itself
        UUID role = UUID.randomUUID();
        ownerJdbc().sql("INSERT INTO role (id, tenant_id, code, name, system_role, permissions) VALUES (?, ?, 'INCIDENT_CLERK', 'Incident clerk', false, ARRAY['exam.incident.manage'])")
                .params(role, org.tenant).update();
        TestOrg.Account clerk = org.account("FACULTY", null);
        ownerJdbc().sql("INSERT INTO role_assignment (id, tenant_id, user_account_id, role_id, scope_type, scope_id) VALUES (?, ?, ?, ?, 'TENANT', ?)")
                .params(UUID.randomUUID(), org.tenant, clerk.id(), role, org.tenant).update();
        JsonNode reported = json(mvc.perform(post("/api/v1/exam/incidents").header(HttpHeaders.AUTHORIZATION, clerk.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"startedAt\":\"" + Instant.now().minusSeconds(600) + "\",\"endedAt\":\"" + Instant.now().minusSeconds(300) + "\",\"description\":\"x\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        mvc.perform(propose(clerk, UUID.fromString(reported.path("id").asString()), "NEW_CHECK_IN", null, reported.path("version").asLong()))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private UUID candidate(String sub, String email, UUID portal) {
        UUID id = UUID.randomUUID();
        ownerJdbc().sql("INSERT INTO candidate_account (id, tenant_id, subject, email_enc, display_name, portal_id) VALUES (?, ?, ?, ?, ?, ?)")
                .params(id, org.tenant, sub, cipher.encrypt(email, "candidate_account.email", org.tenant), "Candidate " + email.charAt(0), portal).update();
        return id;
    }

    private <T> T asCandidate(String sub, String email, java.util.function.Supplier<T> work) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject(sub).claim("email", email).claim("email_verified", true)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        SecurityContextHolder.getContext().setAuthentication(new CandidateAuthentication(jwt));
        try {
            return TenantContext.callInTenant(org.tenant, work);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder propose(TestOrg.Account by, UUID impact, String action, Integer minutes, long version) {
        return post("/api/v1/exam/incidents/" + impact + "/compensations").header(HttpHeaders.AUTHORIZATION, by.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"" + action + "\"," + (minutes == null ? "" : "\"extensionMinutes\":" + minutes + ",") + "\"rationale\":\"Lost time plus 5 minutes\","
                        + "\"version\":" + version + "}");
    }

    private org.springframework.test.web.servlet.ResultActions decide(TestOrg.Account by, UUID approval, boolean approve) throws Exception {
        return mvc.perform(post("/api/v1/approvals/" + approval + "/decision").header(HttpHeaders.AUTHORIZATION, by.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"approve\":" + approve + "}"));
    }

    private static JsonNode json(String s) {
        return JSON.readTree(s);
    }
}
