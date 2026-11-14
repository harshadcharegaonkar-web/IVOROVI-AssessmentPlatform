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
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Follow-up A (KI-66) acceptance tests 1–4: a whole hall behind one campus NAT address checks in, attackers on the same
 * address and distributed attackers are stopped by the identity, device and code layers, and the address layer only
 * delays accounts without a check-in (never refuses, nothing at all on a registered venue network). Test 5 (Valkey
 * unavailable) is {@code com.ivorovi.platform.ratelimit.RateLimiterFailOpenIT}. Results of the shared-address runs are
 * written to {@code build/reports/ki66/} and recorded in {@code docs/performance/check-in-layered-limits.md}.
 *
 * <p>Limits here: address 100 failed/min (default 300), per code 25 failed/min and 20 failed/day, expected-failure ratio
 * 0.02 so the L4 alarm threshold of a 1,000-candidate session is 60 failures/min; address delay 5 ms per excess attempt
 * up to 50 ms (defaults 250 ms / 5 s), so the runs stay short.
 */
@TestPropertySource(properties = {
        "ivorovi.process.role=DELIVERY",
        "ivorovi.delivery.code-attempts-per-ip-per-minute=100",
        "ivorovi.delivery.code-attempts-per-code-per-minute=25",
        "ivorovi.exams.check-in.failed-per-code-per-day=20",
        "ivorovi.exams.check-in.expected-failure-ratio=0.02",
        "ivorovi.exams.check-in.address-delay-step=PT0.005S",
        "ivorovi.exams.check-in.address-max-delay=PT0.05S",
        "ivorovi.rate-limit.failed-auth-per-ip-per-minute=5"})
class LayeredCheckInLimitsIT extends IntegrationTest {

    private static final String NAT = "203.0.113.10";
    private static final double CODE_SPACE = Math.pow(31, 10);

    @Autowired FieldCipher cipher;
    @Autowired StringRedisTemplate redis;
    @Autowired ExamsProperties exams;

    private TestOrg org;
    private String portal;
    private UUID portalId;
    private UUID session;

    record Candidate(String token, UUID id) {}

    record Outcome(int status, long millis, String body) {}

    @BeforeEach
    void setUp() {
        flush();
        ownerJdbc().sql("DELETE FROM platform_signal").update();
        org = new TestOrg(ownerJdbc(), mvc, "ki66");
        portal = "h-" + UUID.randomUUID().toString().substring(0, 8);
        portalId = DeliveryIT.portal(org.tenant, portal, "OFF", true);
        session = DeliveryIT.session(org.tenant, portalId, Instant.now().minus(Duration.ofMinutes(5)), Instant.now().plus(Duration.ofHours(3)));
    }

    @AfterEach
    void flush() {
        redis.delete(redis.keys("ivorovi:rl:*"));
    }

    // ------------------------------------------------------------------------------------------------ test 1

    @Test
    void fiveHundredCandidatesBehindOneAddressCheckInWithinAMinute() throws Exception {
        sharedAddress(500, Duration.ofSeconds(60));
    }

    @Test
    void aThousandCandidatesBehindOneAddressCheckInWithinTwoMinutes() throws Exception {
        sharedAddress(1000, Duration.ofMinutes(2));
    }

