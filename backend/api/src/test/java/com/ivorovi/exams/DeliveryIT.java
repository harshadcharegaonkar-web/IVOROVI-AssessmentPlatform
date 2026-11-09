package com.ivorovi.exams;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ivorovi.platform.crypto.FieldCipher;
import com.ivorovi.support.Fixture;
import com.ivorovi.support.FakeCandidateMfa;
import com.ivorovi.support.IntegrationTest;
import com.ivorovi.support.TestOrg;
import com.ivorovi.support.TestTokens;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The delivery process (P2-03, C-4): candidate-realm tokens only, staff endpoints absent, tenant from the portal key,
 * MFA policy, expiring single-use session codes (C-1), presence and acknowledged saves.
 */
@TestPropertySource(properties = {"ivorovi.process.role=DELIVERY", "ivorovi.delivery.code-attempts-per-ip-per-minute=60"})
class DeliveryIT extends IntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    @Autowired FieldCipher cipher;
    @Autowired FakeCandidateMfa mfa;

    private TestOrg org;
    private String portal;
    private UUID portalId;
    private UUID session;

    @BeforeEach
    void setUp() {
        org = new TestOrg(ownerJdbc(), mvc, "delivery");
        portal = "p-" + UUID.randomUUID().toString().substring(0, 8);
        portalId = portal(org.tenant, portal, "OPTIONAL", true);
        session = session(org.tenant, portalId, Instant.now().minus(Duration.ofMinutes(10)), Instant.now().plus(Duration.ofHours(2)));
    }

    // ------------------------------------------------------------------------------------------------ boundary

    @Test
    void staffEndpointsDoNotExistOnTheDeliveryProcess() throws Exception {
        for (String path : List.of("/api/v1/me", "/api/v1/students", "/api/v1/approvals", "/api/v1/exam/portals", "/api/v1/public/invitations/x",
                "/v3/api-docs")) {
            mvc.perform(get(path).headers(headers(auth(Fixture.ALICE_ORG_ADMIN_A)))).andExpect(status().isNotFound());
        }
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
    }

    @Test
    void staffTokensAndForgedCandidateTokensAreRejected() throws Exception {
        // staff token (staff issuer/key/audience) on a candidate endpoint
        mvc.perform(get("/api/v1/delivery/portals/" + portal + "/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.forCaller(Fixture.ALICE_ORG_ADMIN_A)))
                .andExpect(status().isUnauthorized());
        // staff issuer but signed with the candidate key
        String wrongIssuer = TestTokens.signedByCandidateKey("s-1", null, b -> b.audience(List.of("ivorovi-delivery")).claim("email", "a@b.test")
                .claim("email_verified", true));
        mvc.perform(me(wrongIssuer)).andExpect(status().isUnauthorized());
        // candidate issuer and key but the staff audience
        String wrongAudience = TestTokens.candidate("s-2", b -> b.audience(List.of("ivorovi-api")).claim("email", "a@b.test").claim("email_verified", true));
        mvc.perform(me(wrongAudience)).andExpect(status().isUnauthorized());
        // unverified email address
        String unverified = TestTokens.candidate("s-3", b -> b.claim("email", "a@b.test").claim("email_verified", false));
        mvc.perform(me(unverified)).andExpect(status().isUnauthorized());
        // expired
        String expired = TestTokens.candidate("s-4", b -> b.claim("email", "a@b.test").claim("email_verified", true)
                .issuedAt(Instant.now().minusSeconds(900)).expiresAt(Instant.now().minusSeconds(600)));
        mvc.perform(me(expired)).andExpect(status().isUnauthorized());
    }

    @Test
    void corsAllowsOnlyTheExamOriginAndSecurityHeadersAreSet() throws Exception {
        mvc.perform(options("/api/v1/delivery/portals/" + portal + "/me").header("Origin", "https://exam.test").header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "authorization"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "https://exam.test"));
        mvc.perform(options("/api/v1/delivery/portals/" + portal + "/me").header("Origin", "https://evil.test").header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/delivery/time").header("Origin", "http://localhost:5173"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/delivery/time"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    void portalResolvesTheOrganizationServerSide() throws Exception {
        mvc.perform(get("/api/v1/delivery/portals/" + portal)).andExpect(status().isOk())
                .andExpect(jsonPath("$.organizationName").value("delivery org"))
                .andExpect(jsonPath("$.mfaPolicy").value("OPTIONAL"));
        mvc.perform(get("/api/v1/delivery/portals/no-such-portal")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/delivery/portals/" + portal.toUpperCase())).andExpect(status().isNotFound());
        // a disabled portal and a suspended organization behave like an unknown one
        ownerJdbc().sql("UPDATE exam_portal SET status = 'DISABLED' WHERE id = ?").param(portalId).update();
        String other = "q-" + UUID.randomUUID().toString().substring(0, 8);
        portal(org.tenant, other, "OFF", true);
        ownerJdbc().sql("UPDATE tenant SET status = 'SUSPENDED' WHERE id = ?").param(org.tenant).update();
        mvc.perform(get("/api/v1/delivery/portals/" + other)).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------------------------------------ accounts + MFA

    @Test
    void firstVisitEnrolsTheCandidateAndAuditsAsTheCandidate() throws Exception {
        String sub = UUID.randomUUID().toString();
        JsonNode me = json(mvc.perform(me(TestTokens.candidate(sub, "asha@cand.test"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(me.path("email").asString()).isEqualTo("asha@cand.test");
        assertThat(me.path("mfa").path("satisfied").asBoolean()).isTrue();
        assertThat(me.path("sessions").size()).isEqualTo(1);
        String stored = ownerJdbc().sql("SELECT email_enc FROM candidate_account WHERE subject = ?").param(sub).query(String.class).single();
        assertThat(stored).startsWith("v1:").doesNotContain("asha");
        String actor = ownerJdbc().sql("SELECT actor_subject FROM audit_event WHERE tenant_id = ? AND action = 'exam.candidate.enrolled' AND resource_id = ?")
                .params(org.tenant, me.path("candidateId").asString()).query(String.class).single();
        assertThat(actor).isEqualTo("candidate:" + sub);
        // second visit links the same account
        mvc.perform(me(TestTokens.candidate(sub, "asha@cand.test"))).andExpect(jsonPath("$.candidateId").value(me.path("candidateId").asString()));
    }

    @Test
    void closedPortalsRefuseNewCandidatesAndSuspendedCandidatesAreRefused() throws Exception {
        String closed = "c-" + UUID.randomUUID().toString().substring(0, 8);
        portal(org.tenant, closed, "OFF", false);
        mvc.perform(get("/api/v1/delivery/portals/" + closed + "/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.candidate("x" + UUID.randomUUID(), "x@c.test")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("registration_closed"));
        String sub = UUID.randomUUID().toString();
        String token = TestTokens.candidate(sub, "s@c.test");
        mvc.perform(me(token)).andExpect(status().isOk());
        ownerJdbc().sql("UPDATE candidate_account SET status = 'SUSPENDED' WHERE subject = ?").param(sub).update();
        mvc.perform(me(token)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("candidate_suspended"));
    }

    @Test
    void requiredMfaBlocksEverythingButTheStatusUntilAnAuthenticatorIsEnrolled() throws Exception {
        ownerJdbc().sql("UPDATE exam_portal SET mfa_policy = 'REQUIRED' WHERE id = ?").param(portalId).update();
        String sub = UUID.randomUUID().toString();
        String token = TestTokens.candidate(sub, "m@c.test");
        mvc.perform(me(token)).andExpect(status().isOk()).andExpect(jsonPath("$.mfa.satisfied").value(false))
                .andExpect(jsonPath("$.mfa.policy").value("REQUIRED")).andExpect(jsonPath("$.sessions.length()").value(0));
        String code = code(org.tenant, session, Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600));
        mvc.perform(checkIn(token, code, "10.1.0.1")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("mfa_required"));
        assertThat(ownerJdbc().sql("SELECT consumed_at IS NULL FROM session_code WHERE session_id = ?").param(session).query(Boolean.class).single()).isTrue();
        // enrolled at the identity provider → satisfied
        mfa.enrolled.add(sub);
        mvc.perform(me(token)).andExpect(jsonPath("$.mfa.satisfied").value(true));
        mvc.perform(checkIn(token, code, "10.1.0.1")).andExpect(status().isOk());
        // a token whose authentication used OTP satisfies the policy without a lookup
        String otpSub = UUID.randomUUID().toString();
        mvc.perform(me(TestTokens.candidate(otpSub, "o@c.test", "pwd", "otp"))).andExpect(jsonPath("$.mfa.satisfied").value(true));
        // the refusal was audited
        assertThat(ownerJdbc().sql("SELECT count(*) FROM audit_event WHERE tenant_id = ? AND action = 'exam.candidate.mfa_required' AND outcome = 'DENIED'")
                .param(org.tenant).query(Long.class).single()).isEqualTo(1);
    }

    // ------------------------------------------------------------------------------------------------ session codes

    @Test
    void sessionCodesAreStoredOnlyAsKeyedHashes() throws Exception {
        String code = code(org.tenant, session, Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600));
        String raw = code.replace("-", "");
        List<String> stored = ownerJdbc().sql("SELECT code_hash || ' ' || hint FROM session_code WHERE session_id = ?").param(session).query(String.class).list();
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0)).doesNotContain(raw).hasSize(64 + 1 + 2);
        // a plain (unkeyed) SHA-256 of the code is not what is stored
        String plain = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(raw.getBytes()));
        assertThat(stored.get(0)).doesNotStartWith(plain);
    }

    @Test
    void aCodeIsConsumedExactlyOnceUnderConcurrency() throws Exception {
        String code = code(org.tenant, session, Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600));
        int n = 100;
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String sub = UUID.randomUUID().toString();
            String t = TestTokens.candidate(sub, "c" + i + "@c.test");
            mvc.perform(me(t)).andExpect(status().isOk());
            tokens.add(t);
        }
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        AtomicInteger limited = new AtomicInteger();
        List<Future<?>> all = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String t = tokens.get(i);
            String ip = "10.2." + (i / 250) + "." + (i % 250);
            all.add(pool.submit((Callable<Void>) () -> {
                start.await();
                int s = mvc.perform(checkIn(t, code, ip)).andReturn().getResponse().getStatus();
                if (s == 200) ok.incrementAndGet(); else if (s == 409) refused.incrementAndGet(); else if (s == 429) limited.incrementAndGet();
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : all) f.get();
        pool.shutdown();
        // exactly one winner; every other attempt is refused as used (409) or, once five failures hit the code, throttled (429)
        assertThat(ok.get()).isEqualTo(1);
        assertThat(refused.get() + limited.get()).isEqualTo(n - 1);
        assertThat(refused.get()).isGreaterThanOrEqualTo(5);
        assertThat(ownerJdbc().sql("SELECT count(*) FROM check_in WHERE session_id = ?").param(session).query(Long.class).single()).isEqualTo(1);
        assertThat(ownerJdbc().sql("SELECT count(*) FROM session_code WHERE session_id = ? AND consumed_at IS NOT NULL").param(session).query(Long.class).single())
                .isEqualTo(1);
        assertThat(ownerJdbc().sql("SELECT count(*) FROM audit_event WHERE tenant_id = ? AND action = 'exam.session_code.refused'").param(org.tenant)
                .query(Long.class).single()).isEqualTo(refused.get());
    }

    @Test
    void codesAreRefusedOutsideTheirWindowWhenRevokedOrOnAnotherOrganizationsPortal() throws Exception {
        String token = enrolled();
        String early = code(org.tenant, session, Instant.now().plusSeconds(600), Instant.now().plusSeconds(3600));
        String late = code(org.tenant, session, Instant.now().minusSeconds(3600), Instant.now().minusSeconds(60));
        String revoked = code(org.tenant, session, Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600));
        ownerJdbc().sql("UPDATE session_code SET revoked_at = now() WHERE session_id = ? AND valid_from < now() AND valid_until > now()").param(session).update();
        for (String c : List.of(early, late, revoked, "ABCDE-FGHJK", "not a code")) {
            mvc.perform(checkIn(token, c, "10.3.0.1")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("code_not_valid"));
        }
        // tenant isolation: a valid code of organization B is not usable through organization A's portal
        TestOrg other = new TestOrg(ownerJdbc(), mvc, "other");
        UUID otherPortal = portal(other.tenant, "o-" + UUID.randomUUID().toString().substring(0, 8), "OFF", true);
        UUID otherSession = session(other.tenant, otherPortal, Instant.now().minusSeconds(600), Instant.now().plusSeconds(3600));
        String foreign = code(other.tenant, otherSession, Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600));
        mvc.perform(checkIn(token, foreign, "10.3.0.2")).andExpect(status().isConflict());
        assertThat(ownerJdbc().sql("SELECT consumed_at IS NULL FROM session_code WHERE session_id = ?").param(otherSession).query(Boolean.class).single()).isTrue();
        // a cancelled session's codes stop working
        String good = code(org.tenant, session, Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600));
        ownerJdbc().sql("UPDATE readiness_session SET status = 'CANCELLED' WHERE id = ?").param(session).update();
        mvc.perform(checkIn(token, good, "10.3.0.3")).andExpect(status().isConflict());
    }

    @Test
    void attemptsAreRateLimitedPerCandidateAndPerCode() throws Exception {
        String token = enrolled();
        int limited = 0;
        for (int i = 0; i < 12; i++) {
            int s = mvc.perform(checkIn(token, "ZZZZZ-ZZZZ" + "ABCDEFGHJK".charAt(i % 10), "10.4.0." + i)).andReturn().getResponse().getStatus();
            if (s == 429) limited++;
        }
        assertThat(limited).isEqualTo(2); // 10 per candidate per minute
        // per code: five attempts on the same code from different candidates and addresses, then refused
        String code = "QQQQQ-QQQQQ";
        List<String> others = new ArrayList<>();
        for (int i = 0; i < 7; i++) others.add(enrolled());
        int perCode = 0;
        for (int i = 0; i < 7; i++) {
            if (mvc.perform(checkIn(others.get(i), code, "10.5.0." + i)).andReturn().getResponse().getStatus() == 429) perCode++;
        }
        assertThat(perCode).isEqualTo(2); // five failed attempts per code per minute
        assertThat(ownerJdbc().sql("SELECT count(*) FROM audit_event WHERE tenant_id = ? AND action = 'exam.session_code.rate_limited'").param(org.tenant)
                .query(Long.class).single()).isEqualTo(4);
    }

    @Test
    void attemptsAreRateLimitedPerAddress() throws Exception {
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < 70; i++) tokens.add(enrolled());
        int limited = 0;
        for (String t : tokens) {   // fresh candidate and fresh code each time: only the per-address bucket can trip
            if (mvc.perform(checkIn(t, com.ivorovi.exams.internal.TestCodes.generate(), "10.6.0.1")).andReturn().getResponse().getStatus() == 429) limited++;
        }
        assertThat(limited).isGreaterThanOrEqualTo(5); // 60 per address per minute (plus refill while the loop runs)
    }

    // ------------------------------------------------------------------------------------------------ presence + saves

    @Test
    void presenceAndSavesNeedACheckInAndStayInsideTheOrganization() throws Exception {
        String token = enrolled();
        mvc.perform(post("/api/v1/delivery/portals/" + portal + "/presence").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"sessionId\":\"" + session + "\"}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("not_checked_in"));
        String code = code(org.tenant, session, Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600));
        mvc.perform(checkIn(token, code.toLowerCase().replace("-", " "), "10.7.0.1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(session.toString()));
        mvc.perform(checkIn(token, code(org.tenant, session, Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600)), "10.7.0.1"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("already_checked_in"));
        // the second code was not consumed (rolled back with the duplicate check-in)
        assertThat(ownerJdbc().sql("SELECT count(*) FROM session_code WHERE session_id = ? AND consumed_at IS NULL").param(session).query(Long.class).single())
                .isEqualTo(1);
        for (int i = 1; i <= 3; i++) {
            mvc.perform(post("/api/v1/delivery/portals/" + portal + "/presence").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"sessionId\":\"" + session + "\"}")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.beats").value(i));
        }
        mvc.perform(post("/api/v1/delivery/portals/" + portal + "/saves").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"sessionId\":\"" + session + "\",\"seq\":7,\"payload\":\"x\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.seq").value(7)).andExpect(jsonPath("$.saves").value(1));
        // the same candidate subject on another organization's portal is a different, unlinked account; A's session id is invisible there
        TestOrg other = new TestOrg(ownerJdbc(), mvc, "other2");
        String otherKey = "r-" + UUID.randomUUID().toString().substring(0, 8);
        portal(other.tenant, otherKey, "OFF", true);
        mvc.perform(get("/api/v1/delivery/portals/" + otherKey + "/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.sessions.length()").value(0));
        mvc.perform(post("/api/v1/delivery/portals/" + otherKey + "/presence").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"sessionId\":\"" + session + "\"}")).andExpect(status().isConflict());
    }

    @org.springframework.beans.factory.annotation.Autowired com.ivorovi.exams.ExamSessions examSessions;

    @Test
    void invitedEmailsFindTheirCandidateThroughTheBlindIndexOnly() throws Exception {
        String email = "Mixed.Case" + UUID.randomUUID().toString().substring(0, 6) + "@c.test";
        String t = TestTokens.candidate(UUID.randomUUID().toString(), email);
        mvc.perform(me(t)).andExpect(status().isOk());
        String stored = ownerJdbc().sql("SELECT email_index FROM candidate_account WHERE tenant_id = ? ORDER BY created_at DESC LIMIT 1").param(org.tenant)
                .query(String.class).single();
        assertThat(stored).hasSize(64).doesNotContainIgnoringCase("mixed");
        com.ivorovi.platform.tenancy.TenantContext.runInTenant(org.tenant, () -> {
            assertThat(examSessions.candidateByEmail(portalId, "  " + email.toLowerCase() + " ")).isPresent();
            assertThat(examSessions.candidateByEmail(portalId, "someone.else@c.test")).isEmpty();
            assertThat(examSessions.candidateByEmail(UUID.randomUUID(), email)).isEmpty();
        });
        // another organization never finds this candidate (tenant-bound index and RLS)
        TestOrg other = new TestOrg(ownerJdbc(), mvc, "other-idx");
        com.ivorovi.platform.tenancy.TenantContext.runInTenant(other.tenant, () -> assertThat(examSessions.candidateByEmail(portalId, email)).isEmpty());
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private String enrolled() throws Exception {
        String t = TestTokens.candidate(UUID.randomUUID().toString(), "e" + UUID.randomUUID().toString().substring(0, 6) + "@c.test");
        mvc.perform(me(t)).andExpect(status().isOk());
        return t;
    }

    private MockHttpServletRequestBuilder me(String token) {
        return get("/api/v1/delivery/portals/" + portal + "/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private MockHttpServletRequestBuilder checkIn(String token, String code, String ip) {
        return post("/api/v1/delivery/portals/" + portal + "/check-ins").header(HttpHeaders.AUTHORIZATION, "Bearer " + token).with(remote(ip))
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + code + "\",\"clockOffsetMs\":120,\"systemCheck\":{\"browser\":\"chromium\","
                        + "\"online\":true,\"email\":\"never-stored@x.test\"}}");
    }

    private static RequestPostProcessor remote(String ip) {
        return (MockHttpServletRequest r) -> {
            r.setRemoteAddr(ip);
            return r;
        };
    }

    private static HttpHeaders headers(java.util.Map<String, String> m) {
        HttpHeaders h = new HttpHeaders();
        m.forEach(h::add);
        return h;
    }

    static UUID portal(UUID tenant, String key, String mfa, boolean selfRegistration) {
        UUID id = UUID.randomUUID();
        ownerJdbc().sql("INSERT INTO exam_portal (id, tenant_id, portal_key, name, mfa_policy, self_registration, created_by) VALUES (?, ?, ?, ?, ?, ?, ?)")
                .params(id, tenant, key, "Portal " + key, mfa, selfRegistration, UUID.randomUUID()).update();
        return id;
    }

    static UUID session(UUID tenant, UUID portal, Instant start, Instant end) {
        UUID id = UUID.randomUUID();
        ownerJdbc().sql("INSERT INTO readiness_session (id, tenant_id, portal_id, name, starts_at, ends_at, created_by) VALUES (?, ?, ?, ?, ?, ?, ?)")
                .params(id, tenant, portal, "Readiness check", Timestamp.from(start), Timestamp.from(end), UUID.randomUUID()).update();
        return id;
    }

    private String code(UUID tenant, UUID session, Instant from, Instant until) {
        String code = com.ivorovi.exams.internal.TestCodes.generate();
        ownerJdbc().sql("INSERT INTO session_code (id, tenant_id, session_id, code_hash, hint, valid_from, valid_until) VALUES (?, ?, ?, ?, ?, ?, ?)")
                .params(UUID.randomUUID(), tenant, session, cipher.blindIndex(tenant + ":" + code, "session-code"), code.substring(8),
                        Timestamp.from(from), Timestamp.from(until)).update();
        return code.substring(0, 5) + "-" + code.substring(5);
    }

    private static JsonNode json(String body) {
        return JSON.readTree(body);
    }
}
