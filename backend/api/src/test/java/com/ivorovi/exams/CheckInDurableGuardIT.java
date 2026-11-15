package com.ivorovi.exams;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ivorovi.exams.internal.TestCodes;
import com.ivorovi.platform.crypto.FieldCipher;
import com.ivorovi.platform.ratelimit.OutageRateLimiter;
import com.ivorovi.platform.ratelimit.RateLimiter;
import com.ivorovi.support.IntegrationTest;
import com.ivorovi.support.TestOrg;
import com.ivorovi.support.TestTokens;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

/**
 * KI-89 (decision D-15): the durable, account-level check-in guard in PostgreSQL. A cooldown after N failed attempts in a
 * window, enforced even when Valkey (every other limit layer) is down; atomic under concurrent guessing; recovery by expiry
 * or by a successful check-in; capped escalation; keyed by the signed-in account, so one account cannot lock out another;
 * no session code in the guard or the audit trail. Short durations for the test; production defaults are 15 failures in 15
 * minutes, a 15-minute cooldown doubling up to 2 hours.
 */
@Import(CheckInDurableGuardIT.Outage.class)
@TestPropertySource(properties = {
        "ivorovi.process.role=DELIVERY",
        "ivorovi.exams.check-in.durable-failures=5",
        "ivorovi.exams.check-in.durable-window=PT15M",
        "ivorovi.exams.check-in.durable-cooldown=PT2S",
        "ivorovi.exams.check-in.durable-max-cooldown=PT5S"})
class CheckInDurableGuardIT extends IntegrationTest {

    @TestConfiguration
    static class Outage {
        @Bean
        @Primary
        OutageRateLimiter outageRateLimiter(StringRedisTemplate redis, MeterRegistry meters) {
            return new OutageRateLimiter(redis, meters);
        }
    }

    @Autowired FieldCipher cipher;
    @Autowired StringRedisTemplate redis;
    @Autowired RateLimiter limiter;

    private TestOrg org;
    private String portal;
    private UUID session;

    record Cand(String token, UUID id) {}

    @BeforeEach
    void setUp() {
        flush();
        org = new TestOrg(ownerJdbc(), mvc, "ki89");
        portal = "g-" + UUID.randomUUID().toString().substring(0, 8);
        UUID portalId = DeliveryIT.portal(org.tenant, portal, "OFF", true);
        session = DeliveryIT.session(org.tenant, portalId, Instant.now().minus(Duration.ofMinutes(5)), Instant.now().plus(Duration.ofHours(3)));
    }

    @AfterEach
    void flush() {
        ((OutageRateLimiter) limiter).down.set(false);
        redis.delete(redis.keys("ivorovi:rl:*"));
    }