    private void sharedAddress(int n, Duration budget) throws Exception {
        List<Candidate> hall = enrol(n, "hall");
        List<String> codes = codes(n, Instant.now().minusSeconds(60), Instant.now().plus(Duration.ofHours(3)));
        // every candidate first loads the portal and the server time, from the same address
        parallel(n, i -> mvc.perform(get("/api/v1/delivery/portals/" + portal).with(from(NAT))).andExpect(status().isOk()));
        parallel(n, i -> mvc.perform(get("/api/v1/delivery/time").with(from(NAT))).andExpect(status().isOk()));
        long start = System.nanoTime();
        List<Outcome> out = parallel(n, i -> checkIn(hall.get(i).token(), codes.get(i), NAT));
        Duration took = Duration.ofNanos(System.nanoTime() - start);
        assertThat(out).allMatch(o -> o.status() == 200);
        assertThat(took).isLessThan(budget);
        assertThat(count("SELECT count(*) FROM check_in WHERE session_id = ?", session)).isEqualTo(n);
        assertThat(count("SELECT count(*) FROM audit_event WHERE tenant_id = ? AND outcome = 'DENIED'", org.tenant)).isZero();
        report("shared-address-" + n, Map.of("candidates", n, "address", NAT, "succeeded", out.stream().filter(o -> o.status() == 200).count(),
                "refused429", out.stream().filter(o -> o.status() == 429).count(), "wallClockMs", took.toMillis(),
                "p50Ms", percentile(out, 0.50), "p95Ms", percentile(out, 0.95), "maxMs", percentile(out, 1.0)));
    }

    // ------------------------------------------------------------------------------------------------ test 2 + 4

    @Test
    void attackersOnTheSameAddressAreStoppedWhileTheHallChecksIn() throws Exception {
        Result r = hallWithAttackers(1000, 20, false);
        assertThat(r.legit()).allMatch(o -> o.status() == 200);
        // each attacker: at most 10 attempts per minute get through (L1); the rest are 429 for that account only
        for (List<Outcome> a : r.attackers()) {
            assertThat(a.stream().filter(o -> o.status() != 429).count()).isLessThanOrEqualTo(10);
            assertThat(a).noneMatch(o -> o.status() == 200);
        }
        // the shared dictionary guess was locked out at the code layer with the uniform answer
        assertThat(count("SELECT count(*) FROM audit_event WHERE tenant_id = ? AND action = 'exam.session_code.rate_limited' AND details->>'bucket' = 'code-day'",
                org.tenant)).isPositive();
        // address layer engaged (alert) and delayed only accounts without a check-in; nobody was refused because of it
        assertThat(signals("ADDRESS_THROTTLED")).isPositive();
        assertThat(count("SELECT count(*) FROM audit_event WHERE tenant_id = ? AND details->>'bucket' = 'address'", org.tenant)).isZero();
        assertThat(count("SELECT count(*) FROM audit_event WHERE tenant_id = ? AND details->>'delayedMs' IS NOT NULL", org.tenant)).isPositive();
        // L4: far more failures than 1,000 expected candidates explain
        assertThat(signals("CHECK_IN_ANOMALY")).isPositive();
        assertThat(count("SELECT count(*) FROM audit_event WHERE tenant_id = ? AND action = 'exam.check_in.anomaly'", org.tenant)).isPositive();
        // every refusal in the audit trail belongs to an attacker
        assertRefusalsOnlyFor(r.attackerIds());
        report("shared-address-attackers", Map.of("candidates", 1000, "attackers", 20, "legitSucceeded", r.legit().stream().filter(o -> o.status() == 200).count(),
                "legitP95Ms", percentile(r.legit(), 0.95), "attackerAttempts", r.attackers().stream().mapToLong(List::size).sum(),
                "attackerAttemptsPastL1", r.attackers().stream().flatMap(List::stream).filter(o -> o.status() != 429).count(),
                "wallClockMs", r.millis()));
    }

    @Test
    void onARegisteredVenueNetworkTheAddressLayerOnlyReports() throws Exception {
        ownerJdbc().sql("INSERT INTO exam_venue_network (id, tenant_id, session_id, network, label, created_by) VALUES (?, ?, ?, CAST('203.0.113.0/24' AS cidr), 'Hall A', ?)")
                .params(UUID.randomUUID(), org.tenant, session, UUID.randomUUID()).update();
        Result r = hallWithAttackers(200, 20, true);
        assertThat(r.legit()).allMatch(o -> o.status() == 200);
        for (List<Outcome> a : r.attackers()) assertThat(a.stream().filter(o -> o.status() != 429).count()).isLessThanOrEqualTo(10);
        assertThat(count("SELECT count(*) FROM audit_event WHERE tenant_id = ? AND details->>'delayedMs' IS NOT NULL", org.tenant)).isZero();
        assertThat(ownerJdbc().sql("SELECT bool_and((detail->>'venue')::boolean) FROM platform_signal WHERE kind = 'ADDRESS_THROTTLED' AND portal_id = ?")
                .param(portalId).query(Boolean.class).single()).isTrue();
        assertRefusalsOnlyFor(r.attackerIds());
    }

