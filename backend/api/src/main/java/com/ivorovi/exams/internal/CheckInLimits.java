package com.ivorovi.exams.internal;

import com.ivorovi.exams.ExamsProperties;
import com.ivorovi.exams.SessionRoster;
import com.ivorovi.platform.audit.AuditLog;
import com.ivorovi.platform.audit.AuditOutcome;
import com.ivorovi.platform.config.DeliveryProperties;
import com.ivorovi.platform.process.ProcessHeartbeat;
import com.ivorovi.platform.ratelimit.RateLimitProperties;
import com.ivorovi.platform.ratelimit.RateLimiter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The layered check-in limits of follow-up A (KI-66). Limits are keyed by <em>who</em> acts first (candidate, device,
 * presented code); the address is only a coarse anomaly signal that slows down accounts without a successful check-in and
 * never refuses anyone. See {@link CandidateService#checkIn} for the order. All buckets live in Valkey and fail open.
 */
@Component
class CheckInLimits {

    /**
     * The outcome of the checks before an attempt: the keyed hash of the presented code (null if malformed), the device
     * key, the address, how long the attempt was delayed, and a refusal to throw instead of attempting (or null).
     */
    record Attempt(String codeHash, String deviceKey, String ip, long delayedMs, Function<CandidateService.Portal, CandidateService.Denial> refusal) {}

    private static final Logger log = LoggerFactory.getLogger(CheckInLimits.class);
    private static final Duration DAY = Duration.ofDays(1);
    private static final Duration WINDOW_TTL = Duration.ofMinutes(2);
    private static final Duration EXPECTATION_TTL = Duration.ofSeconds(30);
    private static final Pattern INET = Pattern.compile("[0-9A-Fa-f:.]{2,45}");

    private final RateLimiter limiter;
    private final RateLimitProperties rateLimits;
    private final DeliveryProperties delivery;
    private final ExamsProperties.CheckIn props;
    private final PlatformSignals signals;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final AuditLog audit;
    private final ProcessHeartbeat process;
    private final ObjectProvider<SessionRoster> rosters;
    private final MeterRegistry meters;
    private final Map<UUID, long[]> expectations = new ConcurrentHashMap<>(); // portal -> {expected, computedAtMillis}

    CheckInLimits(RateLimiter limiter, RateLimitProperties rateLimits, DeliveryProperties delivery, ExamsProperties exams, PlatformSignals signals,
                  JdbcClient jdbc, TransactionTemplate tx, AuditLog audit, ProcessHeartbeat process, ObjectProvider<SessionRoster> rosters, MeterRegistry meters) {
        this.limiter = limiter;
        this.rateLimits = rateLimits;
        this.delivery = delivery;
        this.props = exams.checkIn();
        this.signals = signals;
        this.jdbc = jdbc;
        this.tx = tx;
        this.audit = audit;
        this.process = process;
        this.rosters = rosters;
        this.meters = meters;
    }

    // ================================================================================================ before the attempt

    Attempt begin(CandidateService.Caller c, Optional<String> hash, String device, String ip) {
        String codeHash = hash.orElse(null);
        if (!rateLimits.enabled()) return new Attempt(codeHash, device, ip, 0, null);
        String t = c.tenant().toString();
        // L1: the candidate's own budget (all attempts per minute, failed attempts per day)
        if (!limiter.acquire("code-candidate:" + t + ":" + c.candidate(), delivery.codeAttemptsPerCandidatePerMinute(), true).allowed()) {
            return refuse(codeHash, device, ip, p -> CandidateService.limited(p, "candidate", false));
        }
        if (!limiter.acquire("code-candidate-day:" + t + ":" + c.candidate(), props.failedPerCandidatePerDay(), DAY, false).allowed()) {
            return refuse(codeHash, device, ip, p -> CandidateService.limited(p, "candidate-day", false));
        }
        // L3: the device (sign-in session) — a second account on the same browser does not get a fresh budget
        if (!limiter.acquire("code-device:" + t + ":" + device, props.failedPerDevicePerMinute(), false).allowed()) {
            return refuse(codeHash, device, ip, p -> CandidateService.limited(p, "device", false));
        }
        // L2: the presented code — answered exactly like an invalid code
        if (codeHash != null) {
            if (!limiter.acquire(codeKey(codeHash), delivery.codeAttemptsPerCodePerMinute(), false).allowed()) {
                return refuse(codeHash, device, ip, p -> CandidateService.limited(p, "code", true));
            }
            if (!limiter.acquire(codeDayKey(codeHash), props.failedPerCodePerDay(), DAY, false).allowed()) {
                return refuse(codeHash, device, ip, p -> CandidateService.limited(p, "code-day", true));
            }
        }
        // L5: the address, only for candidates who have not checked in yet; a delay, never a refusal
        long delay = 0;
        if (!c.established() && !limiter.acquire(addressKey(ip), delivery.codeAttemptsPerIpPerMinute(), false).allowed()) {
            long minute = minute(Instant.now());
            if (limiter.once("code-addr-alert:" + ip + ":" + minute, WINDOW_TTL)) {
                signals.raise(PlatformSignals.ADDRESS_THROTTLED, c.tenant(), c.portal().id(), detail("address", ip, "venue", c.venue(),
                        "failedPerMinute", delivery.codeAttemptsPerIpPerMinute()));
            }
            if (!c.venue()) {
                long excess = limiter.increment("code-addr-excess:" + ip + ":" + minute, WINDOW_TTL);
                delay = Math.min(props.addressMaxDelay().toMillis(), Math.max(1, excess) * props.addressDelayStep().toMillis());
                meters.counter("ivorovi.checkin.address_delayed").increment();
                sleep(delay);
            }
        }
        return new Attempt(codeHash, device, ip, delay, null);
    }

    // ================================================================================================ after a failed attempt

    /** Charges the failure layers, locks a code whose daily budget is spent and raises the L4 alarm. */
    void failed(CandidateService.Caller c, Attempt a, String reason) {
        record(c, reason);
        if (!rateLimits.enabled()) return;
        String t = c.tenant().toString();
        limiter.acquire("code-candidate-day:" + t + ":" + c.candidate(), props.failedPerCandidatePerDay(), DAY, true);
        limiter.acquire("code-device:" + t + ":" + a.deviceKey(), props.failedPerDevicePerMinute(), true);
        if (a.codeHash() != null) {
            limiter.acquire(codeKey(a.codeHash()), delivery.codeAttemptsPerCodePerMinute(), true);
            RateLimiter.Decision day = limiter.acquire(codeDayKey(a.codeHash()), props.failedPerCodePerDay(), DAY, true);
            if (day.remaining() <= 0) lock(a.codeHash());
        }
        if (!c.established()) limiter.acquire(addressKey(a.ip()), delivery.codeAttemptsPerIpPerMinute(), true);
        anomaly(c);
    }

    /** L4: failed attempts on the portal in this minute against what its expected candidates explain. Never blocks. */
    private void anomaly(CandidateService.Caller c) {
        long minute = minute(Instant.now());
        long failures = limiter.increment("checkin-fail:" + c.portal().id() + ":" + minute, WINDOW_TTL);
        if (failures < props.anomalyMinimumPerMinute()) return;
        long expected = expected(c.portal().id());
        long threshold = threshold(expected);
        if (failures < threshold || !limiter.once("checkin-anomaly:" + c.portal().id() + ":" + minute, WINDOW_TTL)) return;
        Map<String, Object> d = detail("failuresThisMinute", failures, "threshold", threshold, "expectedCandidates", expected, "portal", c.portal().key());
        signals.raise(PlatformSignals.CHECK_IN_ANOMALY, c.tenant(), c.portal().id(), d);
        try {
            audit.record("exam.check_in.anomaly", "exam_portal", c.portal().id().toString(), AuditOutcome.FAILURE,
                    Map.of("failuresThisMinute", String.valueOf(failures), "threshold", String.valueOf(threshold), "expectedCandidates", String.valueOf(expected)));
        } catch (RuntimeException e) {
            log.warn("Could not audit check-in anomaly: {}", e.toString());
        }
    }

    long threshold(long expected) {
        return Math.max(props.anomalyMinimumPerMinute(), (long) Math.ceil(props.anomalyFactor() * props.expectedFailureRatio() * expected));
    }

    /**
     * Candidates expected on the portal's open sessions: the roster where a {@link SessionRoster} provides one, otherwise
     * the single-use codes issued for the session (one per candidate). Cached per portal for 30 seconds.
     */
    long expected(UUID portal) {
        long now = System.currentTimeMillis();
        long[] cached = expectations.get(portal);
        if (cached != null && now - cached[1] < EXPECTATION_TTL.toMillis()) return cached[0];
        long expected = 0;
        try {
            record S(UUID id, long codes) {}
            List<S> sessions = jdbc.sql("""
                    SELECT s.id, (SELECT count(*) FROM session_code c WHERE c.session_id = s.id AND c.issued_for IS NULL AND c.revoked_at IS NULL)
                    FROM readiness_session s WHERE s.portal_id = ? AND s.status = 'SCHEDULED' AND now() >= s.starts_at - interval '2 hours' AND now() < s.ends_at""")
                    .param(portal).query((rs, n) -> new S(rs.getObject(1, UUID.class), rs.getLong(2))).list();
            for (S s : sessions) {
                Optional<Set<UUID>> roster = Optional.empty();
                for (SessionRoster r : rosters) {
                    roster = r.expectedCandidates(s.id());
                    if (roster.isPresent()) break;
                }
                expected += roster.map(Set::size).map(Integer::longValue).orElse(s.codes());
            }
        } catch (RuntimeException e) {
            log.warn("Expected candidates unavailable: {}", e.toString());
        }
        expectations.put(portal, new long[] {expected, now});
        return expected;
    }

    private void lock(String codeHash) {
        try {
            tx.executeWithoutResult(s -> jdbc.sql("""
                    UPDATE session_code SET locked_at = now() WHERE code_hash = ? AND locked_at IS NULL AND consumed_at IS NULL AND revoked_at IS NULL
                    RETURNING id, session_id""").param(codeHash).query((rs, n) -> new UUID[] {rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)})
                    .optional().ifPresent(ids -> audit.record("exam.session_code.locked", "session_code", ids[0].toString(), AuditOutcome.SUCCESS,
                            Map.of("session", ids[1].toString(), "reason", "failed_attempts_per_day"))));
        } catch (RuntimeException e) {
            log.warn("Could not lock a session code: {}", e.toString());
        }
    }

    /** Who could not check in, and when (KI-73 impact): one row per candidate, portal and minute. */
    private void record(CandidateService.Caller c, String reason) {
        try {
            jdbc.sql("""
                    INSERT INTO check_in_failure (tenant_id, portal_id, candidate_id, minute, failures, last_reason, instance_id)
                    VALUES (?, ?, ?, date_trunc('minute', now()), 1, ?, ?)
                    ON CONFLICT (tenant_id, candidate_id, portal_id, minute) DO UPDATE SET failures = check_in_failure.failures + 1,
                        last_reason = EXCLUDED.last_reason, instance_id = EXCLUDED.instance_id""")
                    .params(c.tenant(), c.portal().id(), c.candidate(), reason.length() > 40 ? reason.substring(0, 40) : reason, process.instanceId()).update();
        } catch (RuntimeException e) {
            log.warn("Could not record a failed check-in: {}", e.toString());
        }
    }

    // ================================================================================================ helpers

    /** The address as a literal PostgreSQL accepts as {@code inet}, or null (zone ids are dropped). */
    static String inetLiteral(String ip) {
        if (ip == null) return null;
        int zone = ip.indexOf('%');
        String a = zone < 0 ? ip : ip.substring(0, zone);
        return INET.matcher(a).matches() ? a : null;
    }

    private static Attempt refuse(String codeHash, String device, String ip, Function<CandidateService.Portal, CandidateService.Denial> refusal) {
        return new Attempt(codeHash, device, ip, 0, refusal);
    }

    private static String codeKey(String hash) {
        return "code-hash:" + hash.substring(0, 32);
    }

    private static String codeDayKey(String hash) {
        return "code-hash-day:" + hash.substring(0, 32);
    }

    private static String addressKey(String ip) {
        return "code-addr:" + ip;
    }

    private static long minute(Instant now) {
        return now.getEpochSecond() / 60;
    }

    private static Map<String, Object> detail(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
