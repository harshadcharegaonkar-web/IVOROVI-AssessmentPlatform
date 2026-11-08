package com.ivorovi.integrations.internal;

import com.ivorovi.platform.audit.AuditLog;
import com.ivorovi.platform.audit.AuditOutcome;
import com.ivorovi.platform.ids.Ids;
import com.ivorovi.platform.outbox.Outbox;
import com.ivorovi.platform.security.Authz;
import com.ivorovi.platform.security.CurrentUser;
import com.ivorovi.platform.security.Permission;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.platform.web.ApiException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Service identities (KI-59): non-human principals for long-running integrations, governed independently of any one
 * employee. Read-only. Each has a business and a technical custodian (active users), and an approved scope envelope that
 * caps everything it can ever exercise. Widening the envelope needs approval by a second integration manager (never the
 * proposer); narrowing applies immediately. Roles are assigned with the usual ceiling rule and must fit the envelope.
 * Without a valid custodian for 30 days the identity is suspended.
 */
@Service
public class ServiceIdentityService {

    private static final Logger log = LoggerFactory.getLogger(ServiceIdentityService.class);
    static final String READ = Permission.INTEGRATION_READ.key();
    static final String MANAGE = Permission.INTEGRATION_MANAGE.key();
    static final Duration CUSTODIAN_GRACE = Duration.ofDays(30);

    @Schema(name = "ServiceIdentity")
    public record View(UUID id, String name, String purpose, UUID orgUnitId, String orgUnitName, UUID businessCustodianId, String businessCustodianName,
                       UUID technicalCustodianId, String technicalCustodianName, List<String> scopeEnvelope, List<String> proposedEnvelope, UUID proposedBy,
                       String proposedByName, Instant proposedAt, UUID approvedBy, String approvedByName, Instant approvedAt, String status, String statusReason,
                       Instant custodianInvalidSince, int activeRoles, int activeClients, Instant createdAt, long version) {}

    @Schema(name = "ServiceRoleAssignment")
    public record RoleAssignment(UUID id, UUID roleId, String roleCode, String roleName, String scopeType, UUID orgUnitId, String orgUnitName,
                                 List<String> permissions, List<String> outsideEnvelope, Instant createdAt) {}

    @Schema(name = "ServiceIdentityDetail")
    public record Detail(View identity, List<RoleAssignment> roles, List<ApiClientService.View> clients, List<String> effectivePermissions) {}

    public record Command(String name, String purpose, UUID orgUnitId, UUID businessCustodianId, UUID technicalCustodianId, List<String> envelope, Long version) {}

    private final JdbcClient jdbc;
    private final Authz authz;
    private final AuditLog audit;
    private final Outbox outbox;
    private final ClientAuthorization authorization;
    private final ApiClientService clients;
    private final TransactionTemplate tx;
    private final com.ivorovi.platform.jobs.JobLeases leases;

    ServiceIdentityService(JdbcClient jdbc, Authz authz, AuditLog audit, Outbox outbox, ClientAuthorization authorization, ApiClientService clients,
                           TransactionTemplate tx, com.ivorovi.platform.jobs.JobLeases leases) {
        this.leases = leases;
        this.jdbc = jdbc;
        this.authz = authz;
        this.audit = audit;
        this.outbox = outbox;
        this.authorization = authorization;
        this.clients = clients;
        this.tx = tx;
    }

    // ================================================================================================ reading

    @Transactional(readOnly = true)
    public List<View> list() {
        require(READ);
        return jdbc.sql(SELECT + " ORDER BY lower(si.name)").query(ServiceIdentityService::view).list();
    }

    @Transactional(readOnly = true)
    public Detail get(UUID id) {
        require(READ);
        View v = find(id);
        List<RoleAssignment> roles = roles(v);
        List<ApiClientService.View> cl = clients.list().stream().filter(c -> id.equals(c.serviceIdentityId())).toList();
        List<String> effective = "ACTIVE".equals(v.status())
                ? authorization.serviceGrants(TenantContext.requireTenantId(), id, v.scopeEnvelope()).stream().map(g -> g.permission()).distinct().sorted().toList()
                : List.of();
        return new Detail(v, roles, cl, effective);
    }

    // ================================================================================================ lifecycle

