package com.ivorovi.exams.internal;

import com.ivorovi.exams.CandidateMfa;
import com.ivorovi.platform.audit.AuditLog;
import com.ivorovi.platform.crypto.FieldCipher;
import com.ivorovi.platform.ids.Ids;
import com.ivorovi.platform.security.CandidateAuthentication;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.platform.tenancy.TenantDirectory;
import com.ivorovi.platform.web.ApiException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Candidate side of the delivery boundary. Every call runs with the organization bound from the portal key (never from
 * the token or the browser) and the candidate-realm identity; a candidate account exists per organization. A portal whose
 * MFA policy is REQUIRED refuses every call except {@code /me} until the candidate has an authenticator app (C-1, Q-02).
 */
@Service
public class CandidateService {

    @Schema(name = "ExamPortalInfo")
    public record PortalInfo(String portalKey, String name, String organizationName, String mfaPolicy, boolean selfRegistration) {}

    @Schema(name = "CandidateMfaStatus")
    public record Mfa(String policy, boolean enrolled, boolean satisfied) {}

    @Schema(name = "CandidateSession")
    public record SessionView(UUID id, String name, Instant startsAt, Instant endsAt, Instant checkedInAt) {}

    @Schema(name = "CandidateCompensation")
    public record CompensationView(UUID id, UUID sessionId, String sessionName, String action, Integer extensionMinutes, Instant validUntil,
                                   Instant usedAt) {}

    @Schema(name = "CandidateMe")
    public record Me(UUID candidateId, String email, String displayName, Mfa mfa, List<SessionView> sessions, List<CompensationView> compensations) {}

    @Schema(name = "CandidateCheckInResult")
    public record CheckInResult(UUID sessionId, String sessionName, Instant checkedInAt) {}

    @Schema(name = "PresenceAck")
    public record PresenceAck(Instant serverTime, long beats) {}

    @Schema(name = "SaveAck")
    public record SaveAck(long seq, long saves, Instant serverTime) {}

    record Portal(UUID id, String key, String name, String mfaPolicy, boolean selfRegistration) {}

    record Me0(UUID id, String status) {}

    private final JdbcClient jdbc;
    private final AuditLog audit;
    private final FieldCipher cipher;
    private final CandidateMfa mfa;
    private final TenantDirectory tenants;
    private final ExamAdminService admin;
    private final JsonMapper json;
    private final TransactionTemplate tx;
    private final CheckInLimits limits;
    private final DeliveryTelemetry telemetry;

