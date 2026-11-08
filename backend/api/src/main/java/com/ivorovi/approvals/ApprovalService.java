package com.ivorovi.approvals;

import com.ivorovi.platform.audit.AuditLog;
import com.ivorovi.platform.ids.Ids;
import com.ivorovi.platform.jobs.JobLeases;
import com.ivorovi.platform.outbox.Outbox;
import com.ivorovi.platform.security.Authz;
import com.ivorovi.platform.security.CurrentUser;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.platform.web.ApiException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Four-eyes approval framework (2.0-A, condition C-2, decision Q-04). Separation of duties is enforced three times: the
 * service refuses a decision by the requester, the database constraint {@code approval_four_eyes} refuses it even for a
 * direct write, and the approver must hold the required permission at the subject's unit at decision time. Requests
 * without an eligible approver stay pending (escalation notifies organization-level holders); nothing is auto-approved.
 */
@Service
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);
    public static final Duration DEFAULT_TTL = Duration.ofDays(7);

    @Schema(name = "ApprovalRequest")
    public record Request(UUID id, String type, String typeLabel, String subjectType, String subjectId, String summary, Map<String, Object> payload,
                          String requiredPermission, UUID orgUnitId, String orgUnitName, UUID requestedBy, String requestedByName, Instant requestedAt,
                          String status, Instant expiresAt, UUID decidedBy, String decidedByName, Instant decidedAt, String decisionReason, Instant escalatedAt,
                          long version, boolean canDecide, int eligibleApprovers) {}

    private final JdbcClient jdbc;
    private final Authz authz;
    private final AuditLog audit;
    private final Outbox outbox;
    private final JsonMapper json;
    private final TransactionTemplate tx;
    private final JobLeases leases;
    private final Map<String, ApprovalHandler> handlers;

    ApprovalService(JdbcClient jdbc, Authz authz, AuditLog audit, Outbox outbox, JsonMapper json, TransactionTemplate tx, JobLeases leases,
                    List<ApprovalHandler> handlers) {
        this.jdbc = jdbc;
        this.authz = authz;
        this.audit = audit;
        this.outbox = outbox;
        this.json = json;
        this.tx = tx;
        this.leases = leases;
        this.handlers = handlers.stream().collect(Collectors.toMap(ApprovalHandler::type, Function.identity()));
    }

    // ================================================================================================ requesting

    /**
     * Opens a request. The caller must itself hold {@code requiredPermission} at the unit (you can only ask for what you
     * could otherwise do). Returns the request id. One open request per type and subject.
     */
    @Transactional
    public UUID request(String type, String subjectType, String subjectId, String summary, Map<String, Object> payload, String requiredPermission,
                        UUID orgUnitId, Duration ttl) {
        handler(type);
        if (!authz.hasInOrgUnit(requiredPermission, orgUnitId)) throw new AccessDeniedException("Missing permission " + requiredPermission);
        UUID id = Ids.newId();
        String body = json.writeValueAsString(payload == null ? Map.of() : payload);
        try {
            jdbc.sql("""
                    INSERT INTO approval_request (id, tenant_id, type, subject_type, subject_id, summary, payload, payload_hash, required_permission, org_unit_id,
                                                  requested_by, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?)""")
                    .params(id, TenantContext.requireTenantId(), type, subjectType, subjectId, truncate(summary, 300), body, sha256(body), requiredPermission,
                            orgUnitId, actor(), Timestamp.from(Instant.now().plus(ttl == null ? DEFAULT_TTL : ttl))).update();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("approval_pending", "An approval for this is already pending.");
        }
        audit.recordSuccess("approval.requested", "approval_request", id.toString(), Map.of("type", type, "subject", subjectType + ":" + subjectId,
                "requiredPermission", requiredPermission));
        outbox.append("approval_request", id.toString(), "approval.requested", Map.of("type", type, "summary", truncate(summary, 300)));
        return id;
    }

    // ================================================================================================ deciding

    private enum Outcome { EXPIRED, SELF_DECISION }

    /**
     * Decides a request. Expiry found at decision time is committed (the request becomes EXPIRED) before the refusal is
     * returned; a self-decision attempt is audited after the deciding transaction ended, so it never needs a second
     * pooled connection.
     */
    public Request decide(UUID id, boolean approve, String reason, Long version) {
        Object result = tx.execute(s -> decideInTransaction(id, approve, reason, version));
        if (result == Outcome.EXPIRED) throw ApiException.conflict("approval_expired", "This request has expired.");
        if (result == Outcome.SELF_DECISION) {
            audit.recordDenied("approval.self_decision_denied", Map.of("approvalId", id.toString()));
            throw ApiException.conflict("four_eyes_required", "You requested this, so someone else must decide it.");
        }
        return (Request) result;
    }

    private Object decideInTransaction(UUID id, boolean approve, String reason, Long version) {
        Request r = find(id, true);
        if (!"PENDING".equals(r.status())) throw ApiException.conflict("approval_not_pending", "This request is no longer pending.");
        if (r.expiresAt().isBefore(Instant.now())) {
            expireOne(r);
            return Outcome.EXPIRED;
        }
        UUID me = actor();
        if (me.equals(r.requestedBy())) return Outcome.SELF_DECISION;
        if (!authz.hasInOrgUnit(r.requiredPermission(), r.orgUnitId())) {
            throw new AccessDeniedException("Missing permission " + r.requiredPermission());
        }
        String why = reason == null || reason.isBlank() ? null : truncate(reason.trim(), 500);
        if (!approve && why == null) throw ApiException.badRequest("reason_required", "Give a reason for rejecting.");
        ApprovalHandler h = handler(r.type());
        if (approve && !h.unchanged(r)) throw ApiException.conflict("subject_changed", "The request's subject changed after it was requested; ask for a new approval.");
        int n;
        try {
            n = jdbc.sql("""
                    UPDATE approval_request SET status = ?, decided_by = ?, decided_at = now(), decision_reason = ?, version = version + 1
                    WHERE id = ? AND status = 'PENDING' AND version = ?""")
                    .params(approve ? "APPROVED" : "REJECTED", me, why, id, version == null ? r.version() : version).update();
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("four_eyes_required", "You requested this, so someone else must decide it.");
        }
        if (n == 0) throw ApiException.conflict("concurrent_modification", "This request was changed by someone else. Reload and try again.");
        Request decided = find(id, false);
        if (approve) h.onApproved(decided); else h.onRejected(decided);
        Map<String, String> d = new LinkedHashMap<>();
        d.put("type", r.type());
        d.put("subject", r.subjectType() + ":" + r.subjectId());
        d.put("requestedBy", r.requestedBy().toString());
        if (why != null) d.put("reason", why);
        audit.recordSuccess(approve ? "approval.approved" : "approval.rejected", "approval_request", id.toString(), d);
        outbox.append("approval_request", id.toString(), "approval.decided", Map.of("type", r.type(), "decision", approve ? "APPROVED" : "REJECTED",
                "requestedBy", r.requestedBy().toString(), "summary", r.summary()));
        return decided;
    }

    @Transactional
    public Request cancel(UUID id) {
        Request r = find(id, true);
        if (!actor().equals(r.requestedBy())) throw new AccessDeniedException("Only the requester can cancel a request.");
        if (!"PENDING".equals(r.status())) throw ApiException.conflict("approval_not_pending", "This request is no longer pending.");
        jdbc.sql("UPDATE approval_request SET status = 'CANCELLED', version = version + 1 WHERE id = ?").param(id).update();
        audit.recordSuccess("approval.cancelled", "approval_request", id.toString(), Map.of("type", r.type()));
        Request closed = find(id, false);
        handler(r.type()).onClosed(closed);
        return closed;
    }

    /** Higher-level escalation (Q-04): asks organization-level holders of the permission to decide. Never bypasses four-eyes. */
    @Transactional
    public Request escalate(UUID id) {
        Request r = find(id, true);
        if (!"PENDING".equals(r.status())) throw ApiException.conflict("approval_not_pending", "This request is no longer pending.");
        if (!actor().equals(r.requestedBy()) && !authz.hasInOrgUnit(r.requiredPermission(), r.orgUnitId())) {
            throw new AccessDeniedException("Missing permission " + r.requiredPermission());
        }
        jdbc.sql("UPDATE approval_request SET escalated_by = ?, escalated_at = now(), version = version + 1 WHERE id = ?").params(actor(), id).update();
        audit.recordSuccess("approval.escalated", "approval_request", id.toString(), Map.of("type", r.type()));
        outbox.append("approval_request", id.toString(), "approval.escalated", Map.of("type", r.type(), "summary", r.summary()));
        return find(id, false);
    }

    // ================================================================================================ reading

    /** Requests I asked for and pending requests I may decide (newest first). */
    @Transactional(readOnly = true)
    public List<Request> mine(String view) {
        UUID me = actor();
        String where = "requests".equals(view) ? "a.requested_by = ?" : "a.status = 'PENDING' AND a.requested_by <> ?";
        List<Request> all = jdbc.sql(SELECT + " WHERE " + where + " ORDER BY a.requested_at DESC LIMIT 200").param(me).query(this::row).list();
        if ("requests".equals(view)) return all;
        return all.stream().filter(Request::canDecide).toList();
    }

    @Transactional(readOnly = true)
    public Request get(UUID id) {
        Request r = find(id, false);
        if (!actor().equals(r.requestedBy()) && !authz.hasInOrgUnit(r.requiredPermission(), r.orgUnitId())) throw ApiException.notFound("Approval request");
        return r;
    }

    /** Active users other than the requester who hold the permission at the unit (via the unit or an ancestor, or tenant-wide). */
    int eligibleApprovers(String permission, UUID unit, UUID requester) {
        return jdbc.sql("""
                SELECT count(DISTINCT ra.user_account_id) FROM role_assignment ra JOIN role r ON r.id = ra.role_id JOIN user_account u ON u.id = ra.user_account_id
                WHERE ra.revoked_at IS NULL AND ra.valid_from <= now() AND (ra.valid_to IS NULL OR ra.valid_to > now()) AND r.status = 'ACTIVE'
                  AND r.archived_at IS NULL AND ? = ANY (r.permissions) AND u.status = 'ACTIVE' AND u.id <> ?
                  AND (ra.scope_type = 'TENANT' OR (CAST(? AS uuid) IS NOT NULL AND EXISTS (
                        SELECT 1 FROM org_unit s, org_unit t WHERE s.id = ra.scope_id AND t.id = CAST(? AS uuid) AND t.path <@ s.path)))""")
                .params(permission, requester, unit, unit).query(Integer.class).single();
    }

    // ================================================================================================ expiry

    @Scheduled(fixedDelayString = "${ivorovi.approvals.expiry-interval:PT5M}", initialDelayString = "PT1M")
    void scheduledExpiry() {
        if (!leases.tryAcquire("approvals.expiry", Duration.ofMinutes(4))) return;
        List<UUID> tenants = TenantContext.callAsPlatform(() -> jdbc.sql("SELECT id FROM tenant WHERE status IN ('ACTIVE', 'TRIAL')").query(UUID.class).list());
        for (UUID t : tenants) {
            try {
                TenantContext.runInTenant(t, () -> expireDue(Instant.now()));
            } catch (RuntimeException e) {
                log.warn("Approval expiry failed for an organization: {}", e.getClass().getSimpleName());
            }
        }
    }

    /** Expires pending requests past their deadline (one organization, tenant context bound). */
    public int expireDue(Instant now) {
        Integer n = tx.execute(s -> {
            List<UUID> ids = jdbc.sql("UPDATE approval_request SET status = 'EXPIRED', version = version + 1 WHERE status = 'PENDING' AND expires_at <= ? RETURNING id")
                    .param(Timestamp.from(now)).query(UUID.class).list();
            for (UUID id : ids) {
                audit.record("approval.expired", "approval_request", id.toString(), com.ivorovi.platform.audit.AuditOutcome.SUCCESS, Map.of());
                Request expired = find(id, false);
                ApprovalHandler h = handlers.get(expired.type());
                if (h != null) h.onClosed(expired);
            }
            return ids.size();
        });
        return n == null ? 0 : n;
    }

    private void expireOne(Request r) {
        tx.executeWithoutResult(s -> {
            int n = jdbc.sql("UPDATE approval_request SET status = 'EXPIRED', version = version + 1 WHERE id = ? AND status = 'PENDING'").param(r.id()).update();
            if (n == 1) {
                audit.record("approval.expired", "approval_request", r.id().toString(), com.ivorovi.platform.audit.AuditOutcome.SUCCESS, Map.of());
                ApprovalHandler h = handlers.get(r.type());
                if (h != null) h.onClosed(find(r.id(), false));
            }
        });
    }

    // ================================================================================================ helpers

    private static final String SELECT = """
            SELECT a.id, a.type, a.subject_type, a.subject_id, a.summary, a.payload::text AS payload, a.required_permission, a.org_unit_id, ou.name AS unit,
                   a.requested_by, rq.display_name AS rq_name, a.requested_at, a.status, a.expires_at, a.decided_by, dc.display_name AS dc_name, a.decided_at,
                   a.decision_reason, a.escalated_at, a.version
            FROM approval_request a LEFT JOIN org_unit ou ON ou.id = a.org_unit_id
            LEFT JOIN user_account rq ON rq.id = a.requested_by LEFT JOIN user_account dc ON dc.id = a.decided_by""";

    private Request find(UUID id, boolean lock) {
        return jdbc.sql(SELECT + " WHERE a.id = ?" + (lock ? " FOR UPDATE OF a" : "")).param(id).query(this::row).optional()
                .orElseThrow(() -> ApiException.notFound("Approval request"));
    }

    @SuppressWarnings("unchecked")
    private Request row(ResultSet rs, int n) throws SQLException {
        String type = rs.getString("type");
        ApprovalHandler h = handlers.get(type);
        UUID requester = rs.getObject("requested_by", UUID.class);
        String perm = rs.getString("required_permission");
        UUID unit = rs.getObject("org_unit_id", UUID.class);
        boolean pending = "PENDING".equals(rs.getString("status"));
        boolean canDecide = pending && CurrentUser.get().map(p -> !p.userId().equals(requester)).orElse(false) && authz.hasInOrgUnit(perm, unit);
        return new Request(rs.getObject("id", UUID.class), type, h == null ? type : h.label(), rs.getString("subject_type"), rs.getString("subject_id"),
                rs.getString("summary"), json.readValue(rs.getString("payload"), Map.class), perm, unit, rs.getString("unit"), requester,
                rs.getString("rq_name"), ts(rs, "requested_at"), rs.getString("status"), ts(rs, "expires_at"), rs.getObject("decided_by", UUID.class),
                rs.getString("dc_name"), ts(rs, "decided_at"), rs.getString("decision_reason"), ts(rs, "escalated_at"), rs.getLong("version"), canDecide,
                pending ? eligibleApprovers(perm, unit, requester) : 0);
    }

    private ApprovalHandler handler(String type) {
        ApprovalHandler h = handlers.get(type);
        if (h == null) throw ApiException.badRequest("unknown_approval_type", "Unknown approval type.");
        return h;
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Instant ts(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static UUID actor() {
        return CurrentUser.require().userId();
    }
}
