package com.ivorovi.exams;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ivorovi.exams.internal.TestCodes;
import com.ivorovi.platform.crypto.FieldCipher;
import com.ivorovi.support.IntegrationTest;
import com.ivorovi.support.TestOrg;
import com.ivorovi.support.TestTokens;
import java.net.ServerSocket;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Adversarial review of KI-89 (QA, Day 6): the durable account-level check-in guard with Valkey really unreachable (the
 * delivery process points at a closed port, so every Valkey layer fails as in an outage, not through a test double), and
 * complements {@link CheckInDurableGuardIT}: a burst of simultaneous guesses released at once, other candidates and other
 * organizations during a cooldown, expiry and recovery, and no session code in the audit trail or in the log output.
 * Short durations as in CheckInDurableGuardIT (5 failures, 2-second cooldown).
 */
@Tag("security-regression")
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {
        "ivorovi.process.role=DELIVERY",
        "ivorovi.exams.check-in.durable-failures=5",
        "ivorovi.exams.check-in.durable-window=PT15M",
        "ivorovi.exams.check-in.durable-cooldown=PT2S",
        "ivorovi.exams.check-in.durable-max-cooldown=PT5S"})
class CheckInGuardAdversarialIT extends IntegrationTest {

    /** Valkey down for this whole context: a port nothing listens on (connection refused). */
    @DynamicPropertySource
    static void valkeyDown(DynamicPropertyRegistry r) throws Exception {
        int closed;
        try (ServerSocket s = new ServerSocket(0)) {
            closed = s.getLocalPort();
        }
        int port = closed;
        // the URL takes precedence over the host and port the shared test base sets
        r.add("spring.data.redis.url", () -> "redis://127.0.0.1:" + port);
    }

    @Autowired FieldCipher cipher;
    @Autowired io.micrometer.core.instrument.MeterRegistry meters;

    /** Proof that Valkey really is unreachable in this context: the limiter failed open. */
    private void valkeyWasDown() {
        assertThat(meters.find("ivorovi.ratelimit.failopen").counters().stream().mapToDouble(c -> c.count()).sum()).as("rate limiter fail-open count").isPositive();
    }

    private TestOrg org;
    private String portal;
    private UUID session;
    private final List<String> codesUsed = new ArrayList<>();

    record Cand(String token, UUID id) {}

    @BeforeEach
    void setUp() {
        org = new TestOrg(ownerJdbc(), mvc, "ki89q");
        portal = "gq-" + UUID.randomUUID().toString().substring(0, 8);
        UUID portalId = DeliveryIT.portal(org.tenant, portal, "OFF", true);
        session = DeliveryIT.session(org.tenant, portalId, Instant.now().minus(Duration.ofMinutes(5)), Instant.now().plus(Duration.ofHours(3)));
    }

