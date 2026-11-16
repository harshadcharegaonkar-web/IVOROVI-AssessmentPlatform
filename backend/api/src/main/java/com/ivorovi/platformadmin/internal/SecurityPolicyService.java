package com.ivorovi.platformadmin.internal;

import com.ivorovi.approvals.ApprovalHandler;
import com.ivorovi.approvals.ApprovalService;
import com.ivorovi.identity.IdentityProvisioner;
import com.ivorovi.platform.audit.AuditLog;
import com.ivorovi.platform.security.Authz;
import com.ivorovi.platform.security.IvoroviAuthentication;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.platform.web.ApiException;
import com.ivorovi.platformadmin.PlatformPolicies.SecurityPolicy;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * The organization security policy for staff sessions (mandatory module I). Strengthening applies at once; any change
 * that weakens a rule (second factor off, a longer or removed limit) needs a second holder of
 * {@code tenant.security.manage} to approve it (ADR-0007), and is applied only then. Turning the second factor on
 * requires the administrator to have one already, so an organization cannot lock all its administrators out through a
 * provider that does not deliver the factor.
 */
@Service
public class SecurityPolicyService implements ApprovalHandler {

    public static final String PERMISSION = "tenant.security.manage";
    static final String APPROVAL_TYPE = "tenant.security_policy";
    /** {@code amr} values that count as a second factor (Keycloak: authenticator app; WebAuthn hardware or software keys). */
    static final Set<String> SECOND_FACTORS = Set.of("otp", "hwk", "swk", "mfa");
    private static final Duration CACHE_TTL = Duration.ofSeconds(30);

    @Schema(name = "SecurityPolicyView")
    public record View(boolean mfaRequired, Integer idleMinutes, Integer absoluteHours, long version, Instant updatedAt,
                       String passwordPolicyScope, UUID pendingApprovalId, Change pendingChange, boolean callerHasSecondFactor) {}

    @Schema(name = "SecurityPolicyChange")
    public record Change(boolean mfaRequired, Integer idleMinutes, Integer absoluteHours) {}

    @Schema(name = "SecurityPolicyUpdate")
    public record Update(boolean mfaRequired, Integer idleMinutes, Integer absoluteHours, long version) {}

    @Schema(name = "SecurityPolicyUpdateResult")
    public record Result(String status, UUID approvalRequestId, View policy) {}

    private record Row(SecurityPolicy policy, long version, Instant updatedAt) {}

    private record Cached(SecurityPolicy policy, Instant loadedAt) {}

    private final JdbcClient jdbc;
    private final Authz authz;
    private final AuditLog audit;
    private final ApprovalService approvals;
    private final IdentityProvisioner idp;
    private final JsonMapper json;
    private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();
    private final Map<String, Instant> enrolled = new ConcurrentHashMap<>();
    private static final Duration ENROLLED_TTL = Duration.ofSeconds(60);

    SecurityPolicyService(JdbcClient jdbc, Authz authz, AuditLog audit, @Lazy ApprovalService approvals, IdentityProvisioner idp, JsonMapper json) {
        this.jdbc = jdbc;
        this.authz = authz;
        this.audit = audit;
        this.approvals = approvals;
        this.idp = idp;
        this.json = json;
    }

    // ================================================================================================ reading

    /** The policy in force, cached for 30 s per instance (an update on this instance clears it at once). */
    public SecurityPolicy effective(UUID tenantId) {
        Cached c = cache.get(tenantId);
        if (c != null && c.loadedAt().plus(CACHE_TTL).isAfter(Instant.now())) return c.policy();
        SecurityPolicy p = TenantContext.callInTenant(tenantId, () -> row().map(Row::policy).orElse(SecurityPolicy.NONE));
        cache.put(tenantId, new Cached(p, Instant.now()));
        return p;
    }

    private Optional<Row> row() {
        return jdbc.sql("SELECT mfa_required, idle_minutes, absolute_hours, version, updated_at FROM tenant_security_policy WHERE tenant_id = current_tenant_id()")
                .query((rs, n) -> new Row(new SecurityPolicy(rs.getBoolean(1), (Integer) rs.getObject(2), (Integer) rs.getObject(3)), rs.getLong(4),
                        rs.getTimestamp(5).toInstant())).optional();
    }

    @Transactional(readOnly = true)
    public View view() {
        require();
        return view(row());
    }

