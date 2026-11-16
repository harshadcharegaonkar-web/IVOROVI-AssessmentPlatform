package com.ivorovi.platformadmin.internal;

import com.ivorovi.platform.audit.AuditLog;
import com.ivorovi.platform.security.StaffSessionGuard;
import com.ivorovi.platformadmin.PlatformPolicies.SecurityPolicy;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Enforces the organization's staff session policy on every staff API call ({@link StaffSessionGuard}).
 * <ul>
 *   <li><b>Second factor:</b> the token's {@code amr} names one, or the user has an authenticator enrolled (which the
 *       staff realm then requires at every sign-in); otherwise the user is asked (once) at the identity provider to enrol
 *       one and the call is refused.</li>
 *   <li><b>Idle and absolute limits:</b> tracked per identity-provider session ({@code sid}). The absolute limit counts
 *       from {@code auth_time} when present, else from the first call seen. An ended session stays ended: the user signs
 *       in again (new session). Last-seen updates are throttled to one write per 30 s per session.</li>
 * </ul>
 * No policy (the default) means no checks and no writes.
 */
@Component
class StaffSessionPolicyGuard implements StaffSessionGuard {

    private static final Logger log = LoggerFactory.getLogger(StaffSessionPolicyGuard.class);
    private static final Duration TOUCH_EVERY = Duration.ofSeconds(30);

    private final SecurityPolicyService policies;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final AuditLog audit;

    StaffSessionPolicyGuard(SecurityPolicyService policies, JdbcClient jdbc, TransactionTemplate tx, AuditLog audit) {
        this.policies = policies;
        this.jdbc = jdbc;
        this.tx = tx;
        this.audit = audit;
    }

    private record Seen(Instant firstSeen, Instant lastSeen, String endedReason) {}

    @Override
    public Optional<Refusal> check(UUID tenantId, UUID userId, String subject, String sessionId, Instant authTime, List<String> amr) {
        SecurityPolicy p = policies.effective(tenantId);
        if (p.mfaRequired() && !policies.hasSecondFactor(subject, amr)) {
            try {
                tx.executeWithoutResult(s -> policies.requestEnrolment(userId, subject));
            } catch (RuntimeException e) {
                log.warn("Authenticator enrolment request failed: {}", e.getClass().getSimpleName());   // retried at the next call
            }
            return Optional.of(Refusal.MFA_REQUIRED);
        }
        if (p.idleMinutes() == null && p.absoluteHours() == null) return Optional.empty();
        if (sessionId == null || sessionId.isBlank() || sessionId.length() > 100) return Optional.of(Refusal.SESSION_UNTRACKED);
        return Optional.ofNullable(tx.execute(s -> session(tenantId, userId, sessionId, authTime, p)));
    }

    private Refusal session(UUID tenantId, UUID userId, String sessionId, Instant authTime, SecurityPolicy p) {
        Instant now = Instant.now();
        Seen seen = jdbc.sql("SELECT first_seen, last_seen, ended_reason FROM staff_session WHERE tenant_id = ? AND session_id = ? FOR UPDATE")
                .params(tenantId, sessionId)
                .query((rs, n) -> new Seen(rs.getTimestamp(1).toInstant(), rs.getTimestamp(2).toInstant(), rs.getString(3))).optional().orElse(null);
        if (seen == null) {
            jdbc.sql("INSERT INTO staff_session (tenant_id, session_id, user_id, first_seen, last_seen) VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING")
                    .params(tenantId, sessionId, userId, Timestamp.from(now), Timestamp.from(now)).update();
            seen = new Seen(now, now, null);
        }
        if (seen.endedReason() != null) return "IDLE".equals(seen.endedReason()) ? Refusal.SESSION_IDLE : Refusal.SESSION_EXPIRED;
        if (p.idleMinutes() != null && seen.lastSeen().plus(Duration.ofMinutes(p.idleMinutes())).isBefore(now)) {
            end(tenantId, sessionId, userId, "IDLE");
            return Refusal.SESSION_IDLE;
        }
        Instant start = authTime != null && authTime.isBefore(seen.firstSeen()) ? authTime : seen.firstSeen();
        if (p.absoluteHours() != null && start.plus(Duration.ofHours(p.absoluteHours())).isBefore(now)) {
            end(tenantId, sessionId, userId, "EXPIRED");
            return Refusal.SESSION_EXPIRED;
        }
        if (seen.lastSeen().plus(TOUCH_EVERY).isBefore(now)) {
            jdbc.sql("UPDATE staff_session SET last_seen = ? WHERE tenant_id = ? AND session_id = ?").params(Timestamp.from(now), tenantId, sessionId).update();
        }
        return null;
    }

    private void end(UUID tenantId, String sessionId, UUID userId, String reason) {
        jdbc.sql("UPDATE staff_session SET ended_at = now(), ended_reason = ? WHERE tenant_id = ? AND session_id = ?").params(reason, tenantId, sessionId).update();
        audit.recordSuccess("tenant.security_policy.session_ended", "user", userId.toString(), Map.of("reason", reason));
    }
}
