package com.ivorovi.exams;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.ivorovi.exams.internal.RecoveryGuard;
import com.ivorovi.support.FakeCandidateMfa;
import com.ivorovi.support.IntegrationTest;
import com.ivorovi.support.TestOrg;
import com.ivorovi.support.TestTokens;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;

/**
 * KI-79 (decision D-16): a password reset with several active sessions. Sessions that existed before the reset become
 * unusable at once in the delivery API, also for access tokens that have not expired yet; the session the reset itself signed
 * in and sessions created after the reset keep working; a second reset ends everything before it, including the first
 * reset's session; requests running while the guard runs never let an old token through afterwards. The identity provider
 * side (Keycloak sessions ended, refresh refused) is covered against the real Keycloak by the exam E2E
 * {@code password-reset-sessions.spec.ts}; the guard's two Day 4 flaws by {@code ExamAdminIT}.
 */
@TestPropertySource(properties = "ivorovi.process.role=DELIVERY")
class PasswordResetSessionsIT extends IntegrationTest {

    @Autowired FakeCandidateMfa mfa;
    @Autowired RecoveryGuard guard;

    private TestOrg org;
    private String portal;
    private final String subject = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() throws Exception {
        org = new TestOrg(ownerJdbc(), mvc, "ki79");
        portal = "r-" + UUID.randomUUID().toString().substring(0, 8);
        DeliveryIT.portal(org.tenant, portal, "OFF", true);
        mfa.passwordResets.clear();
        // the account exists (first visit with a session that started long before any reset)
        assertThat(status(token("sid-first", Instant.now().minusSeconds(600)))).isEqualTo(200);
    }

    @AfterEach
    void clear() {
        mfa.passwordResets.clear();
    }

    String token(String sid, Instant issuedAt) {
        return TestTokens.candidate(subject, b -> b.claim("email", "r-" + subject.substring(0, 8) + "@cand.test").claim("email_verified", true).claim("sid", sid)
                .issuedAt(issuedAt).expiresAt(Instant.now().plusSeconds(300)));
    }

    int status(String token) throws Exception {
        return mvc.perform(get("/api/v1/delivery/portals/" + portal + "/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn().getResponse().getStatus();
    }

    int exams(String token) throws Exception {
        return mvc.perform(get("/api/v1/delivery/portals/" + portal + "/exams").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn().getResponse().getStatus();
    }

    @Test
    void sessionsBeforeTheResetBecomeUnusableAtOnceAndLaterOnesKeepWorking() throws Exception {
        Instant reset = Instant.now().minusSeconds(30);
        String laptop = token("sid-laptop", reset.minusSeconds(120));     // another device, token still valid for minutes
        String tablet = token("sid-tablet", reset.minusSeconds(5));
        String resetFlow = token("sid-reset", reset.plusMillis(200));     // the session the reset flow signed in
        String sameSecond = token("sid-reset", reset);                    // same session, token issued in the reset's second
        assertThat(List.of(status(laptop), status(tablet), exams(laptop))).containsOnly(200);

        mfa.passwordResets.add(new com.ivorovi.exams.CandidateMfa.PasswordReset(subject, reset, false, "sid-reset"));
        assertThat(guard.enforce()).isEqualTo(1);

        // ended sessions: refused immediately on every delivery endpoint, before their tokens expire
        assertThat(List.of(status(laptop), exams(laptop), status(tablet), exams(tablet))).containsOnly(401);
        assertThat(mvc.perform(get("/api/v1/delivery/portals/" + portal + "/exams").header(HttpHeaders.AUTHORIZATION, "Bearer " + laptop))
                .andReturn().getResponse().getContentAsString()).contains("session_ended");
        // the reset's own session and a new sign-in after the reset keep working
        assertThat(List.of(status(resetFlow), status(sameSecond), exams(resetFlow))).containsOnly(200);
        String newPhone = token("sid-phone", reset.plusSeconds(20));
        assertThat(status(newPhone)).isEqualTo(200);
        // the provider was asked to end the pre-reset sessions and keep the reset session
        assertThat(mfa.signedOutBefore).contains(new FakeCandidateMfa.SignOutBefore(subject, reset, "sid-reset"));

        // a second reset ends everything before it, including the first reset's session and the new phone
        Instant reset2 = Instant.now();
        mfa.passwordResets.add(new com.ivorovi.exams.CandidateMfa.PasswordReset(subject, reset2, false, "sid-reset-2"));
        assertThat(guard.enforce()).isEqualTo(1);
        assertThat(List.of(status(resetFlow), status(newPhone), status(laptop))).containsOnly(401);
        assertThat(status(token("sid-reset-2", reset2.plusMillis(100)))).isEqualTo(200);
        assertThat(status(token("sid-after-2", reset2.plusSeconds(2)))).isEqualTo(200);
    }

    @Test
    void requestsRunningWhileTheGuardRunsNeverLetAnOldTokenThroughAfterwards() throws Exception {
        Instant reset = Instant.now().minusSeconds(10);
        String old = token("sid-old", reset.minusSeconds(60));
        mfa.passwordResets.add(new com.ivorovi.exams.CandidateMfa.PasswordReset(subject, reset, false, "sid-new"));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<Integer>> during = new ArrayList<>();
        for (int i = 0; i < 40; i++) during.add(pool.submit(() -> exams(old)));
        guard.enforce();
        List<Integer> before = new ArrayList<>();
        for (Future<Integer> f : during) before.add(f.get());
        pool.shutdown();
        assertThat(before).allMatch(s -> s == 200 || s == 401);     // in flight: either side of the switch
        for (int i = 0; i < 20; i++) assertThat(exams(old)).isEqualTo(401);
    }

    @Test
    void theRevocationRuleAtTheSecondBoundary() {
        Instant reset = Instant.parse("2026-11-15T10:00:00.800Z");
        assertThat(com.ivorovi.exams.internal.CandidateServiceTestAccess.revoked(Instant.parse("2026-11-15T09:59:59Z"), "a", reset, "k")).isTrue();
        assertThat(com.ivorovi.exams.internal.CandidateServiceTestAccess.revoked(Instant.parse("2026-11-15T10:00:00Z"), "a", reset, "k")).isFalse();   // same second: allowed
        assertThat(com.ivorovi.exams.internal.CandidateServiceTestAccess.revoked(Instant.parse("2026-11-15T09:00:00Z"), "k", reset, "k")).isFalse();   // kept session
        assertThat(com.ivorovi.exams.internal.CandidateServiceTestAccess.revoked(null, "a", reset, "k")).isTrue();                                     // no iat: fail safe
    }
}