    Cand candidate() throws Exception {
        String sub = UUID.randomUUID().toString();
        String t = TestTokens.candidate(sub, b -> b.claim("email", "g-" + sub.substring(0, 8) + "@cand.test").claim("email_verified", true).claim("sid", "sid-" + sub));
        String body = mvc.perform(get("/api/v1/delivery/portals/" + portal + "/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + t))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new Cand(t, UUID.fromString(body.replaceAll(".*\"candidateId\":\"([0-9a-f-]+)\".*", "$1")));
    }

    String validCode() {
        String code = TestCodes.generate();
        ownerJdbc().sql("INSERT INTO session_code (id, tenant_id, session_id, code_hash, hint, valid_from, valid_until) VALUES (?, ?, ?, ?, ?, ?, ?)")
                .params(UUID.randomUUID(), org.tenant, session, cipher.blindIndex(org.tenant + ":" + code, "session-code"), code.substring(8),
                        Timestamp.from(Instant.now().minusSeconds(60)), Timestamp.from(Instant.now().plus(Duration.ofHours(3)))).update();
        return code.substring(0, 5) + "-" + code.substring(5);
    }

    static String wrongCode() {
        String c = TestCodes.generate();
        return c.substring(0, 5) + "-" + c.substring(5);
    }

    MvcResult checkIn(Cand c, String code) throws Exception {
        return mvc.perform(post("/api/v1/delivery/portals/" + portal + "/check-ins").header(HttpHeaders.AUTHORIZATION, "Bearer " + c.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + code + "\",\"clockOffsetMs\":0,\"systemCheck\":{}}")).andReturn();
    }

    static String code(MvcResult r) throws Exception {
        String b = r.getResponse().getContentAsString();
        return r.getResponse().getStatus() + ":" + (b.contains("\"code\":\"") ? b.replaceAll(".*\"code\":\"([a-z_]+)\".*", "$1") : "");
    }

    long count(String sql, Object... params) {
        return ownerJdbc().sql(sql).params(params).query(Long.class).single();
    }

    @Test
    void aCooldownAfterRepeatedFailuresThenRecoveryAndCappedEscalationWithoutLockingOutOthers() throws Exception {
        Cand a = candidate(), b = candidate();
        List<String> guesses = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String g = wrongCode();
            guesses.add(g);
            assertThat(code(checkIn(a, g))).isEqualTo("409:code_not_valid");
        }
        // the cooldown holds even with a valid code; another candidate on the same portal is unaffected
        String valid = validCode();
        assertThat(code(checkIn(a, valid))).isEqualTo("429:check_in_cooldown");
        assertThat(code(checkIn(b, validCode()))).isEqualTo("200:");
        assertThat(count("SELECT count(*) FROM audit_event WHERE tenant_id = ? AND action = 'exam.check_in.cooldown_started' AND resource_id = ?",
                org.tenant, a.id().toString())).isEqualTo(1);
        // no session code (or any guess) in the audit trail; the guard table has no code column at all
        String audit = ownerJdbc().sql("SELECT coalesce(string_agg(details::text, ' '), '') FROM audit_event WHERE tenant_id = ?").param(org.tenant).query(String.class).single();
        for (String g : guesses) assertThat(audit).doesNotContain(g).doesNotContain(g.replace("-", ""));
        assertThat(audit).doesNotContain(valid).doesNotContain(valid.replace("-", ""));
        assertThat(ownerJdbc().sql("SELECT string_agg(column_name, ',') FROM information_schema.columns WHERE table_name = 'check_in_guard'").query(String.class).single())
                .doesNotContain("code");

        // recovery: after the cooldown the valid code works and the failure history is gone
        Thread.sleep(2300);
        assertThat(code(checkIn(a, valid))).isEqualTo("200:");
        assertThat(count("SELECT count(*) FROM check_in_guard WHERE tenant_id = ? AND candidate_id = ?", org.tenant, a.id())).isZero();

        // escalation (a property of this layer, so measured with the Valkey layers off): a second cooldown in a row doubles
        // (2 s → 4 s), a third is capped at 5 s
        ((OutageRateLimiter) limiter).down.set(true);
        Cand c = candidate();
        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < 5; i++) assertThat(code(checkIn(c, wrongCode()))).isEqualTo("409:code_not_valid");
            assertThat(code(checkIn(c, wrongCode()))).isEqualTo("429:check_in_cooldown");
            if (round < 2) Thread.sleep(round == 0 ? 2300 : 4300);
        }
        assertThat(ownerJdbc().sql("SELECT string_agg(details->>'cooldownSeconds', ',' ORDER BY occurred_at) FROM audit_event WHERE tenant_id = ? AND action = 'exam.check_in.cooldown_started' AND resource_id = ?")
                .params(org.tenant, c.id().toString()).query(String.class).single()).isEqualTo("2,4,5");
    }

    @Test
    void theGuardHoldsWhileValkeyIsDown() throws Exception {
        ((OutageRateLimiter) limiter).down.set(true);   // every Valkey layer now fails open, as during an outage
        Cand d = candidate();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < 30; i++) out.add(code(checkIn(d, wrongCode())));
        assertThat(out.subList(0, 5)).containsOnly("409:code_not_valid");
        assertThat(out.subList(5, 30)).containsOnly("429:check_in_cooldown");
        // without the guard, the Valkey layers would have answered "rate_limited" after 10 attempts; they are fully off here
        assertThat(out).noneMatch(s -> s.contains("rate_limited"));
        assertThat(count("SELECT sum(failures) FROM check_in_failure WHERE tenant_id = ? AND candidate_id = ?", org.tenant, d.id())).isEqualTo(5);
    }

    @Test
    void concurrentGuessesAreCountedAtomically() throws Exception {
        ((OutageRateLimiter) limiter).down.set(true);
        Cand e = candidate();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Future<String>> fs = new ArrayList<>();
        for (int i = 0; i < 64; i++) fs.add(pool.submit(() -> code(checkIn(e, wrongCode()))));
        List<String> out = new ArrayList<>();
        for (Future<String> f : fs) out.add(f.get());
        pool.shutdown();
        assertThat(out).noneMatch(s -> s.startsWith("200"));
        // exactly one cooldown, started by the fifth counted failure; guesses already in flight when it started are still
        // answered as wrong, but no more than the in-flight count (16 threads) get past the guard
        assertThat(count("SELECT count(*) FROM audit_event WHERE tenant_id = ? AND action = 'exam.check_in.cooldown_started' AND resource_id = ?",
                org.tenant, e.id().toString())).isEqualTo(1);
        long evaluated = out.stream().filter(s -> s.equals("409:code_not_valid")).count();
        assertThat(evaluated).isBetween(5L, 5L + 16);
        assertThat(out.stream().filter(s -> s.equals("429:check_in_cooldown")).count()).isEqualTo(64 - evaluated);
    }

    @Test
    void aLegitimateCandidateBelowTheThresholdRecoversWithTheRightCode() throws Exception {
        Cand f = candidate();
        for (int i = 0; i < 4; i++) assertThat(code(checkIn(f, wrongCode()))).isEqualTo("409:code_not_valid");
        assertThat(code(checkIn(f, validCode()))).isEqualTo("200:");
        assertThat(count("SELECT count(*) FROM check_in_guard WHERE tenant_id = ? AND candidate_id = ?", org.tenant, f.id())).isZero();
    }
}