    @SuppressWarnings("unchecked")
    private View view(Optional<Row> r) {
        record Pending(UUID id, Map<String, Object> payload) {}
        Optional<Pending> pending = jdbc.sql("""
                SELECT id, payload::text FROM approval_request WHERE type = ? AND subject_type = 'tenant_security_policy' AND status = 'PENDING' AND expires_at > now()
                ORDER BY requested_at DESC LIMIT 1""").param(APPROVAL_TYPE)
                .query((rs, n) -> new Pending(rs.getObject(1, UUID.class), json.readValue(rs.getString(2), Map.class))).optional();
        UUID pendingId = pending.map(Pending::id).orElse(null);
        Change pendingChange = pending.map(x -> new Change(Boolean.TRUE.equals(x.payload().get("mfaRequired")), intOrNull(x.payload().get("idleMinutes")),
                intOrNull(x.payload().get("absoluteHours")))).orElse(null);
        SecurityPolicy p = r.map(Row::policy).orElse(SecurityPolicy.NONE);
        return new View(p.mfaRequired(), p.idleMinutes(), p.absoluteHours(), r.map(Row::version).orElse(0L), r.map(Row::updatedAt).orElse(null),
                "PLATFORM", pendingId, pendingChange, callerHasSecondFactor());
    }

    // ================================================================================================ updating