    CandidateService(JdbcClient jdbc, AuditLog audit, FieldCipher cipher, CandidateMfa mfa, TenantDirectory tenants, ExamAdminService admin,
                     JsonMapper json, TransactionTemplate tx, CheckInLimits limits, DeliveryTelemetry telemetry) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.cipher = cipher;
        this.mfa = mfa;
        this.tenants = tenants;
        this.admin = admin;
        this.json = json;
        this.tx = tx;
        this.limits = limits;
        this.telemetry = telemetry;
    }

    /**
     * A refusal to be audited once the business transaction has rolled back. Auditing it inside would need a second
     * pooled connection per request (REQUIRES_NEW) and can exhaust the pool under a burst of refused attempts.
     */
    static final class Denial extends RuntimeException {
        final String action;
        final Map<String, String> details;
        final ApiException error;
        /** A failed check-in attempt that counts against the failure layers (not a limit refusal or an MFA denial). */
        final boolean failedAttempt;

        Denial(String action, Map<String, String> details, ApiException error) {
            this(action, details, error, false);
        }

        Denial(String action, Map<String, String> details, ApiException error, boolean failedAttempt) {
            super(error.getMessage(), null, false, false);
            this.action = action;
            this.details = details;
            this.error = error;
            this.failedAttempt = failedAttempt;
        }
    }

    /** Runs candidate work in a transaction; refusals raised as {@link Denial} are audited after the rollback. */
    <T> T runGuarded(java.util.function.Supplier<T> work) {
        return run(work);
    }

    private <T> T run(java.util.function.Supplier<T> work) {
        try {
            return tx.execute(s -> work.get());
        } catch (Denial d) {
            audit.recordDenied(d.action, d.details);
            throw d.error;
        }
    }

    // ================================================================================================ portal + identity

    @Transactional(readOnly = true)
    public PortalInfo portalInfo(String key) {
        Portal p = portal(key);
        String org = tenants.find(TenantContext.requireTenantId()).map(TenantDirectory.TenantInfo::name).orElse("");
        return new PortalInfo(p.key(), p.name(), org, p.mfaPolicy(), p.selfRegistration());
    }

    /** Links (or, with self-registration, enrols) the signed-in candidate in this organization and returns their state. */
    @Transactional
    public Me me(String key) {
        CandidateAuthentication c = candidate();
        Portal p = portal(key);
        UUID tenant = TenantContext.requireTenantId();
        Optional<Me0> existing = jdbc.sql("SELECT id, status FROM candidate_account WHERE subject = ?").param(c.subject())
                .query((rs, n) -> new Me0(rs.getObject(1, UUID.class), rs.getString(2))).optional();
        UUID id;
        if (existing.isEmpty()) {
            if (!p.selfRegistration()) {
                throw new ApiException(HttpStatus.FORBIDDEN, "registration_closed", "This exam portal does not accept new candidates. Contact the institution.");
            }
            id = jdbc.sql("""
                    INSERT INTO candidate_account (id, tenant_id, subject, email_enc, email_index, display_name, portal_id, last_seen_at) VALUES (?, ?, ?, ?, ?, ?, ?, now())
                    ON CONFLICT (tenant_id, subject) DO UPDATE SET last_seen_at = now() RETURNING id""")
                    .params(Ids.newId(), tenant, c.subject(), cipher.encrypt(c.email(), ExamAdminService.EMAIL_FIELD, tenant), emailIndex(c.email()), truncate(c.name(), 200), p.id())
                    .query(UUID.class).single();
            c.link(id);
            audit.recordSuccess("exam.candidate.enrolled", "candidate_account", id.toString(), Map.of("portal", p.key()));
        } else {
            id = existing.get().id();
            c.link(id);
            if (!"ACTIVE".equals(existing.get().status())) throw suspended();
            jdbc.sql("UPDATE candidate_account SET last_seen_at = now(), email_enc = ?, email_index = ?, display_name = coalesce(?, display_name) WHERE id = ?")
                    .params(cipher.encrypt(c.email(), ExamAdminService.EMAIL_FIELD, tenant), emailIndex(c.email()), truncate(c.name(), 200), id).update();
        }
        Mfa m = mfaStatus(p, c);
        List<SessionView> sessions = m.satisfied() ? jdbc.sql("""
                SELECT s.id, s.name, s.starts_at, s.ends_at, ci.checked_in_at FROM readiness_session s
                LEFT JOIN check_in ci ON ci.session_id = s.id AND ci.candidate_id = ?
                WHERE s.portal_id = ? AND s.status = 'SCHEDULED' AND s.ends_at > now() - interval '1 day'
                ORDER BY s.starts_at LIMIT 20""").params(id, p.id()).query((rs, n) -> new SessionView(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getTimestamp(3).toInstant(), rs.getTimestamp(4).toInstant(), ts(rs.getTimestamp(5)))).list() : List.of();
        List<CompensationView> comps = m.satisfied() ? compensations(id) : List.of();
        return new Me(id, c.email(), c.name(), m, sessions, comps);
    }

    private List<CompensationView> compensations(UUID candidate) {
        return jdbc.sql("""
                SELECT cc.id, cc.session_id, s.name, ec.action, cc.extension_minutes, sc.valid_until, sc.consumed_at
                FROM candidate_compensation cc JOIN exam_compensation ec ON ec.id = cc.compensation_id
                JOIN readiness_session s ON s.id = cc.session_id LEFT JOIN session_code sc ON sc.id = cc.new_code_id
                WHERE cc.candidate_id = ? ORDER BY cc.created_at DESC LIMIT 20""").param(candidate)
                .query((rs, n) -> new CompensationView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                        (Integer) rs.getObject(5), ts(rs.getTimestamp(6)), ts(rs.getTimestamp(7)))).list();
    }

    private Mfa mfaStatus(Portal p, CandidateAuthentication c) {
        if ("OFF".equals(p.mfaPolicy())) return new Mfa("OFF", false, true);
        boolean enrolled;
        if (c.amr().contains("otp")) {
            enrolled = true;
        } else if ("REQUIRED".equals(p.mfaPolicy())) {
            enrolled = mfa.enrolled(c.subject()); // fails closed (503) when the identity provider cannot answer
        } else {
            try {
                enrolled = mfa.enrolled(c.subject());
            } catch (ApiException unavailable) {
                enrolled = false;                 // OPTIONAL: display only, never blocks
            }
        }
        return new Mfa(p.mfaPolicy(), enrolled, enrolled || !"REQUIRED".equals(p.mfaPolicy()));
    }

    /** The linked, active candidate who satisfies the portal's MFA policy. */
    UUID requireCandidate(Portal p) {
        CandidateAuthentication c = candidate();
        Me0 me = jdbc.sql("SELECT id, status FROM candidate_account WHERE subject = ?").param(c.subject())
                .query((rs, n) -> new Me0(rs.getObject(1, UUID.class), rs.getString(2))).optional()
                .orElseThrow(() -> ApiException.conflict("not_enrolled", "Open the exam portal first to link your account."));
        c.link(me.id());
        if (!"ACTIVE".equals(me.status())) throw suspended();
        if ("REQUIRED".equals(p.mfaPolicy()) && !mfaStatus(p, c).satisfied()) {
            throw new Denial("exam.candidate.mfa_required", Map.of("portal", p.key()),
                    new ApiException(HttpStatus.FORBIDDEN, "mfa_required", "This institution requires an authenticator app. Set it up, then sign in again."));
        }
        return me.id();
    }

    // ================================================================================================ check-in with a session code

    /** What the limiter needs to know about the caller before an attempt (read in a short transaction of its own). */
    record Caller(Portal portal, UUID candidate, UUID tenant, boolean established, boolean venue) {}

    /**
     * Consumes a session code exactly once (atomic conditional update: of any number of concurrent attempts with the same
     * code, one wins). Attempts pass the layered limits of follow-up A (KI-66) first, in this order:
     * <ol>
     *   <li>L1 candidate: attempts per minute and failed attempts per day (429 for that candidate only);</li>
     *   <li>L3 device: failed attempts per minute of the token's sign-in session (429 for that device only);</li>
     *   <li>L2 code: failed attempts on the presented code per minute and per day; refused with the same answer as an
     *       invalid code, and an existing code is locked once its daily budget is spent;</li>
     *   <li>L5 address: only for candidates without a successful check-in in an open session of this portal, and not on a
     *       registered venue network: failed attempts beyond the address threshold delay further attempts progressively —
     *       never a refusal;</li>
     *   <li>L4 portal: failed attempts per minute far above what the expected candidates explain raise an operator alert.</li>
     * </ol>
     * Rate limits are applied outside any database transaction (a delayed attempt holds no connection). Every refusal is
     * audited once, with the layer and any delay.
     */
    public CheckInResult checkIn(String key, String rawCode, Integer clockOffsetMs, Map<String, Object> systemCheck, String ip) {
        Caller c = run(() -> caller(key, ip));
        CheckInLimits.Attempt attempt = limits.begin(c, rawCode == null ? Optional.empty() : SessionCodes.normalize(rawCode).map(n -> admin.codeHash(c.tenant(), n)),
                deviceKey(), ip);
        try {
            if (attempt.refusal() != null) throw attempt.refusal().apply(c.portal());
            return tx.execute(s -> consume(c, attempt, clockOffsetMs, systemCheck));
        } catch (Denial d) {
            if (d.failedAttempt) limits.failed(c, attempt, d.details.getOrDefault("reason", "invalid"));
            Map<String, String> details = new LinkedHashMap<>(d.details);
            if (attempt.delayedMs() > 0) details.put("delayedMs", String.valueOf(attempt.delayedMs()));
            audit.recordDenied(d.action, details);
            throw d.error;
        }
    }

    private Caller caller(String key, String ip) {
        Portal p = portal(key);
        UUID me = requireCandidate(p);
        String inet = CheckInLimits.inetLiteral(ip);
        record Trust(boolean established, boolean venue) {}
        Trust t = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM check_in ci JOIN readiness_session s ON s.id = ci.session_id
                               WHERE ci.candidate_id = ? AND s.portal_id = ? AND s.status = 'SCHEDULED' AND s.ends_at > now()),
                       CAST(? AS inet) IS NOT NULL AND EXISTS (SELECT 1 FROM exam_venue_network v JOIN readiness_session s ON s.id = v.session_id
                               WHERE s.portal_id = ? AND s.status = 'SCHEDULED' AND now() >= s.starts_at - interval '2 hours' AND now() < s.ends_at
                                 AND v.network >>= CAST(? AS inet))""")
                .params(me, p.id(), inet, p.id(), inet).query((rs, n) -> new Trust(rs.getBoolean(1), rs.getBoolean(2))).single();
        return new Caller(p, me, TenantContext.requireTenantId(), t.established(), t.venue());
    }

    private CheckInResult consume(Caller c, CheckInLimits.Attempt attempt, Integer clockOffsetMs, Map<String, Object> systemCheck) {
        Portal p = c.portal();
        UUID me = c.candidate();
        if (attempt.codeHash() == null) throw refused(p, "malformed");
        record Hit(UUID codeId, UUID sessionId, String sessionName) {}
        Optional<Hit> hit = jdbc.sql("""
                UPDATE session_code sc SET consumed_at = now(), consumed_by = ?
                FROM readiness_session rs
                WHERE sc.code_hash = ? AND sc.consumed_at IS NULL AND sc.revoked_at IS NULL AND sc.locked_at IS NULL AND sc.issued_for IS NULL
                  AND now() >= sc.valid_from AND now() < sc.valid_until
                  AND rs.id = sc.session_id AND rs.status = 'SCHEDULED' AND rs.portal_id = ?
                RETURNING sc.id, sc.session_id, rs.name""").params(me, attempt.codeHash(), p.id())
                .query((rs, n) -> new Hit(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3))).optional();
        if (hit.isEmpty()) throw refused(p, "invalid_used_or_outside_window");
        Instant at = recordCheckIn(c.tenant(), hit.get().sessionId(), me, hit.get().codeId(), clockOffsetMs, systemCheck, false);
        Map<String, String> details = new LinkedHashMap<>();
        details.put("codeId", hit.get().codeId().toString());
        details.put("portal", p.key());
        if (attempt.delayedMs() > 0) details.put("delayedMs", String.valueOf(attempt.delayedMs()));
        audit.recordSuccess("exam.check_in", "readiness_session", hit.get().sessionId().toString(), details);
        return new CheckInResult(hit.get().sessionId(), hit.get().sessionName(), at);
    }

    /** The device layer's key: the identity provider's sign-in session of the token (per browser), else the subject. */
    private static String deviceKey() {
        CandidateAuthentication c = candidate();
        String sid = c.token().getClaimAsString("sid");
        return sid == null || sid.isBlank() ? "sub:" + c.subject() : "sid:" + sid;
    }

    /** Uses a compensation entitlement (a code bound to this candidate by an approved compensation; nothing to type). */
    public CheckInResult compensationCheckIn(String key, UUID compensationId, Integer clockOffsetMs, Map<String, Object> systemCheck) {
        return run(() -> {
            Portal p = portal(key);
            UUID me = requireCandidate(p);
            UUID tenant = TenantContext.requireTenantId();
            record Hit(UUID codeId, UUID sessionId, String sessionName) {}
            Optional<Hit> hit = jdbc.sql("""
                    UPDATE session_code sc SET consumed_at = now(), consumed_by = ?
                    FROM candidate_compensation cc, readiness_session rs
                    WHERE cc.id = ? AND cc.candidate_id = ? AND sc.id = cc.new_code_id AND sc.issued_for = ?
                      AND sc.consumed_at IS NULL AND sc.revoked_at IS NULL AND now() >= sc.valid_from AND now() < sc.valid_until
                      AND rs.id = sc.session_id AND rs.portal_id = ?
                    RETURNING sc.id, sc.session_id, rs.name""").params(me, compensationId, me, me, p.id())
                    .query((rs, n) -> new Hit(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3))).optional();
            if (hit.isEmpty()) throw refused(p, "compensation_unavailable");
            Instant at = recordCheckIn(tenant, hit.get().sessionId(), me, hit.get().codeId(), clockOffsetMs, systemCheck, true);
            audit.recordSuccess("exam.check_in.compensation", "readiness_session", hit.get().sessionId().toString(), Map.of("compensation",
                    compensationId.toString()));
            return new CheckInResult(hit.get().sessionId(), hit.get().sessionName(), at);
        });
    }

    private Instant recordCheckIn(UUID tenant, UUID session, UUID me, UUID codeId, Integer clockOffsetMs, Map<String, Object> systemCheck, boolean replace) {
        String check = json.writeValueAsString(sanitize(systemCheck));
        Integer offset = clockOffsetMs == null ? null : Math.max(-86_400_000, Math.min(86_400_000, clockOffsetMs));
        try {
            return jdbc.sql("""
                    INSERT INTO check_in (id, tenant_id, session_id, candidate_id, code_id, clock_offset_ms, system_check, instance_id)
                    VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
                    """ + (replace ? "ON CONFLICT (tenant_id, session_id, candidate_id) DO UPDATE SET code_id = EXCLUDED.code_id, checked_in_at = now(), "
                    + "clock_offset_ms = EXCLUDED.clock_offset_ms, system_check = EXCLUDED.system_check, instance_id = EXCLUDED.instance_id " : "")
                    + "RETURNING checked_in_at")
                    .params(Ids.newId(), tenant, session, me, codeId, offset, check, telemetry.instanceId()).query(Timestamp.class).single().toInstant();
        } catch (DuplicateKeyException e) {
            // rolls back the code consumption too: the code stays usable by someone else
            throw ApiException.conflict("already_checked_in", "You have already checked in to this session.");
        }
    }

    /** Only the known, non-personal readiness checks are kept (browser family, screen size, network, camera permission). */
    private static Map<String, Object> sanitize(Map<String, Object> in) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (in == null) return out;
        for (String k : List.of("browser", "browserSupported", "screen", "online", "latencyMs", "cameraPermission", "fullscreen", "storage")) {
            Object v = in.get(k);
            if (v instanceof String s) out.put(k, truncate(s, 60));
            else if (v instanceof Number || v instanceof Boolean) out.put(k, v);
        }
        return out;
    }

    // ================================================================================================ presence + acknowledged saves

    public PresenceAck presence(String key, UUID sessionId) {
        return run(() -> {
            Portal p = portal(key);
            UUID me = requireCandidate(p);
            requireCheckedIn(me, sessionId);
            long beats = telemetry.beat(TenantContext.requireTenantId(), me, sessionId, false, 0).beats();
            return new PresenceAck(Instant.now(), beats);
        });
    }

    /** Practice save (the harness's stand-in for answer saves until 2.5): acknowledged only after it is durable. */
    public SaveAck save(String key, UUID sessionId, long seq, String payload) {
        return run(() -> {
            Portal p = portal(key);
            UUID me = requireCandidate(p);
            requireCheckedIn(me, sessionId);
            if (payload != null && payload.length() > 4096) throw ApiException.badRequest("payload_too_large", "At most 4096 characters.");
            long saves = telemetry.beat(TenantContext.requireTenantId(), me, sessionId, true, seq).saves();
            return new SaveAck(seq, saves, Instant.now());
        });
    }

    private void requireCheckedIn(UUID me, UUID sessionId) {
        boolean ok = jdbc.sql("SELECT EXISTS (SELECT 1 FROM check_in WHERE session_id = ? AND candidate_id = ?)").params(sessionId, me).query(Boolean.class).single();
        if (!ok) throw ApiException.conflict("not_checked_in", "Check in to this session first.");
    }

    // ================================================================================================ helpers

    static final String EMAIL_INDEX_PURPOSE = "candidate.email";

    /** Blind index of a normalized email within the tenant (no plaintext lookup column exists). */
    public String emailIndex(String email) {
        return email == null ? null : cipher.blindIndex(TenantContext.requireTenantId() + ":" + email.strip().toLowerCase(java.util.Locale.ROOT), EMAIL_INDEX_PURPOSE);
    }

    Portal portal(String key) {
        return jdbc.sql("SELECT id, portal_key, name, mfa_policy, self_registration FROM exam_portal WHERE portal_key = ? AND status = 'ACTIVE'").param(key)
                .query((rs, n) -> new Portal(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5))).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "portal_not_found", "This exam portal does not exist or is not available."));
    }

    static Denial refused(Portal p, String reason) {
        // one message for every reason: the response never tells a guesser whether a code exists
        return new Denial("exam.session_code.refused", Map.of("portal", p.key(), "reason", reason),
                ApiException.conflict("code_not_valid", "This code is not valid now. Check it, or ask the invigilator for a new one."), true);
    }

    /** A limit layer refused the attempt. The code layer answers exactly like an invalid code (uniform refusals). */
    static Denial limited(Portal p, String bucket, boolean uniform) {
        ApiException error = uniform
                ? ApiException.conflict("code_not_valid", "This code is not valid now. Check it, or ask the invigilator for a new one.")
                : new ApiException(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", "Too many attempts. Wait a minute and try again.");
        return new Denial("exam.session_code.rate_limited", Map.of("portal", p.key(), "bucket", bucket), error);
    }

    private static ApiException suspended() {
        return new ApiException(HttpStatus.FORBIDDEN, "candidate_suspended", "Your exam account is suspended. Contact the institution.");
    }

    private static CandidateAuthentication candidate() {
        return CandidateAuthentication.current().orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "unauthenticated", "Sign in first."));
    }

    private static Instant ts(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