    /** Created PENDING_APPROVAL: the proposed envelope must be approved by a different integration manager. */
    @Transactional
    public View create(Command c) {
        require(MANAGE);
        String name = text(c.name(), 100, "invalid_name", "Give the identity a name (at most 100 characters).");
        String purpose = text(c.purpose(), 500, "purpose_required", "Describe the integration this identity serves (at most 500 characters).");
        custodians(c.businessCustodianId(), c.technicalCustodianId());
        List<String> envelope = envelope(c.envelope());
        if (c.orgUnitId() != null && jdbc.sql("SELECT count(*) FROM org_unit WHERE id = ?").param(c.orgUnitId()).query(Long.class).single() == 0) {
            throw ApiException.badRequest("invalid_org_unit", "Unknown organization unit.");
        }
        UUID id = Ids.newId();
        try {
            jdbc.sql("""
                    INSERT INTO service_identity (id, tenant_id, name, purpose, org_unit_id, business_custodian_id, technical_custodian_id, proposed_envelope,
                                                  proposed_by, proposed_at, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS text[]), ?, now(), ?)""")
                    .params(id, TenantContext.requireTenantId(), name, purpose, c.orgUnitId(), c.businessCustodianId(), c.technicalCustodianId(),
                            envelope.toArray(String[]::new), actor(), actor()).update();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("name_taken", "Another service identity already has this name.");
        }
        Map<String, String> d = new LinkedHashMap<>();
        d.put("name", name);
        d.put("businessCustodianId", c.businessCustodianId().toString());
        d.put("technicalCustodianId", c.technicalCustodianId().toString());
        d.put("proposedEnvelope", String.join(",", envelope));
        audit.recordSuccess("service_identity.created", "service_identity", id.toString(), d);
        outbox.append("service_identity", id.toString(), "service_identity.envelope_proposed", Map.of("name", name));
        return find(id);
    }

    /** Narrowing applies at once; any widening is proposed and waits for independent approval. */
    @Transactional
    public View changeEnvelope(UUID id, List<String> requested, Long version) {
        require(MANAGE);
        View v = find(id);
        notDisabled(v);
        List<String> envelope = envelope(requested);
        if (v.scopeEnvelope().containsAll(envelope) && !"PENDING_APPROVAL".equals(v.status())) {
            List<String> removed = v.scopeEnvelope().stream().filter(p -> !envelope.contains(p)).toList();
            update("UPDATE service_identity SET scope_envelope = CAST(? AS text[]), proposed_envelope = NULL, proposed_by = NULL, proposed_at = NULL, "
                    + "updated_at = now(), version = version + 1 WHERE id = ? AND version = ?", envelope.toArray(String[]::new), id, version(v, version));
            audit.recordSuccess("service_identity.envelope_narrowed", "service_identity", id.toString(), Map.of("removed", String.join(",", removed)));
        } else {
            update("UPDATE service_identity SET proposed_envelope = CAST(? AS text[]), proposed_by = ?, proposed_at = now(), updated_at = now(), "
                    + "version = version + 1 WHERE id = ? AND version = ?", envelope.toArray(String[]::new), actor(), id, version(v, version));
            audit.recordSuccess("service_identity.envelope_proposed", "service_identity", id.toString(), Map.of("proposed", String.join(",", envelope)));
            outbox.append("service_identity", id.toString(), "service_identity.envelope_proposed", Map.of("name", v.name()));
        }
        return find(id);
    }

    /** Independent approval: the approver must not be the proposer and must hold every proposed permission at the identity's unit. */
    @Transactional
    public View approveEnvelope(UUID id, Long version) {
        require(MANAGE);
        View v = find(id);
        notDisabled(v);
        if (v.proposedEnvelope() == null) throw ApiException.conflict("nothing_to_approve", "There is no proposed envelope.");
        if (actor().equals(v.proposedBy())) {
            audit.recordDenied("service_identity.envelope_approval_denied", Map.of("serviceIdentityId", id.toString(), "reason", "same_person"));
            throw ApiException.conflict("four_eyes_required", "The envelope must be approved by someone other than the person who proposed it.");
        }
        for (String p : v.proposedEnvelope()) {
            if (!authz.hasInOrgUnit(p, v.orgUnitId())) throw new AccessDeniedException("You cannot approve a permission you do not hold there: " + p);
        }
        boolean activate = "PENDING_APPROVAL".equals(v.status());
        update("""
                UPDATE service_identity SET scope_envelope = proposed_envelope, proposed_envelope = NULL, approved_by = ?, approved_at = now(),
                    status = CASE WHEN ? THEN 'ACTIVE' ELSE status END, status_reason = CASE WHEN ? THEN NULL ELSE status_reason END,
                    updated_at = now(), version = version + 1 WHERE id = ? AND version = ?""", actor(), activate, activate, id, version(v, version));
        audit.recordSuccess("service_identity.envelope_approved", "service_identity", id.toString(), Map.of("envelope", String.join(",", v.proposedEnvelope()),
                "proposedBy", String.valueOf(v.proposedBy()), "activated", String.valueOf(activate)));
        return find(id);
    }