    @Transactional
    public Result update(Update in) {
        require();
        Change target = normalize(in);
        Optional<Row> current = row();
        long version = current.map(Row::version).orElse(0L);
        if (in.version() != version) throw ApiException.conflict("concurrent_modification", "The security policy was changed. Reload and try again.");
        SecurityPolicy before = current.map(Row::policy).orElse(SecurityPolicy.NONE);
        SecurityPolicy after = new SecurityPolicy(target.mfaRequired(), target.idleMinutes(), target.absoluteHours());
        if (before.equals(after)) return new Result("UNCHANGED", null, view(current));
        if (after.mfaRequired() && !before.mfaRequired() && !callerHasSecondFactor()) {
            // prove the second factor works for this organization before requiring it of everyone
            requestEnrolment(me(), subject());
            throw ApiException.conflict("second_factor_needed_first",
                    "Set up an authenticator app for your own account first: sign in again and follow the set-up step, then turn this on.");
        }
        if (weaker(before, after)) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("mfaRequired", after.mfaRequired());
            payload.put("idleMinutes", after.idleMinutes());
            payload.put("absoluteHours", after.absoluteHours());
            payload.put("fromVersion", version);
            payload.put("from", describe(before));
            UUID id = approvals.request(APPROVAL_TYPE, "tenant_security_policy", TenantContext.requireTenantId().toString(),
                    "Weaken the organization security policy: " + describe(after), payload, PERMISSION, null, Duration.ofDays(7));
            audit.recordSuccess("tenant.security_policy.weakening_requested", "tenant_security_policy", TenantContext.requireTenantId().toString(),
                    Map.of("from", describe(before), "to", describe(after)));
            return new Result("PENDING_APPROVAL", id, view(current));
        }
        write(after, version);
        audit.recordSuccess("tenant.security_policy.changed", "tenant_security_policy", TenantContext.requireTenantId().toString(),
                Map.of("from", describe(before), "to", describe(after)));
        return new Result("APPLIED", null, view(row()));
    }

    private void write(SecurityPolicy p, long expectedVersion) {
        UUID tenant = TenantContext.requireTenantId();
        int n = jdbc.sql("""
                INSERT INTO tenant_security_policy (tenant_id, mfa_required, idle_minutes, absolute_hours, updated_by, version) VALUES (?, ?, ?, ?, ?, 1)
                ON CONFLICT (tenant_id) DO UPDATE SET mfa_required = EXCLUDED.mfa_required, idle_minutes = EXCLUDED.idle_minutes,
                    absolute_hours = EXCLUDED.absolute_hours, updated_by = EXCLUDED.updated_by, updated_at = now(), version = tenant_security_policy.version + 1
                WHERE tenant_security_policy.version = ?""")
                .params(tenant, p.mfaRequired(), p.idleMinutes(), p.absoluteHours(), me(), expectedVersion).update();
        if (n == 0) throw ApiException.conflict("concurrent_modification", "The security policy was changed. Reload and try again.");
        cache.remove(tenant);
    }

    static boolean weaker(SecurityPolicy before, SecurityPolicy after) {
        if (before.mfaRequired() && !after.mfaRequired()) return true;
        return looser(before.idleMinutes(), after.idleMinutes()) || looser(before.absoluteHours(), after.absoluteHours());
    }

    /** A limit is looser when it is removed or larger. */
    private static boolean looser(Integer before, Integer after) {
        if (before == null) return false;
        return after == null || after > before;
    }

    private static Change normalize(Update in) {
        if (in.idleMinutes() != null && (in.idleMinutes() < 5 || in.idleMinutes() > 1440))
            throw ApiException.badRequest("invalid_idle_limit", "The idle limit must be between 5 and 1440 minutes.");
        if (in.absoluteHours() != null && (in.absoluteHours() < 1 || in.absoluteHours() > 720))
            throw ApiException.badRequest("invalid_session_limit", "The session limit must be between 1 and 720 hours.");
        if (in.idleMinutes() != null && in.absoluteHours() != null && in.idleMinutes() > in.absoluteHours() * 60)
            throw ApiException.badRequest("invalid_idle_limit", "The idle limit cannot be longer than the session limit.");
        return new Change(in.mfaRequired(), in.idleMinutes(), in.absoluteHours());
    }

    static String describe(SecurityPolicy p) {
        return "second factor " + (p.mfaRequired() ? "required" : "optional") + ", idle " + (p.idleMinutes() == null ? "no limit" : p.idleMinutes() + " min")
                + ", session " + (p.absoluteHours() == null ? "no limit" : p.absoluteHours() + " h");
    }

    // ================================================================================================ approval handler

    @Override
    public String type() {
        return APPROVAL_TYPE;
    }

    @Override
    public String label() {
        return "Weaker security policy";
    }

    @Override
    public boolean unchanged(ApprovalService.Request r) {
        long from = ((Number) r.payload().get("fromVersion")).longValue();
        return row().map(Row::version).orElse(0L) == from;
    }

    @Override
    public void onApproved(ApprovalService.Request r) {
        Map<String, Object> p = r.payload();
        SecurityPolicy after = new SecurityPolicy(Boolean.TRUE.equals(p.get("mfaRequired")), intOrNull(p.get("idleMinutes")), intOrNull(p.get("absoluteHours")));
        long from = ((Number) p.get("fromVersion")).longValue();
        SecurityPolicy before = row().map(Row::policy).orElse(SecurityPolicy.NONE);
        write(after, from);
        audit.recordSuccess("tenant.security_policy.changed", "tenant_security_policy", TenantContext.requireTenantId().toString(),
                Map.of("from", describe(before), "to", describe(after), "approvalRequestId", r.id().toString()));
    }

    // ================================================================================================ helpers

    /** Requests authenticator enrolment once per user at the identity provider; true when newly requested. */
    boolean requestEnrolment(UUID userId, String subject) {
        boolean already = jdbc.sql("SELECT EXISTS (SELECT 1 FROM staff_mfa_enrolment_request WHERE tenant_id = current_tenant_id() AND user_id = ?)")
                .param(userId).query(Boolean.class).single();
        if (already) return false;
        idp.requireAuthenticatorSetup(subject);                              // provider failure: nothing recorded, asked again next time
        jdbc.sql("INSERT INTO staff_mfa_enrolment_request (tenant_id, user_id) VALUES (?, ?) ON CONFLICT DO NOTHING")
                .params(TenantContext.requireTenantId(), userId).update();
        audit.recordSuccess("tenant.security_policy.mfa_enrolment_requested", "user", userId.toString(), Map.of());
        return true;
    }

    private void require() {
        if (!authz.has(PERMISSION)) throw new AccessDeniedException("Missing permission " + PERMISSION);
    }

    private static Optional<IvoroviAuthentication> auth() {
        return SecurityContextHolder.getContext().getAuthentication() instanceof IvoroviAuthentication a ? Optional.of(a) : Optional.empty();
    }

    static boolean usedSecondFactor(List<String> amr) {
        return amr != null && amr.stream().anyMatch(SECOND_FACTORS::contains);
    }

    private boolean callerHasSecondFactor() {
        return auth().map(a -> hasSecondFactor(a.getPrincipal().subject(), a.getCredentials() == null ? List.of() : a.getCredentials().getClaimAsStringList("amr")))
                .orElse(false);
    }

    /**
     * True when the sign-in used a second factor: the token's {@code amr} names one, or the user has an authenticator
     * enrolled (the staff realm then requires it at every sign-in; Keycloak's default flows leave {@code amr} empty).
     * Positive answers are cached for 60 s; negative ones are not, so a user who just enrolled is never refused again.
     * An identity provider that cannot answer counts as "no" (fail closed).
     */
    boolean hasSecondFactor(String subject, List<String> amr) {
        if (usedSecondFactor(amr)) return true;
        Instant ok = enrolled.get(subject);
        if (ok != null && ok.plus(ENROLLED_TTL).isAfter(Instant.now())) return true;
        boolean has;
        try {
            has = idp.hasAuthenticator(subject);
        } catch (RuntimeException e) {
            return false;
        }
        if (has) enrolled.put(subject, Instant.now());
        return has;
    }

    private static UUID me() {
        return auth().map(a -> a.getPrincipal().userId()).orElseThrow(() -> new AccessDeniedException("Staff sign-in required"));
    }

    private static String subject() {
        return auth().map(a -> a.getPrincipal().subject()).orElseThrow(() -> new AccessDeniedException("Staff sign-in required"));
    }

    private static Integer intOrNull(Object o) {
        return o == null ? null : ((Number) o).intValue();
    }
}
