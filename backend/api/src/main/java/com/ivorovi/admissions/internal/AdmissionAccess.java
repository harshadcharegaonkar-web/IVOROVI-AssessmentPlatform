package com.ivorovi.admissions.internal;

import com.ivorovi.platform.audit.AuditLog;
import com.ivorovi.platform.ids.Ids;
import com.ivorovi.platform.security.Authz;
import com.ivorovi.platform.security.CurrentUser;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.platform.web.ApiException;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/** Permission checks at the cycle's institution, the application event log and audit (ids and codes only). */
@Component
public class AdmissionAccess {

    public static final String READ = "admission.read";
    public static final String MANAGE = "admission.manage";
    public static final String DECIDE = "admission.decide";

    private final JdbcClient jdbc;
    private final Authz authz;
    private final AuditLog audit;

    AdmissionAccess(JdbcClient jdbc, Authz authz, AuditLog audit) {
        this.jdbc = jdbc;
        this.authz = authz;
        this.audit = audit;
    }

    static UUID tenant() {
        return TenantContext.requireTenantId();
    }

    static UUID actor() {
        return CurrentUser.require().userId();
    }

    boolean can(String permission, UUID institutionId) {
        return authz.hasInOrgUnit(permission, institutionId);
    }

    boolean anywhere(String permission) {
        return authz.hasAnywhere(permission);
    }

    /** 404 outside read scope, 403 (audited) without the permission inside it. */
    void require(String permission, UUID institutionId, String resource, UUID id) {
        if (!authz.hasInOrgUnit(READ, institutionId)) throw ApiException.notFound(resource);
        if (!authz.hasInOrgUnit(permission, institutionId)) {
            audit.recordDenied("admission.access_denied", Map.of("resource", resource, "id", id == null ? "" : id.toString(), "permission", permission));
            throw new AccessDeniedException("Missing permission " + permission);
        }
    }

    /** The institution of a cycle (404 when it does not exist or lies outside the caller's read scope). */
    UUID cycleInstitution(UUID cycleId) {
        UUID inst = jdbc.sql("SELECT institution_id FROM admission_cycle WHERE id = ?").param(cycleId).query(UUID.class).optional()
                .orElseThrow(() -> ApiException.notFound("Admission cycle"));
        if (!authz.hasInOrgUnit(READ, inst)) throw ApiException.notFound("Admission cycle");
        return inst;
    }

    void audit(String action, String type, UUID id, Map<String, String> details) {
        audit.recordSuccess(action, type, id.toString(), details);
    }

    void denied(String action, Map<String, String> details) {
        audit.recordDenied(action, details);
    }

    /** Appends to the application's event log (append-only); {@code reason} must never carry personal values. */
    void event(UUID applicationId, String from, String to, String reason, String actorKind) {
        UUID who = "STAFF".equals(actorKind) ? actor() : null;
        jdbc.sql("""
                INSERT INTO admission_application_event (id, tenant_id, application_id, from_status, to_status, reason, actor_id, actor_kind)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""").params(Ids.newId(), tenant(), applicationId, from, to, reason, who, actorKind).update();
    }

    static String text(String value, int max, String field, boolean required) {
        String v = value == null ? null : value.strip();
        if (v == null || v.isEmpty()) {
            if (required) throw ApiException.badRequest("invalid_" + field, "Enter the " + field + ".");
            return null;
        }
        if (v.length() > max) throw ApiException.badRequest("invalid_" + field, "At most " + max + " characters.");
        return v;
    }

    static void version(Long given, long current) {
        if (given == null || given != current) throw ApiException.conflict("concurrent_modification", "This record was changed by someone else. Reload and try again.");
    }
}