    @Transactional
    public View rejectEnvelope(UUID id, String reason) {
        require(MANAGE);
        View v = find(id);
        if (v.proposedEnvelope() == null) throw ApiException.conflict("nothing_to_approve", "There is no proposed envelope.");
        String why = text(reason, 300, "reason_required", "Give a reason (at most 300 characters).");
        jdbc.sql("UPDATE service_identity SET proposed_envelope = NULL, proposed_by = NULL, proposed_at = NULL, updated_at = now(), version = version + 1 WHERE id = ?")
                .param(id).update();
        audit.recordSuccess("service_identity.envelope_rejected", "service_identity", id.toString(), Map.of("reason", why));
        return find(id);
    }

    @Transactional
    public View changeCustodians(UUID id, UUID business, UUID technical, Long version) {
        require(MANAGE);
        View v = find(id);
        notDisabled(v);
        custodians(business, technical);
        update("UPDATE service_identity SET business_custodian_id = ?, technical_custodian_id = ?, custodian_invalid_since = NULL, updated_at = now(), "
                + "version = version + 1 WHERE id = ? AND version = ?", business, technical, id, version(v, version));
        audit.recordSuccess("service_identity.custodians_changed", "service_identity", id.toString(), Map.of(
                "fromBusiness", String.valueOf(v.businessCustodianId()), "toBusiness", business.toString(),
                "fromTechnical", String.valueOf(v.technicalCustodianId()), "toTechnical", technical.toString()));
        return find(id);
    }

    @Transactional
    public View suspend(UUID id, String reason) {
        require(MANAGE);
        View v = find(id);
        if (!"ACTIVE".equals(v.status())) throw ApiException.conflict("not_active", "Only an active identity can be suspended.");
        String why = text(reason, 300, "reason_required", "Give a reason (at most 300 characters).");
        jdbc.sql("UPDATE service_identity SET status = 'SUSPENDED', status_reason = 'manual', updated_at = now(), version = version + 1 WHERE id = ?").param(id).update();
        audit.recordSuccess("service_identity.suspended", "service_identity", id.toString(), Map.of("reason", "manual", "note", why));
        return find(id);
    }

    /** Never automatic: custodians must be valid and an envelope approved. */
    @Transactional
    public View resume(UUID id) {
        require(MANAGE);
        View v = find(id);
        if (!"SUSPENDED".equals(v.status())) throw ApiException.conflict("not_suspended", "The identity is not suspended.");
        if (!custodiansActive(v.businessCustodianId(), v.technicalCustodianId())) {
            throw ApiException.conflict("custodian_required", "Name active business and technical custodians before resuming.");
        }
        jdbc.sql("UPDATE service_identity SET status = 'ACTIVE', status_reason = NULL, custodian_invalid_since = NULL, updated_at = now(), version = version + 1 WHERE id = ?")
                .param(id).update();
        audit.recordSuccess("service_identity.resumed", "service_identity", id.toString(), Map.of("previousReason", String.valueOf(v.statusReason())));
        return find(id);
    }

    /** Permanent: revokes its clients and role assignments. */
    @Transactional
    public View disable(UUID id, String reason) {
        require(MANAGE);
        View v = find(id);
        notDisabled(v);
        String why = text(reason, 300, "reason_required", "Give a reason (at most 300 characters).");
        jdbc.sql("UPDATE service_identity SET status = 'DISABLED', status_reason = 'manual', updated_at = now(), version = version + 1 WHERE id = ?").param(id).update();
        int roles = jdbc.sql("UPDATE service_role_assignment SET revoked_at = now(), revoked_by = ? WHERE service_identity_id = ? AND revoked_at IS NULL")
                .params(actor(), id).update();
        int cl = jdbc.sql("UPDATE api_client SET status = 'REVOKED', revoked_at = now(), revoked_by = ?, updated_at = now(), version = version + 1 "
                + "WHERE service_identity_id = ? AND status <> 'REVOKED'").params(actor(), id).update();
        jdbc.sql("UPDATE api_client_secret SET revoked_at = now() WHERE client_id IN (SELECT id FROM api_client WHERE service_identity_id = ?) AND revoked_at IS NULL")
                .param(id).update();
        audit.recordSuccess("service_identity.disabled", "service_identity", id.toString(), Map.of("reason", why, "rolesRevoked", String.valueOf(roles),
                "clientsRevoked", String.valueOf(cl)));
        return find(id);
    }

