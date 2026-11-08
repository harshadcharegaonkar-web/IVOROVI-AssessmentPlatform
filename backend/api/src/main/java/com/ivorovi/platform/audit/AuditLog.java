package com.ivorovi.platform.audit;

import com.ivorovi.platform.ids.Ids;
import com.ivorovi.platform.security.CurrentUser;
import com.ivorovi.platform.security.IvoroviPrincipal;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.platform.web.RequestCorrelationFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes audit events for the current tenant. {@link #record} joins the caller's transaction, so the audit entry
 * commits or rolls back together with the business change it describes.
 */
@Component
public class AuditLog {

    private static final Logger log = LoggerFactory.getLogger(AuditLog.class);
    private final JdbcClient jdbc;
    private final JsonMapper json;

    AuditLog(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void record(String action, String resourceType, String resourceId, AuditOutcome outcome,
                       Map<String, String> details) {
        UUID tenantId = TenantContext.requireTenantId();
        Map<String, String> safeDetails = new TreeMap<>(details);
        // Serialise appends per tenant so sequence numbers and the hash chain stay linear.
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(?, 7041))").param(tenantId.toString())
                .query((rs, n) -> 1).list();
        record Last(long seq, String hash) {}
        Last last = jdbc.sql("SELECT seq, hash FROM audit_event WHERE tenant_id = ? ORDER BY seq DESC LIMIT 1")
                .param(tenantId).query((rs, n) -> new Last(rs.getLong(1), rs.getString(2))).optional()
                .orElse(new Last(0, AuditHasher.GENESIS));

        long seq = last.seq() + 1;
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Optional<IvoroviPrincipal> actor = CurrentUser.get();
        AuditActor other = actor.isEmpty() && org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication() instanceof AuditActor a
                ? a : null;
        UUID actorId = actor.map(IvoroviPrincipal::userId).orElse(other == null ? null : other.auditActorId());
        String actorSubject = actor.map(IvoroviPrincipal::subject).orElse(other == null ? "system" : other.auditActorSubject());
        String hash = AuditHasher.hash(last.hash(), tenantId, seq, now, actorId, actorSubject, action, resourceType,
                resourceId, outcome, safeDetails);
        HttpServletRequest req = currentRequest();

        jdbc.sql("""
                INSERT INTO audit_event (id, tenant_id, seq, occurred_at, actor_id, actor_subject, action, resource_type,
                    resource_id, outcome, details, client_ip, user_agent, trace_id, prev_hash, hash)
                VALUES (:id, :tenant, :seq, :at, :actor, :subject, :action, :rtype, :rid, :outcome, CAST(:details AS jsonb),
                    :ip, :ua, :trace, :prev, :hash)""")
                .param("id", Ids.newId()).param("tenant", tenantId).param("seq", seq)
                .param("at", java.sql.Timestamp.from(now)).param("actor", actorId).param("subject", actorSubject)
                .param("action", action).param("rtype", resourceType).param("rid", resourceId)
                .param("outcome", outcome.name()).param("details", json.writeValueAsString(safeDetails))
                .param("ip", req == null ? null : req.getRemoteAddr())
                .param("ua", req == null ? null : truncate(req.getHeader("User-Agent"), 512))
                .param("trace", MDC.get(RequestCorrelationFilter.MDC_TRACE_ID))
                .param("prev", last.hash()).param("hash", hash)
                .update();
    }

    public void recordSuccess(String action, String resourceType, String resourceId, Map<String, String> details) {
        record(action, resourceType, resourceId, AuditOutcome.SUCCESS, details);
    }

    /**
     * Records a denied attempt in its own transaction (the business transaction, if any, is rolling back).
     * Best effort: a failure to audit a denial is logged, never masks the original 403.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordDenied(String action, Map<String, String> details) {
        if (TenantContext.currentTenantId().isEmpty()) {
            log.warn("{} without tenant context: {}", action, details);
            return;
        }
        try {
            record(action, null, null, AuditOutcome.DENIED, details);
        } catch (RuntimeException e) {
            log.error("Failed to audit denied access", e);
        }
    }

    private static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes sra ? sra.getRequest() : null;
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
