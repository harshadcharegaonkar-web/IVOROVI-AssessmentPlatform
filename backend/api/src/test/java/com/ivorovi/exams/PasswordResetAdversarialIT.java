package com.ivorovi.exams;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.ivorovi.exams.internal.RecoveryGuard;
import com.ivorovi.support.FakeCandidateMfa;
import com.ivorovi.support.IntegrationTest;
import com.ivorovi.support.TestOrg;
import com.ivorovi.support.TestTokens;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Adversarial review of KI-79 (QA, Day 6): after a password reset, can a token of an ended session still reach anything?
 * Complements {@link PasswordResetSessionsIT} (the main flow, in-flight requests, the second boundary). Here: every candidate
 * endpoint, tokens without a session id, several organizations, an organization the candidate was not yet enrolled in, an
 * organization that was not active at the time of the reset, a reset retried after the identity provider failed, and
 * repeated guard runs. Identity provider: {@link FakeCandidateMfa}; the real Keycloak side is the exam E2E.
 */
@Tag("security-regression")
@TestPropertySource(properties = "ivorovi.process.role=DELIVERY")
class PasswordResetAdversarialIT extends IntegrationTest {

    @Autowired FakeCandidateMfa mfa;
    @Autowired RecoveryGuard guard;

    private TestOrg org;
    private String portal;
    private final String subject = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() throws Exception {
        org = new TestOrg(ownerJdbc(), mvc, "ki79q");
        portal = newPortal(org.tenant);
        mfa.passwordResets.clear();
        mfa.failSignOuts.set(0);
        assertThat(me(portal, token("sid-first", Instant.now().minusSeconds(600)))).isEqualTo(200);
    }

    @AfterEach
    void clear() {
        mfa.passwordResets.clear();
        mfa.failSignOuts.set(0);
    }

    private static String newPortal(UUID tenant) {
        String key = "q-" + UUID.randomUUID().toString().substring(0, 8);
        DeliveryIT.portal(tenant, key, "OFF", true);
        return key;
    }

    private String token(String sid, Instant issuedAt) {
        return TestTokens.candidate(subject, b -> {
            b.claim("email", "q-" + subject.substring(0, 8) + "@cand.test").claim("email_verified", true).issuedAt(issuedAt).expiresAt(Instant.now().plusSeconds(300));
            if (sid != null) b.claim("sid", sid);
        });
    }