    // ================================================================================================ roles

    /** Same ceiling rule as for people (the assigner holds every permission there) and the role must fit the envelope. */
    @Transactional
    public RoleAssignment assignRole(UUID id, UUID roleId, UUID orgUnitId) {
        require(MANAGE);
        View v = find(id);
        if (!"ACTIVE".equals(v.status())) throw ApiException.conflict("not_active", "Roles can only be assigned to an active identity.");
        record R(String code, String status, boolean archived, List<String> permissions) {}
        R role = jdbc.sql("SELECT code, status, archived_at IS NOT NULL, permissions FROM role WHERE id = ?").param(roleId)
                .query((rs, n) -> new R(rs.getString(1), rs.getString(2), rs.getBoolean(3), ApiClientService.strings(rs.getArray(4)))).optional()
                .orElseThrow(() -> ApiException.notFound("Role"));
        if (!"ACTIVE".equals(role.status()) || role.archived()) throw ApiException.conflict("role_inactive", "Only active roles can be assigned.");
        List<String> outside = role.permissions().stream().filter(p -> !v.scopeEnvelope().contains(p)).toList();
        if (!outside.isEmpty()) throw ApiException.badRequest("role_exceeds_envelope", "The role grants permissions outside the approved envelope: " + String.join(", ", outside) + ".");
        if (orgUnitId != null && jdbc.sql("SELECT count(*) FROM org_unit WHERE id = ?").param(orgUnitId).query(Long.class).single() == 0) {
            throw ApiException.badRequest("invalid_org_unit", "Unknown organization unit.");
        }
        for (String p : role.permissions()) {
            if (!authz.hasInOrgUnit(p, orgUnitId)) throw new AccessDeniedException("You cannot assign a role with permissions you do not hold there: " + p);
        }
        UUID aid = Ids.newId();
        UUID tenant = TenantContext.requireTenantId();
        jdbc.sql("""
                INSERT INTO service_role_assignment (id, tenant_id, service_identity_id, role_id, scope_type, scope_id, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?)""").params(aid, tenant, id, roleId, orgUnitId == null ? "TENANT" : "ORG_UNIT", orgUnitId == null ? tenant : orgUnitId,
                actor()).update();
        audit.recordSuccess("service_identity.role_assigned", "service_identity", id.toString(), Map.of("assignmentId", aid.toString(), "role", role.code(),
                "scope", orgUnitId == null ? "TENANT" : "ORG_UNIT:" + orgUnitId));
        return roles(find(id)).stream().filter(r -> r.id().equals(aid)).findFirst().orElseThrow();
    }

    @Transactional
    public void revokeRole(UUID id, UUID assignmentId) {
        require(MANAGE);
        find(id);
        String role = jdbc.sql("""
                UPDATE service_role_assignment sra SET revoked_at = now(), revoked_by = ? FROM role r
                WHERE sra.id = ? AND sra.service_identity_id = ? AND sra.revoked_at IS NULL AND r.id = sra.role_id RETURNING r.code""")
                .params(actor(), assignmentId, id).query(String.class).optional().orElseThrow(() -> ApiException.notFound("Role assignment"));
        audit.recordSuccess("service_identity.role_revoked", "service_identity", id.toString(), Map.of("assignmentId", assignmentId.toString(), "role", role));
    }

    // ================================================================================================ custodian monitoring

    /** A custodian was suspended or deactivated: start the 30-day clock (event-driven; the daily check is the safety net). */
    @Transactional
    public int custodianLost(UUID userId) {
        List<UUID> ids = jdbc.sql("""
                UPDATE service_identity SET custodian_invalid_since = coalesce(custodian_invalid_since, now())
                WHERE (business_custodian_id = ? OR technical_custodian_id = ?) AND status IN ('ACTIVE', 'PENDING_APPROVAL', 'SUSPENDED') RETURNING id, name""")
                .params(userId, userId).query((rs, n) -> {
                    UUID sid = rs.getObject(1, UUID.class);
                    outbox.append("service_identity", sid.toString(), "service_identity.custodian_missing", Map.of("name", rs.getString(2)));
                    return sid;
                }).list();
        for (UUID sid : ids) {
            audit.record("service_identity.custodian_invalid", "service_identity", sid.toString(), AuditOutcome.SUCCESS, Map.of("userId", userId.toString()));
        }
        return ids.size();
    }