    private Cand candidate(String portalKey, String sub) throws Exception {
        String t = TestTokens.candidate(sub, b -> b.claim("email", "gq-" + sub.substring(0, 8) + "@cand.test").claim("email_verified", true).claim("sid", "sid-" + sub));
        String body = mvc.perform(get("/api/v1/delivery/portals/" + portalKey + "/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + t))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new Cand(t, UUID.fromString(body.replaceAll(".*\"candidateId\":\"([0-9a-f-]+)\".*", "$1")));
    }

    private Cand candidate() throws Exception {
        return candidate(portal, UUID.randomUUID().toString());
    }

    private String validCode(UUID tenant, UUID sessionId) {
        String code = TestCodes.generate();
        ownerJdbc().sql("INSERT INTO session_code (id, tenant_id, session_id, code_hash, hint, valid_from, valid_until) VALUES (?, ?, ?, ?, ?, ?, ?)")
                .params(UUID.randomUUID(), tenant, sessionId, cipher.blindIndex(tenant + ":" + code, "session-code"), code.substring(8),
                        Timestamp.from(Instant.now().minusSeconds(60)), Timestamp.from(Instant.now().plus(Duration.ofHours(3)))).update();
        String shown = code.substring(0, 5) + "-" + code.substring(5);
        codesUsed.add(shown);
        return shown;
    }

    private String wrongCode() {
        String c = TestCodes.generate();
        String shown = c.substring(0, 5) + "-" + c.substring(5);
        codesUsed.add(shown);
        return shown;
    }

    private MvcResult checkIn(String portalKey, Cand c, String code) throws Exception {
        return mvc.perform(post("/api/v1/delivery/portals/" + portalKey + "/check-ins").header(HttpHeaders.AUTHORIZATION, "Bearer " + c.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + code + "\",\"clockOffsetMs\":0,\"systemCheck\":{}}")).andReturn();
    }

    private static String code(MvcResult r) throws Exception {
        String b = r.getResponse().getContentAsString();
        return r.getResponse().getStatus() + ":" + (b.contains("\"code\":\"") ? b.replaceAll(".*\"code\":\"([a-z_]+)\".*", "$1") : "");
    }

    private long count(String sql, Object... params) {
        return ownerJdbc().sql(sql).params(params).query(Long.class).single();
    }

    private void noCodeAnywhere(CapturedOutput output) {
        String audit = ownerJdbc().sql("SELECT coalesce(string_agg(details::text, ' '), '') FROM audit_event WHERE tenant_id = ?").param(org.tenant).query(String.class).single();
        String failures = ownerJdbc().sql("SELECT coalesce(string_agg(last_reason, ' '), '') FROM check_in_failure WHERE tenant_id = ?").param(org.tenant).query(String.class).single();
        String logs = output.getAll();
        for (String c : codesUsed) {
            for (String form : List.of(c, c.replace("-", ""), c.replace("-", "").toLowerCase())) {
                assertThat(audit).as("audit trail").doesNotContain(form);
                assertThat(failures).as("failure records").doesNotContain(form);
                assertThat(logs).as("log output").doesNotContain(form);
            }
        }
    }

    @Test
    void withValkeyUnreachableCheckInStillWorksAndTheGuardStillHoldsThenRecovers(CapturedOutput output) throws Exception {
        Cand a = candidate(), b = candidate();
        long started = System.nanoTime();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < 12; i++) out.add(code(checkIn(portal, a, wrongCode())));
        long perRequestMs = (System.nanoTime() - started) / 12 / 1_000_000;
        assertThat(out.subList(0, 5)).containsOnly("409:code_not_valid");
        assertThat(out.subList(5, 12)).containsOnly("429:check_in_cooldown");
        assertThat(perRequestMs).as("a refused Valkey connection must not stall check-in (ms per request)").isLessThan(2000);
        // the valid code is refused during the cooldown; another candidate checks in normally
        String valid = validCode(org.tenant, session);
        assertThat(code(checkIn(portal, a, valid))).isEqualTo("429:check_in_cooldown");
        assertThat(code(checkIn(portal, b, validCode(org.tenant, session)))).isEqualTo("200:");
        // expiry and recovery: the cooldown ends by itself, the valid code then works and the history is gone
        Thread.sleep(2300);
        assertThat(code(checkIn(portal, a, valid))).isEqualTo("200:");
        assertThat(count("SELECT count(*) FROM check_in_guard WHERE tenant_id = ? AND candidate_id = ?", org.tenant, a.id())).isZero();
        noCodeAnywhere(output);
        valkeyWasDown();
    }

    @Test
    void aCooldownInOneOrganizationDoesNotLockTheSameIdentityOutOfAnother(CapturedOutput output) throws Exception {
        TestOrg second = new TestOrg(ownerJdbc(), mvc, "ki89q2");
        String portal2 = "gq2-" + UUID.randomUUID().toString().substring(0, 8);
        UUID portal2Id = DeliveryIT.portal(second.tenant, portal2, "OFF", true);
        UUID session2 = DeliveryIT.session(second.tenant, portal2Id, Instant.now().minus(Duration.ofMinutes(5)), Instant.now().plus(Duration.ofHours(3)));
        String sub = UUID.randomUUID().toString();
        Cand here = candidate(portal, sub), there = candidate(portal2, sub);
        for (int i = 0; i < 5; i++) checkIn(portal, here, wrongCode());
        assertThat(code(checkIn(portal, here, wrongCode()))).isEqualTo("429:check_in_cooldown");
        assertThat(code(checkIn(portal2, there, validCode(second.tenant, session2)))).isEqualTo("200:");
        // a code of the other organization is no code here, also during and after the cooldown
        String foreign = validCode(second.tenant, session2);
        Thread.sleep(2300);
        assertThat(code(checkIn(portal, here, foreign))).isEqualTo("409:code_not_valid");
        noCodeAnywhere(output);
        valkeyWasDown();
    }

    /** 48 guesses of one fresh account released at the same instant; returns the responses. */
    private List<String> burst(Cand e, int n) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String g = wrongCode();
            fs.add(pool.submit(() -> {
                go.await();
                return code(checkIn(portal, e, g));
            }));
        }
        go.countDown();
        List<String> out = new ArrayList<>();
        try {
            for (Future<String> f : fs) out.add(f.get());
        } finally {
            pool.shutdown();
        }
        return out;
    }

    /**
     * Burst with Valkey down: what the guard guarantees — no success, exactly one cooldown, every response "not valid" or
     * "cooldown", the failure records match. How many guesses were evaluated is printed (QA-D6-5: measured 47–48 of 48).
     */
    @Test
    void aBurstOfSimultaneousGuessesStartsOneCooldownAndNeverSucceeds(CapturedOutput output) throws Exception {
        Cand e = candidate();
        List<String> out = burst(e, 48);
        long evaluated = out.stream().filter(s -> s.equals("409:code_not_valid")).count();
        System.out.println("QA-KI89-BURST evaluated=" + evaluated + " of 48 (threshold 5)");
        assertThat(out).allMatch(s -> s.equals("409:code_not_valid") || s.equals("429:check_in_cooldown"));
        assertThat(count("SELECT count(*) FROM audit_event WHERE tenant_id = ? AND action = 'exam.check_in.cooldown_started' AND resource_id = ?",
                org.tenant, e.id().toString())).isEqualTo(1);
        assertThat(count("SELECT coalesce(sum(failures), 0) FROM check_in_failure WHERE tenant_id = ? AND candidate_id = ?", org.tenant, e.id())).isEqualTo(evaluated);
        noCodeAnywhere(output);
        valkeyWasDown();
    }

    /**
     * QA-D6-5 (Low): the cooldown check is a read before the attempt and the failure is counted after it, so simultaneous
     * guesses of one account are all evaluated: with Valkey down (the guard is then the only per-account layer) 47–48 of 48
     * simultaneous guesses were checked against the codes, threshold 5; the bound is the number of requests in flight, once per
     * cooldown. 49-bit codes keep the practical risk low. Verified failing 2026-11-15.
     */
    @org.junit.jupiter.api.Disabled("QA-D6-5: fails until a guess is counted (or reserved) before it is evaluated — enable with the fix")
    @Test
    void noMoreGuessesThanTheThresholdAreEvaluatedInABurst() throws Exception {
        long evaluated = burst(candidate(), 48).stream().filter(s -> s.equals("409:code_not_valid")).count();
        assertThat(evaluated).as("guesses evaluated in a burst of 48, threshold 5").isLessThanOrEqualTo(5);
    }
}