    private int send(MockHttpServletRequestBuilder r, String token) throws Exception {
        return mvc.perform(r.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).contentType(MediaType.APPLICATION_JSON)).andReturn().getResponse().getStatus();
    }

    private int me(String portalKey, String token) throws Exception {
        return send(get("/api/v1/delivery/portals/" + portalKey + "/me"), token);
    }

    private void reset(Instant at, String keptSession) {
        mfa.passwordResets.add(new CandidateMfa.PasswordReset(subject, at, false, keptSession));
    }

    private long audits(String action) {
        return ownerJdbc().sql("SELECT count(*) FROM audit_event WHERE action = ? AND resource_id = (SELECT id::text FROM candidate_account WHERE tenant_id = ? AND subject = ?)")
                .params(action, org.tenant, subject).query(Long.class).single();
    }

    @Test
    void everyCandidateEndpointRefusesAnEndedSession() throws Exception {
        String old = token("sid-old", Instant.now().minusSeconds(120));
        reset(Instant.now().minusSeconds(10), "sid-new");
        assertThat(guard.enforce()).isEqualTo(1);
        String p = "/api/v1/delivery/portals/" + portal;
        UUID any = UUID.randomUUID();
        List<MockHttpServletRequestBuilder> calls = List.of(
                get(p + "/me"), get(p + "/exams"),
                post(p + "/exams/" + any + "/attempt").content("{}"),
                get(p + "/attempts/" + any), post(p + "/attempts/" + any + "/pulse").content("{}"),
                put(p + "/attempts/" + any + "/answers/0").content("{\"seq\":1,\"response\":{\"choice\":\"A\"}}"),
                post(p + "/attempts/" + any + "/submit").content("{}"), get(p + "/attempts/" + any + "/result"),
                get(p + "/attempts/" + any + "/images/" + any),
                post(p + "/check-ins").content("{\"code\":\"ABCD-EFGH\"}"), post(p + "/compensations/" + any + "/check-in").content("{}"),
                post(p + "/presence").content("{\"sessionId\":\"" + any + "\"}"), post(p + "/saves").content("{\"sessionId\":\"" + any + "\",\"seq\":1,\"payload\":\"x\"}"));
        List<String> wrong = new ArrayList<>();
        for (MockHttpServletRequestBuilder c : calls) {
            int s = send(c, old);
            if (s != 401) wrong.add(c.buildRequest(new org.springframework.mock.web.MockServletContext()).getRequestURI() + " -> " + s);
        }
        assertThat(wrong).as("endpoints that did not refuse the ended session").isEmpty();
        // the public portal information and the server time stay available (no candidate data)
        assertThat(send(get(p), old)).isEqualTo(200);
    }

    @Test
    void aTokenWithoutASessionIdIsJudgedByItsIssueTimeAlone() throws Exception {
        Instant at = Instant.now().minusSeconds(20);
        reset(at, "sid-new");
        guard.enforce();
        assertThat(me(portal, token(null, at.minusSeconds(60)))).isEqualTo(401);
        assertThat(me(portal, token(null, at.minusSeconds(1)))).isEqualTo(401);
        assertThat(me(portal, token(null, at.plusSeconds(2)))).isEqualTo(200);
        // a session id that only resembles the kept one is not the kept one
        assertThat(me(portal, token("sid-new ", at.minusSeconds(60)))).isEqualTo(401);
        assertThat(me(portal, token("SID-NEW", at.minusSeconds(60)))).isEqualTo(401);
    }

    @Test
    void theRevocationHoldsInEveryOrganizationTheCandidateBelongsTo() throws Exception {
        TestOrg second = new TestOrg(ownerJdbc(), mvc, "ki79q2");
        String portal2 = newPortal(second.tenant);
        String old = token("sid-old", Instant.now().minusSeconds(120));
        assertThat(me(portal2, old)).isEqualTo(200);
        reset(Instant.now().minusSeconds(5), "sid-new");
        guard.enforce();
        assertThat(List.of(me(portal, old), me(portal2, old))).containsOnly(401);
        assertThat(List.of(me(portal, token("sid-new", Instant.now())), me(portal2, token("sid-new", Instant.now())))).containsOnly(200);
    }

    @Test
    void repeatedGuardRunsDoNotRepeatOrUndoTheRevocation() throws Exception {
        String old = token("sid-old", Instant.now().minusSeconds(120));
        reset(Instant.now().minusSeconds(15), "sid-new");
        assertThat(guard.enforce()).isEqualTo(1);
        assertThat(guard.enforce()).isZero();
        assertThat(guard.enforce()).isZero();
        assertThat(me(portal, old)).isEqualTo(401);
        assertThat(audits("exam.candidate.recovered")).isEqualTo(1);
        // the identity provider failing once: nothing is recorded as handled, the next run enforces it
        String older = token("sid-older", Instant.now().minusSeconds(8));   // a sign-in after the first reset
        Instant second = Instant.now().minusSeconds(2);
        reset(second, "sid-newer");
        mfa.failSignOuts.set(1);
        assertThat(guard.enforce()).isZero();
        assertThat(me(portal, older)).as("before the retry the reset is not yet recorded").isEqualTo(200);
        assertThat(guard.enforce()).isEqualTo(1);
        assertThat(me(portal, older)).isEqualTo(401);
    }

    /**
     * QA-D6-3 (Low): the guard overwrites kept_session_id with the session of whichever reset it handles last, while
     * sessions_revoked_before only moves forward. When the sign-out of an older reset A fails and a newer reset B succeeds, the
     * retry of A (next run) sets kept_session_id to A's session: tokens of A's session, which B ended, are accepted again
     * until they expire (at most 5 minutes; the identity provider ended the session, so no refresh). Verified failing 2026-11-15.
     */
    @Disabled("QA-D6-3: fails until a retried older reset cannot replace the kept session of a newer one — enable with the fix")
    @Test
    void aRetriedOlderResetDoesNotExemptItsSessionAfterANewerReset() throws Exception {
        // within the guard's look-back (the guard is one bean for the whole test context; its window trails by 30 seconds)
        Instant a = Instant.now().minusSeconds(12);
        Instant b = Instant.now().minusSeconds(4);
        reset(a, "sid-a");
        reset(b, "sid-b");
        mfa.failSignOuts.set(1);                 // A's sign-out fails, B's succeeds
        assertThat(guard.enforce()).isEqualTo(1);
        String ofA = token("sid-a", a.plusSeconds(2));
        assertThat(me(portal, ofA)).as("A's session after reset B").isEqualTo(401);
        assertThat(guard.enforce()).isEqualTo(1);   // A retried
        assertThat(me(portal, ofA)).as("A's session after reset B and the retry of A").isEqualTo(401);
    }

    /**
     * QA-D6-4 (Low): the revocation is stored per organization (candidate_account row), not per identity. An organization the
     * candidate joins after the reset (self-registration) never learns of it: a token of an ended session enrols the candidate
     * there and is accepted, until it expires (at most 5 minutes). Same root cause as the next test. Verified failing 2026-11-15.
     */
    @Disabled("QA-D6-4: fails until the revocation applies to the identity, not only to existing enrolments — enable with the fix")
    @Test
    void anEndedSessionCannotEnrolTheCandidateInAnotherOrganization() throws Exception {
        String old = token("sid-old", Instant.now().minusSeconds(120));
        reset(Instant.now().minusSeconds(5), "sid-new");
        guard.enforce();
        TestOrg later = new TestOrg(ownerJdbc(), mvc, "ki79q3");
        String portal3 = newPortal(later.tenant);
        assertThat(me(portal3, old)).as("an ended session at a portal the candidate never used").isEqualTo(401);
    }

    /** QA-D6-4: the guard only records the reset in ACTIVE and TRIAL organizations; one reactivated afterwards accepts old tokens. */
    @Disabled("QA-D6-4: fails until the revocation applies to the identity, not only to existing enrolments — enable with the fix")
    @Test
    void anOrganizationReactivatedAfterTheResetStillRefusesTheEndedSession() throws Exception {
        TestOrg second = new TestOrg(ownerJdbc(), mvc, "ki79q4");
        String portal2 = newPortal(second.tenant);
        String old = token("sid-old", Instant.now().minusSeconds(120));
        assertThat(me(portal2, old)).isEqualTo(200);
        String status = ownerJdbc().sql("SELECT status FROM tenant WHERE id = ?").param(second.tenant).query(String.class).single();
        ownerJdbc().sql("UPDATE tenant SET status = 'SUSPENDED' WHERE id = ?").param(second.tenant).update();
        reset(Instant.now().minusSeconds(5), "sid-new");
        guard.enforce();
        ownerJdbc().sql("UPDATE tenant SET status = ? WHERE id = ?").params(status, second.tenant).update();
        assertThat(me(portal, old)).isEqualTo(401);
        assertThat(me(portal2, old)).as("an organization reactivated after the reset").isEqualTo(401);
    }
}