    /** For one organization: mark/clear invalid custodians and suspend identities without a valid custodian for 30 days. */
    public int checkCustodians(Instant now) {
        Integer n = tx.execute(s -> {
            jdbc.sql("""
                    UPDATE service_identity si SET custodian_invalid_since = NULL WHERE custodian_invalid_since IS NOT NULL
                      AND EXISTS (SELECT 1 FROM user_account b WHERE b.id = si.business_custodian_id AND b.status = 'ACTIVE')
                      AND EXISTS (SELECT 1 FROM user_account t WHERE t.id = si.technical_custodian_id AND t.status = 'ACTIVE')""").update();
            jdbc.sql("""
                    UPDATE service_identity si SET custodian_invalid_since = ? WHERE custodian_invalid_since IS NULL AND status <> 'DISABLED'
                      AND (NOT EXISTS (SELECT 1 FROM user_account b WHERE b.id = si.business_custodian_id AND b.status = 'ACTIVE')
                        OR NOT EXISTS (SELECT 1 FROM user_account t WHERE t.id = si.technical_custodian_id AND t.status = 'ACTIVE'))""")
                    .param(Timestamp.from(now)).update();
            List<Map<String, Object>> due = jdbc.sql("""
                    UPDATE service_identity SET status = 'SUSPENDED', status_reason = 'custodian_missing', updated_at = now(), version = version + 1
                    WHERE status = 'ACTIVE' AND custodian_invalid_since <= ? RETURNING id, name""")
                    .param(Timestamp.from(now.minus(CUSTODIAN_GRACE))).query().listOfRows();
            for (Map<String, Object> r : due) {
                audit.record("service_identity.suspended", "service_identity", r.get("id").toString(), AuditOutcome.SUCCESS,
                        Map.of("reason", "custodian_missing", "graceDays", String.valueOf(CUSTODIAN_GRACE.toDays())));
                outbox.append("service_identity", r.get("id").toString(), "service_identity.suspended", Map.of("name", String.valueOf(r.get("name")),
                        "reason", "custodian_missing"));
            }
            return due.size();
        });
        return n == null ? 0 : n;
    }

    @Scheduled(cron = "${ivorovi.integrations.custodian-check-cron:0 17 * * * *}")
    void scheduledCustodianCheck() {
        if (!leases.tryAcquire("integrations.custodian-check", Duration.ofMinutes(55))) return;   // KI-61: one instance
        List<UUID> tenants = TenantContext.callAsPlatform(() -> jdbc.sql("SELECT id FROM tenant WHERE status IN ('ACTIVE', 'TRIAL')").query(UUID.class).list());
        for (UUID t : tenants) {
            try {
                TenantContext.runInTenant(t, () -> checkCustodians(Instant.now()));
            } catch (RuntimeException e) {
                log.warn("Custodian check failed for an organization: {}", e.getClass().getSimpleName());
            }
        }
    }

    // ================================================================================================ helpers

    private static final String SELECT = """
            SELECT si.id, si.name, si.purpose, si.org_unit_id, ou.name AS unit, si.business_custodian_id, b.display_name AS bname, si.technical_custodian_id,
                   t.display_name AS tname, si.scope_envelope, si.proposed_envelope, si.proposed_by, p.display_name AS pname, si.proposed_at, si.approved_by,
                   a.display_name AS aname, si.approved_at, si.status, si.status_reason, si.custodian_invalid_since,
                   (SELECT count(*) FROM service_role_assignment r WHERE r.service_identity_id = si.id AND r.revoked_at IS NULL) AS roles,
                   (SELECT count(*) FROM api_client c WHERE c.service_identity_id = si.id AND c.status <> 'REVOKED') AS clients, si.created_at, si.version
            FROM service_identity si LEFT JOIN org_unit ou ON ou.id = si.org_unit_id
            LEFT JOIN user_account b ON b.id = si.business_custodian_id LEFT JOIN user_account t ON t.id = si.technical_custodian_id
            LEFT JOIN user_account p ON p.id = si.proposed_by LEFT JOIN user_account a ON a.id = si.approved_by""";