    record Result(List<Outcome> legit, List<List<Outcome>> attackers, Set<UUID> attackerIds, long millis) {}

    private Result hallWithAttackers(int n, int attackers, boolean venue) throws Exception {
        List<Candidate> hall = enrol(n, "hall");
        List<Candidate> bad = enrol(attackers, "attacker");
        List<String> codes = codes(n, Instant.now().minusSeconds(60), Instant.now().plus(Duration.ofHours(3)));
        String dictionary = TestCodes.generate();
        List<List<Outcome>> attacks = new CopyOnWriteArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(48);
        long start = System.nanoTime();
        List<Future<?>> attacking = new ArrayList<>();
        for (Candidate a : bad) {
            attacking.add(pool.submit((Callable<Void>) () -> {
                List<Outcome> mine = new ArrayList<>();
                for (int i = 0; i < 30; i++) mine.add(checkIn(a.token(), i % 2 == 0 ? dictionary : TestCodes.generate(), NAT));
                attacks.add(mine);
                return null;
            }));
        }
        List<Future<Outcome>> legit = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int k = i;
            legit.add(pool.submit(() -> checkIn(hall.get(k).token(), codes.get(k), NAT)));
        }
        for (Future<?> f : attacking) f.get();
        List<Outcome> out = new ArrayList<>();
        for (Future<Outcome> f : legit) out.add(f.get());
        long millis = Duration.ofNanos(System.nanoTime() - start).toMillis();
        pool.shutdown();
        Set<UUID> ids = new HashSet<>();
        bad.forEach(c -> ids.add(c.id()));
        return new Result(out, attacks, ids, millis);
    }

    // ------------------------------------------------------------------------------------------------ test 3

    @Test
    void distributedGuessingLocksCodesRaisesTheAlarmAndStaysBelowOneInAMillion() throws Exception {
        int codesIssued = 1000;
        codes(codesIssued - 1, Instant.now().minusSeconds(60), Instant.now().plus(Duration.ofHours(3)));
        // one real code that is not valid yet (released at the start of the exam): attackers will hit it and fail
        String early = codes(1, Instant.now().plus(Duration.ofMinutes(30)), Instant.now().plus(Duration.ofHours(3))).get(0);
        List<Candidate> accounts = enrol(1000, "dist");                      // 200 addresses x 5 accounts
        List<Outcome> out = parallel(accounts.size() * 4, i -> {
            Candidate a = accounts.get(i / 4);
            String ip = "198.51." + (100 + (i / 4) / 5 / 250) + "." + ((i / 4) / 5 % 250);
            return checkIn(a.token(), i % 4 == 0 ? early : TestCodes.generate(), ip);
        });
        assertThat(out).noneMatch(o -> o.status() == 200);
        // nothing is refused because of an address (20 failures per address, under the threshold) or a candidate limit (4 each)
        assertThat(out).noneMatch(o -> o.status() == 429);
        assertThat(out).allMatch(o -> o.status() == 409 && o.body().contains("code_not_valid"));
        // the guessed real code is locked once its daily budget (20 failures) is spent, so it stays unusable even when valid
        assertThat(ownerJdbc().sql("SELECT locked_at IS NOT NULL FROM session_code WHERE session_id = ? AND valid_from > now()").param(session)
                .query(Boolean.class).single()).isTrue();
        assertThat(count("SELECT count(*) FROM audit_event WHERE tenant_id = ? AND action = 'exam.session_code.locked'", org.tenant)).isEqualTo(1);
        assertThat(signals("CHECK_IN_ANOMALY")).isPositive();
        assertThat(signals("ADDRESS_THROTTLED")).isZero();
        // expected successful guesses in the session, from the limits: every account may fail at most failedPerCandidatePerDay
        // times a day, each guess hits one of the issued codes with probability codes / 31^10
        double expected = accounts.size() * (double) exams.checkIn().failedPerCandidatePerDay() * codesIssued / CODE_SPACE;
        assertThat(expected).isLessThan(1e-6);
        report("distributed-guessing", Map.of("addresses", 200, "accounts", accounts.size(), "attempts", out.size(), "successfulGuesses", 0,
                "codesIssued", codesIssued, "expectedSuccessfulGuessesPerDay", expected, "earlyCodeLocked", true));
    }

    // ------------------------------------------------------------------------------------------------ L3, L1-day, public lookups

    @Test
    void severalAccountsOnOneDeviceShareTheDeviceBudget() throws Exception {
        String sid = "browser-" + UUID.randomUUID();
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String t = TestTokens.candidate(UUID.randomUUID().toString(), b -> b.claim("email", "d" + UUID.randomUUID() + "@c.test").claim("email_verified", true)
                    .claim("sid", sid));
            mvc.perform(get("/api/v1/delivery/portals/" + portal + "/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + t)).andExpect(status().isOk());
            tokens.add(t);
        }
        int device = 0;
        for (String t : tokens) {
            for (int i = 0; i < 10; i++) {
                Outcome o = checkIn(t, TestCodes.generate(), "192.0.2." + (i + 1));
                if (o.status() == 429 && o.body().contains("rate_limited")) device++;
            }
        }
        assertThat(device).isBetween(8, 10); // 40 attempts, 30 failed per device per minute (plus refill while the loop runs)
        assertThat(count("SELECT count(*) FROM audit_event WHERE tenant_id = ? AND details->>'bucket' = 'device'", org.tenant)).isEqualTo(device);
        // a fresh device of a fresh account is unaffected
        assertThat(checkIn(enrol(1, "fresh").get(0).token(), TestCodes.generate(), "192.0.2.1").status()).isEqualTo(409);
    }

    @Test
    void portalLookupsFromOneAddressAreNotLimitedButUnknownPortalsAre() throws Exception {
        List<Outcome> misses = new ArrayList<>();
        for (int i = 0; i < 70; i++) {
            misses.add(new Outcome(mvc.perform(get("/api/v1/delivery/portals/nope-" + i).with(from(NAT))).andReturn().getResponse().getStatus(), 0, ""));
        }
        // 60 unknown keys per address per minute (plus refill while the loop runs), then 429 for unknown keys only
        assertThat(misses.stream().filter(o -> o.status() == 404).count()).isBetween(60L, 64L);
        assertThat(misses.stream().filter(o -> o.status() == 429).count()).isGreaterThanOrEqualTo(6);
        // the real portal still answers the same address
        mvc.perform(get("/api/v1/delivery/portals/" + portal).with(from(NAT))).andExpect(status().isOk());
    }

    @Test
    void failedAuthenticationsFromAnAddressNeverLockOutValidCandidates() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(get("/api/v1/delivery/portals/" + portal + "/me").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-token").with(from(NAT)))
                    .andExpect(status().isUnauthorized());
        }
        Candidate c = enrol(1, "valid").get(0);
        mvc.perform(get("/api/v1/delivery/portals/" + portal + "/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + c.token()).with(from(NAT)))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private void assertRefusalsOnlyFor(Set<UUID> attackers) {
        List<UUID> actors = ownerJdbc().sql("SELECT DISTINCT actor_id FROM audit_event WHERE tenant_id = ? AND outcome = 'DENIED'").param(org.tenant)
                .query(UUID.class).list();
        assertThat(actors).isNotEmpty().allMatch(attackers::contains);
    }

    private List<Candidate> enrol(int n, String label) throws Exception {
        List<Candidate> out = Collections.synchronizedList(new ArrayList<>(Collections.nCopies(n, (Candidate) null)));
        parallel(n, i -> {
            String sub = UUID.randomUUID().toString();
            String t = TestTokens.candidate(sub, b -> b.claim("email", label + i + "-" + sub.substring(0, 6) + "@cand.test").claim("email_verified", true)
                    .claim("sid", "sid-" + sub));
            String body = mvc.perform(get("/api/v1/delivery/portals/" + portal + "/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + t))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            out.set(i, new Candidate(t, UUID.fromString(body.replaceAll(".*\"candidateId\":\"([0-9a-f-]+)\".*", "$1"))));
            return null;
        });
        return out;
    }

    private List<String> codes(int n, Instant from, Instant until) {
        List<String> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String code = TestCodes.generate();
            ownerJdbc().sql("INSERT INTO session_code (id, tenant_id, session_id, code_hash, hint, valid_from, valid_until) VALUES (?, ?, ?, ?, ?, ?, ?)")
                    .params(UUID.randomUUID(), org.tenant, session, cipher.blindIndex(org.tenant + ":" + code, "session-code"), code.substring(8),
                            Timestamp.from(from), Timestamp.from(until)).update();
            out.add(code.substring(0, 5) + "-" + code.substring(5));
        }
        return out;
    }

    private Outcome checkIn(String token, String code, String ip) throws Exception {
        long t0 = System.nanoTime();
        MvcResult r = mvc.perform(post("/api/v1/delivery/portals/" + portal + "/check-ins").header(HttpHeaders.AUTHORIZATION, "Bearer " + token).with(from(ip))
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + code + "\",\"clockOffsetMs\":0,\"systemCheck\":{}}")).andReturn();
        return new Outcome(r.getResponse().getStatus(), (System.nanoTime() - t0) / 1_000_000, r.getResponse().getContentAsString());
    }

    interface Work<T> {
        T run(int i) throws Exception;
    }

    private static <T> List<T> parallel(int n, Work<T> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(32);
        try {
            List<Future<T>> fs = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                int k = i;
                fs.add(pool.submit(() -> work.run(k)));
            }
            List<T> out = new ArrayList<>(n);
            for (Future<T> f : fs) out.add(f.get());
            return out;
        } finally {
            pool.shutdown();
        }
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor from(String ip) {
        return (MockHttpServletRequest r) -> {
            r.setRemoteAddr(ip);
            return r;
        };
    }

    private long count(String sql, Object param) {
        return ownerJdbc().sql(sql).param(param).query(Long.class).single();
    }

    private long signals(String kind) {
        return ownerJdbc().sql("SELECT count(*) FROM platform_signal WHERE kind = ? AND portal_id = ?").params(kind, portalId).query(Long.class).single();
    }

    private static long percentile(List<Outcome> out, double p) {
        List<Long> ms = out.stream().map(Outcome::millis).sorted().toList();
        if (ms.isEmpty()) return 0;
        return ms.get((int) Math.min(ms.size() - 1, Math.ceil(p * ms.size()) - 1));
    }

    private static final Map<String, String> REPORTS = new ConcurrentHashMap<>();

    private static void report(String name, Map<String, Object> values) throws Exception {
        StringBuilder sb = new StringBuilder("{");
        values.forEach((k, v) -> sb.append(sb.length() > 1 ? "," : "").append('"').append(k).append("\":").append(v instanceof String s ? "\"" + s + "\"" : v));
        sb.append(",\"recordedAt\":\"").append(Instant.now()).append("\"}");
        REPORTS.put(name, sb.toString());
        Path dir = Path.of("build/reports/ki66");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name + ".json"), sb + "\n");
        System.out.println("KI-66 " + name + " " + sb);
    }
}