    private View find(UUID id) {
        return jdbc.sql(SELECT + " WHERE si.id = ?").param(id).query(ServiceIdentityService::view).optional()
                .orElseThrow(() -> ApiException.notFound("Service identity"));
    }

    private static View view(ResultSet rs, int n) throws SQLException {
        return new View(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getObject(4, UUID.class), rs.getString(5), rs.getObject(6, UUID.class),
                rs.getString(7), rs.getObject(8, UUID.class), rs.getString(9), ApiClientService.strings(rs.getArray(10)),
                rs.getArray(11) == null ? null : ApiClientService.strings(rs.getArray(11)), rs.getObject(12, UUID.class), rs.getString(13), ts(rs, 14),
                rs.getObject(15, UUID.class), rs.getString(16), ts(rs, 17), rs.getString(18), rs.getString(19), ts(rs, 20), rs.getInt(21), rs.getInt(22),
                ts(rs, 23), rs.getLong(24));
    }

    private List<RoleAssignment> roles(View v) {
        return jdbc.sql("""
                SELECT sra.id, r.id, r.code, r.name, sra.scope_type, CASE WHEN sra.scope_type = 'ORG_UNIT' THEN sra.scope_id END, ou.name, r.permissions, sra.created_at
                FROM service_role_assignment sra JOIN role r ON r.id = sra.role_id LEFT JOIN org_unit ou ON ou.id = sra.scope_id
                WHERE sra.service_identity_id = ? AND sra.revoked_at IS NULL ORDER BY sra.created_at""").param(v.id())
                .query((rs, n) -> {
                    List<String> perms = ApiClientService.strings(rs.getArray(8));
                    return new RoleAssignment(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getString(5),
                            rs.getObject(6, UUID.class), rs.getString(7), perms, perms.stream().filter(p -> !v.scopeEnvelope().contains(p)).toList(), ts(rs, 9));
                }).list();
    }

    private void custodians(UUID business, UUID technical) {
        if (business == null || technical == null) throw ApiException.badRequest("custodians_required", "Name a business custodian and a technical custodian.");
        if (business.equals(technical)) {
            throw ApiException.badRequest("custodians_must_differ", "The business custodian and the technical custodian must be two different people.");
        }
        if (!custodiansActive(business, technical)) throw ApiException.badRequest("custodian_inactive", "Custodians must be active users of this organization.");
    }

    private boolean custodiansActive(UUID business, UUID technical) {
        // two different, active people (decision Q-01); the database enforces the difference as well
        return !business.equals(technical) && jdbc.sql("SELECT count(DISTINCT id) FROM user_account WHERE id IN (?, ?) AND status = 'ACTIVE'")
                .params(business, technical).query(Long.class).single() == 2;
    }

    private static List<String> envelope(List<String> requested) {
        if (requested == null || requested.isEmpty()) throw ApiException.badRequest("envelope_required", "Choose at least one permission for the envelope.");
        List<String> out = new ArrayList<>();
        for (String key : requested.stream().distinct().sorted().toList()) {
            Permission p = Permission.fromKey(key).orElseThrow(() -> ApiException.badRequest("invalid_scope", "Unknown permission " + key + "."));
            if (!ApiClientService.assignable(p)) throw ApiException.badRequest("invalid_scope", "Service identities are read-only and cannot hold " + key + ".");
            out.add(key);
        }
        return out;
    }

    private void update(String sql, Object... params) {
        if (jdbc.sql(sql).params(params).update() == 0) {
            throw ApiException.conflict("concurrent_modification", "This service identity was changed by someone else. Reload and try again.");
        }
    }

    private static long version(View v, Long requested) {
        return requested == null ? v.version() : requested;
    }

    private static void notDisabled(View v) {
        if ("DISABLED".equals(v.status())) throw ApiException.conflict("service_identity_disabled", "This service identity is disabled.");
    }

    private static String text(String s, int max, String code, String message) {
        if (s == null || s.isBlank() || s.trim().length() > max) throw ApiException.badRequest(code, message);
        return s.trim();
    }

    private static Instant ts(ResultSet rs, int col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    private void require(String permission) {
        if (!authz.has(permission)) throw new AccessDeniedException("Missing permission " + permission);
    }

    private static UUID actor() {
        return CurrentUser.require().userId();
    }
}
