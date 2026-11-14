package com.ivorovi.integrations.internal;

import com.ivorovi.platform.audit.AuditLog;
import com.ivorovi.platform.ids.Ids;
import com.ivorovi.platform.security.Authz;
import com.ivorovi.platform.security.CurrentUser;
import com.ivorovi.platform.security.Permission;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.platform.web.ApiException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * API clients: read-only machine credentials with explicit scopes (read permissions the creator holds at the client's
 * unit; integration, audit and personal-data permissions are never available), optional unit restriction, IP
 * allow-list and expiry. A key is {@code ivk_<keyId>_<secret>}; only a SHA-256 hash of the secret is stored, and the
 * key is shown once at creation or rotation. Rotation keeps the previous secret valid for a grace period.
 * <p>KI-59: scopes are the registered maximum only. What a client can do is computed on every request from current
 * authority ({@link ClientAuthorization}); owners' suspension suspends their delegated clients; resumption is manual.
 */
@Service
public class ApiClientService {

    static final String READ = Permission.INTEGRATION_READ.key();
    static final String MANAGE = Permission.INTEGRATION_MANAGE.key();
    static final int MAX_GRACE_HOURS = 168;
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Scopes an API client can never hold. Question content and answer keys are confidential examination material (Increment
     * 2.1, ADR-0009): no integration use is approved, so clients get no question access at all.
     */
    static final List<String> EXCLUDED = List.of(Permission.AUDIT_READ.key(), Permission.STUDENT_PERSONAL_READ.key(), READ,
            Permission.QUESTION_READ.key(), Permission.QUESTION_KEY_READ.key(),
            // candidate personal data and scores (Day 3): never through API clients until a reviewed integration needs them
            Permission.EXAM_SCHEDULE_READ.key(), Permission.EXAM_RESULTS_READ.key());

    @Schema(name = "ApiScope")
    public record Scope(String key, String name, String description, String domain) {}

    @Schema(name = "ApiClient")
    public record View(UUID id, String name, String description, List<String> scopes, UUID orgUnitId, String orgUnitName, List<String> allowedIps,
                       String status, Instant expiresAt, Instant lastUsedAt, int activeKeys, long requests30Days, long denied30Days, String createdBy,
                       Instant createdAt, Instant revokedAt, long version, String kind, UUID ownerUserId, String ownerName, UUID serviceIdentityId,
                       String serviceIdentityName, String suspendedReason, Instant suspendedAt) {}

    @Schema(name = "ApiClientKey")
    public record Key(UUID id, String keyId, String hint, String status, Instant createdAt, Instant expiresAt, Instant revokedAt, Instant lastUsedAt) {}

    @Schema(name = "ApiClientUsageDay")
    public record UsageDay(LocalDate day, long requests, long denied) {}

    @Schema(name = "ApiClientDetail")
    /** {@code effectiveScopes}: what the client can use right now; {@code lostScopes}: scope → reason it does not work now. */
    public record Detail(View client, List<Key> keys, List<UsageDay> usage, List<String> effectiveScopes, Map<String, String> lostScopes) {}

    /** Returned once: the full key is never retrievable again. */
    @Schema(name = "ApiClientCreated")
    public record Issued(View client, String apiKey, String keyId, Instant keyExpiresAt) {}

    public record Command(String name, String description, List<String> scopes, UUID orgUnitId, List<String> allowedIps, Instant expiresAt, Long version,
                          UUID serviceIdentityId) {
        public Command(String name, String description, List<String> scopes, UUID orgUnitId, List<String> allowedIps, Instant expiresAt, Long version) {
            this(name, description, scopes, orgUnitId, allowedIps, expiresAt, version, null);
        }
    }

    private final JdbcClient jdbc;
    private final Authz authz;
    private final AuditLog audit;
    private final ClientAuthorization authorization;
    private final com.ivorovi.platform.outbox.Outbox outbox;

    ApiClientService(JdbcClient jdbc, Authz authz, AuditLog audit, ClientAuthorization authorization, com.ivorovi.platform.outbox.Outbox outbox) {
        this.jdbc = jdbc;
        this.authz = authz;
        this.audit = audit;
        this.authorization = authorization;
        this.outbox = outbox;
    }

    static boolean assignable(Permission p) {
        return p.level() == Permission.Level.READ && p.grantable() && p.domain() != Permission.Domain.INTEGRATIONS && !EXCLUDED.contains(p.key());
    }

    /** Scopes the caller could give a client (read permissions they hold somewhere). */
    @Transactional(readOnly = true)
    public List<Scope> scopes() {
        require(READ);
        return Arrays.stream(Permission.values()).filter(ApiClientService::assignable).filter(p -> authz.hasAnywhere(p.key()))
                .map(p -> new Scope(p.key(), p.displayName(), p.description(), p.domain().label())).toList();
    }

    @Transactional(readOnly = true)
    public List<View> list() {
        require(READ);
        return jdbc.sql(SELECT + " ORDER BY c.status, lower(c.name)").query(ApiClientService::view).list();
    }

    @Transactional(readOnly = true)
    public Detail get(UUID id) {
        require(READ);
        View v = find(id);
        List<Key> keys = jdbc.sql("""
                SELECT id, key_id, hint, created_at, expires_at, revoked_at, last_used_at FROM api_client_secret WHERE client_id = ? ORDER BY created_at DESC""")
                .param(id).query((rs, n) -> {
                    Instant exp = ts(rs, 5), rev = ts(rs, 6);
                    String status = rev != null ? "REVOKED" : exp != null && exp.isBefore(Instant.now()) ? "EXPIRED" : exp != null ? "EXPIRING" : "ACTIVE";
                    return new Key(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), status, ts(rs, 4), exp, rev, ts(rs, 7));
                }).list();
        List<UsageDay> usage = jdbc.sql("SELECT day, requests, denied FROM api_client_usage WHERE client_id = ? AND day >= current_date - 29 ORDER BY day")
                .param(id).query((rs, n) -> new UsageDay(rs.getObject(1, LocalDate.class), rs.getLong(2), rs.getLong(3))).list();
        ClientAuthorization.Effective eff = authorization.evaluate(TenantContext.requireTenantId(), subject(v));
        return new Detail(v, keys, usage, eff.effectiveScopes(), eff.lostScopes());
    }

    @Transactional
    public Issued create(Command c) {
        require(MANAGE);
        String name = name(c.name());
        List<String> ips = IpRules.normalize(c.allowedIps());
        Instant expires = expiry(c.expiresAt());
        List<String> scopes;
        UUID owner = null;
        if (c.serviceIdentityId() == null) {
            scopes = scopes(c.scopes(), c.orgUnitId());          // delegated: the creator owns it and must hold every scope
            owner = actor();
        } else {
            scopes = serviceScopes(c.scopes(), c.serviceIdentityId());
        }
        UUID id = Ids.newId();
        try {
            jdbc.sql("""
                    INSERT INTO api_client (id, tenant_id, name, description, scopes, org_unit_id, allowed_ips, expires_at, created_by, kind, owner_user_id,
                                            service_identity_id)
                    VALUES (?, ?, ?, ?, CAST(? AS text[]), ?, CAST(? AS text[]), ?, ?, ?, ?, ?)""")
                    .params(id, TenantContext.requireTenantId(), name, blankToNull(c.description()), scopes.toArray(String[]::new), c.orgUnitId(),
                            ips.toArray(String[]::new), expires == null ? null : Timestamp.from(expires), actor(), owner == null ? "SERVICE" : "DELEGATED", owner,
                            c.serviceIdentityId()).update();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("name_taken", "Another API client already has this name.");
        }
        NewKey k = newKey(id, expires);
        Map<String, String> d = new LinkedHashMap<>();
        d.put("name", name);
        d.put("kind", owner == null ? "SERVICE" : "DELEGATED");
        if (owner != null) d.put("ownerUserId", owner.toString());
        else d.put("serviceIdentityId", c.serviceIdentityId().toString());
        d.put("scopes", String.join(",", scopes));
        d.put("orgUnitId", c.orgUnitId() == null ? "organization" : c.orgUnitId().toString());
        d.put("allowedIps", String.valueOf(ips.size()));
        d.put("keyId", k.keyId());
        if (expires != null) d.put("expiresAt", expires.toString());
        audit.recordSuccess("api_client.created", "api_client", id.toString(), d);
        return new Issued(find(id), k.key(), k.keyId(), expires);
    }

    /** Service clients: scopes must be read-only assignable and inside the identity's approved envelope. */
    private List<String> serviceScopes(List<String> requested, UUID identityId) {
        if (requested == null || requested.isEmpty()) throw ApiException.badRequest("scopes_required", "Choose at least one scope.");
        record I(String status, List<String> envelope) {}
        I identity = jdbc.sql("SELECT status, scope_envelope FROM service_identity WHERE id = ?").param(identityId)
                .query((rs, n) -> new I(rs.getString(1), strings(rs.getArray(2)))).optional()
                .orElseThrow(() -> ApiException.notFound("Service identity"));
        if (!"ACTIVE".equals(identity.status())) throw ApiException.conflict("service_identity_inactive", "The service identity is not active.");
        List<String> out = new ArrayList<>();
        for (String key : requested.stream().distinct().sorted().toList()) {
            Permission p = Permission.fromKey(key).orElseThrow(() -> ApiException.badRequest("invalid_scope", "Unknown scope " + key + "."));
            if (!assignable(p)) throw ApiException.badRequest("invalid_scope", "The scope " + key + " cannot be given to an API client.");
            if (!identity.envelope().contains(key)) throw ApiException.badRequest("scope_outside_envelope", "The scope " + key + " is outside the identity's approved envelope.");
            out.add(key);
        }
        return out;
    }

    // ================================================================================================ lifecycle (KI-59)

    /** Gives a delegated client to another ACTIVE user, who must hold every registered scope at the client's unit right now. */
    @Transactional
    public View reassign(UUID id, UUID newOwner, Long version) {
        require(MANAGE);
        View v = find(id);
        if (!"DELEGATED".equals(v.kind())) throw ApiException.conflict("not_delegated", "Only delegated clients have an owner.");
        if ("REVOKED".equals(v.status())) throw ApiException.conflict("api_client_revoked", "This API client has been revoked.");
        if (newOwner == null) throw ApiException.badRequest("owner_required", "Choose the new owner.");
        ClientAuthorization.Effective eff = authorization.evaluateForOwner(TenantContext.requireTenantId(), subject(v), newOwner);
        if (eff.denial() != null) throw ApiException.conflict("owner_inactive", "The new owner must be an active user who has signed in.");
        if (!eff.lostScopes().isEmpty()) {
            throw ApiException.conflict("owner_lacks_scopes", "The new owner does not hold these scopes at the client's unit: " + String.join(", ", eff.lostScopes().keySet()) + ".");
        }
        boolean resume = "SUSPENDED".equals(v.status()) && "owner_inactive".equals(v.suspendedReason());
        int n = jdbc.sql("""
                UPDATE api_client SET owner_user_id = ?, status = CASE WHEN ? THEN 'ACTIVE' ELSE status END,
                    suspended_reason = CASE WHEN ? THEN NULL ELSE suspended_reason END, suspended_at = CASE WHEN ? THEN NULL ELSE suspended_at END,
                    updated_at = now(), version = version + 1 WHERE id = ? AND version = ?""")
                .params(newOwner, resume, resume, resume, id, version == null ? v.version() : version).update();
        if (n == 0) throw ApiException.conflict("concurrent_modification", "This API client was changed by someone else. Reload and try again.");
        audit.recordSuccess("api_client.owner_changed", "api_client", id.toString(), Map.of("fromOwner", String.valueOf(v.ownerUserId()),
                "toOwner", newOwner.toString(), "scopesRevalidated", String.join(",", eff.effectiveScopes()), "resumed", String.valueOf(resume)));
        return find(id);
    }

    @Transactional
    public View suspend(UUID id, String reason) {
        require(MANAGE);
        View v = find(id);
        if (!"ACTIVE".equals(v.status())) throw ApiException.conflict("not_active", "Only an active client can be suspended.");
        String why = blankToNull(reason);
        if (why == null || why.length() > 300) throw ApiException.badRequest("reason_required", "Give a reason (at most 300 characters).");
        jdbc.sql("UPDATE api_client SET status = 'SUSPENDED', suspended_reason = 'manual', suspended_at = now(), updated_at = now(), version = version + 1 WHERE id = ?")
                .param(id).update();
        audit.recordSuccess("api_client.suspended", "api_client", id.toString(), Map.of("reason", "manual", "note", why));
        return find(id);
    }

    /** Resumes a suspended client; never automatic. The owner (or identity) must be active again. */
    @Transactional
    public View resume(UUID id) {
        require(MANAGE);
        View v = find(id);
        if (!"SUSPENDED".equals(v.status())) throw ApiException.conflict("not_suspended", "The client is not suspended.");
        ClientAuthorization.Effective eff = authorization.evaluate(TenantContext.requireTenantId(), subject(v));
        if (eff.denial() != null) {
            throw ApiException.conflict(eff.denial(), "SERVICE".equals(v.kind()) ? "The service identity is not active." : "The owner is not active; reassign the client instead.");
        }
        jdbc.sql("UPDATE api_client SET status = 'ACTIVE', suspended_reason = NULL, suspended_at = NULL, updated_at = now(), version = version + 1 WHERE id = ?")
                .param(id).update();
        audit.recordSuccess("api_client.resumed", "api_client", id.toString(), Map.of("previousReason", String.valueOf(v.suspendedReason()),
                "effectiveScopes", String.join(",", eff.effectiveScopes())));
        return find(id);
    }

    /** Removes scopes (widening needs a new client). */
    @Transactional
    public View narrowScopes(UUID id, List<String> scopes, Long version) {
        require(MANAGE);
        View v = find(id);
        if ("REVOKED".equals(v.status())) throw ApiException.conflict("api_client_revoked", "This API client has been revoked.");
        List<String> keep = scopes == null ? List.of() : scopes.stream().distinct().sorted().toList();
        if (keep.isEmpty()) throw ApiException.badRequest("scopes_required", "Keep at least one scope, or revoke the client.");
        if (!v.scopes().containsAll(keep)) throw ApiException.badRequest("scope_widening", "Scopes can only be removed; register a new client for more.");
        int n = jdbc.sql("UPDATE api_client SET scopes = CAST(? AS text[]), updated_at = now(), version = version + 1 WHERE id = ? AND version = ?")
                .params(keep.toArray(String[]::new), id, version == null ? v.version() : version).update();
        if (n == 0) throw ApiException.conflict("concurrent_modification", "This API client was changed by someone else. Reload and try again.");
        List<String> removed = v.scopes().stream().filter(x -> !keep.contains(x)).toList();
        audit.recordSuccess("api_client.scopes_changed", "api_client", id.toString(), Map.of("removed", String.join(",", removed)));
        return find(id);
    }

    /** Owner suspended or deactivated: suspend every active delegated client of that owner (event-driven; the live check already blocks them). */
    @Transactional
    public int suspendForOwner(UUID ownerUserId, String event) {
        List<Map<String, Object>> rows = jdbc.sql("""
                UPDATE api_client SET status = 'SUSPENDED', suspended_reason = 'owner_inactive', suspended_at = now(), updated_at = now(), version = version + 1
                WHERE owner_user_id = ? AND kind = 'DELEGATED' AND status = 'ACTIVE' RETURNING id, name""").param(ownerUserId).query().listOfRows();
        for (Map<String, Object> r : rows) {
            String id = r.get("id").toString();
            audit.record("api_client.suspended", "api_client", id, com.ivorovi.platform.audit.AuditOutcome.SUCCESS,
                    Map.of("reason", "owner_inactive", "ownerUserId", ownerUserId.toString(), "event", event));
            outbox.append("api_client", id, "api_client.suspended", Map.of("name", String.valueOf(r.get("name")), "reason", "owner_inactive"));
        }
        return rows.size();
    }

    ClientAuthorization.Subject subject(View v) {
        return new ClientAuthorization.Subject(v.id(), v.kind(), v.ownerUserId(), v.serviceIdentityId(), v.scopes(), v.orgUnitId());
    }

    /** Name, description, IP allow-list and expiry; scopes are fixed for the life of a client (create a new one instead). */
    @Transactional
    public View update(UUID id, Command c) {
        require(MANAGE);
        View v = active(id);
        List<String> ips = c.allowedIps() == null ? v.allowedIps() : IpRules.normalize(c.allowedIps());
        Instant expires = c.expiresAt() == null ? v.expiresAt() : expiry(c.expiresAt());
        int n;
        try {
            n = jdbc.sql("""
                    UPDATE api_client SET name = ?, description = ?, allowed_ips = CAST(? AS text[]), expires_at = ?, updated_at = now(), version = version + 1
                    WHERE id = ? AND version = ?""")
                    .params(c.name() == null ? v.name() : name(c.name()), c.description() == null ? v.description() : blankToNull(c.description()),
                            ips.toArray(String[]::new), expires == null ? null : Timestamp.from(expires), id, c.version() == null ? v.version() : c.version()).update();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("name_taken", "Another API client already has this name.");
        }
        if (n == 0) throw ApiException.conflict("concurrent_modification", "This API client was changed by someone else. Reload and try again.");
        audit.recordSuccess("api_client.updated", "api_client", id.toString(), Map.of("allowedIps", String.valueOf(ips.size()),
                "expiresAt", expires == null ? "never" : expires.toString()));
        return find(id);
    }

    /** New key; current keys stay valid for {@code graceHours} (0 = revoked now). */
    @Transactional
    public Issued rotate(UUID id, int graceHours) {
        require(MANAGE);
        if (graceHours < 0 || graceHours > MAX_GRACE_HOURS) throw ApiException.badRequest("invalid_grace_period", "The grace period must be 0 to 168 hours.");
        View v = active(id);
        if (graceHours == 0) {
            jdbc.sql("UPDATE api_client_secret SET revoked_at = now() WHERE client_id = ? AND revoked_at IS NULL").param(id).update();
        } else {
            jdbc.sql("""
                    UPDATE api_client_secret SET expires_at = LEAST(coalesce(expires_at, 'infinity'), now() + make_interval(hours => ?))
                    WHERE client_id = ? AND revoked_at IS NULL""").params(graceHours, id).update();
        }
        NewKey k = newKey(id, v.expiresAt());
        audit.recordSuccess("api_client.rotated", "api_client", id.toString(), Map.of("keyId", k.keyId(), "graceHours", String.valueOf(graceHours)));
        return new Issued(find(id), k.key(), k.keyId(), v.expiresAt());
    }

    @Transactional
    public void revokeKey(UUID id, UUID keyId) {
        require(MANAGE);
        active(id);
        String kid = jdbc.sql("UPDATE api_client_secret SET revoked_at = now() WHERE id = ? AND client_id = ? AND revoked_at IS NULL RETURNING key_id")
                .params(keyId, id).query(String.class).optional().orElseThrow(() -> ApiException.notFound("Active key"));
        audit.recordSuccess("api_client.key_revoked", "api_client", id.toString(), Map.of("keyId", kid));
    }

    @Transactional
    public View revoke(UUID id, String reason) {
        require(MANAGE);
        active(id);
        String why = blankToNull(reason);
        if (why == null || why.length() > 300) throw ApiException.badRequest("reason_required", "Give a reason (at most 300 characters); it is recorded in the audit trail.");
        jdbc.sql("UPDATE api_client SET status = 'REVOKED', revoked_at = now(), revoked_by = ?, updated_at = now(), version = version + 1 WHERE id = ?")
                .params(actor(), id).update();
        jdbc.sql("UPDATE api_client_secret SET revoked_at = now() WHERE client_id = ? AND revoked_at IS NULL").param(id).update();
        audit.recordSuccess("api_client.revoked", "api_client", id.toString(), Map.of("reason", why));
        return find(id);
    }

    // ================================================================================================ helpers

    record NewKey(String keyId, String key) {}

    private NewKey newKey(UUID clientId, Instant expires) {
        byte[] idBytes = new byte[12];
        byte[] secret = new byte[32];
        RANDOM.nextBytes(idBytes);
        RANDOM.nextBytes(secret);
        String keyId = HexFormat.of().formatHex(idBytes);
        String s = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        jdbc.sql("""
                INSERT INTO api_client_secret (id, tenant_id, client_id, key_id, secret_hash, hint, created_by, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(Ids.newId(), TenantContext.requireTenantId(), clientId, keyId, sha256(s), s.substring(s.length() - 4), actor(),
                        expires == null ? null : Timestamp.from(expires)).update();
        return new NewKey(keyId, "ivk_" + keyId + "_" + s);
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<String> scopes(List<String> requested, UUID orgUnitId) {
        if (requested == null || requested.isEmpty()) throw ApiException.badRequest("scopes_required", "Choose at least one scope.");
        if (orgUnitId != null && jdbc.sql("SELECT count(*) FROM org_unit WHERE id = ?").param(orgUnitId).query(Long.class).single() == 0) {
            throw ApiException.badRequest("invalid_org_unit", "Unknown organization unit.");
        }
        List<String> out = new ArrayList<>();
        for (String key : requested.stream().distinct().sorted().toList()) {
            Permission p = Permission.fromKey(key).orElseThrow(() -> ApiException.badRequest("invalid_scope", "Unknown scope " + key + "."));
            if (!assignable(p)) throw ApiException.badRequest("invalid_scope", "The scope " + key + " cannot be given to an API client (read-only, no personal, audit or integration data).");
            // no escalation: the creator must hold every scope where the client will use it
            if (!authz.hasInOrgUnit(key, orgUnitId)) throw new AccessDeniedException("You cannot give a scope you do not hold there: " + key);
            out.add(key);
        }
        return out;
    }

    private static Instant expiry(Instant expiresAt) {
        if (expiresAt == null) return null;
        if (expiresAt.isBefore(Instant.now().plus(Duration.ofHours(1)))) throw ApiException.badRequest("invalid_expiry", "The expiry must be at least one hour away.");
        if (expiresAt.isAfter(Instant.now().plus(Duration.ofDays(730)))) throw ApiException.badRequest("invalid_expiry", "The expiry can be at most two years away.");
        return expiresAt;
    }

    private static final String SELECT = """
            SELECT c.id, c.name, c.description, c.scopes, c.org_unit_id, ou.name AS unit, c.allowed_ips, c.status, c.expires_at, c.last_used_at,
                   (SELECT count(*) FROM api_client_secret s WHERE s.client_id = c.id AND s.revoked_at IS NULL AND (s.expires_at IS NULL OR s.expires_at > now())) AS keys,
                   (SELECT coalesce(sum(requests), 0) FROM api_client_usage g WHERE g.client_id = c.id AND g.day >= current_date - 29) AS req,
                   (SELECT coalesce(sum(denied), 0) FROM api_client_usage g WHERE g.client_id = c.id AND g.day >= current_date - 29) AS den,
                   u.display_name AS creator, c.created_at, c.revoked_at, c.version, c.kind, c.owner_user_id, o.display_name AS owner_name,
                   c.service_identity_id, si.name AS identity_name, c.suspended_reason, c.suspended_at
            FROM api_client c LEFT JOIN org_unit ou ON ou.id = c.org_unit_id LEFT JOIN user_account u ON u.id = c.created_by
            LEFT JOIN user_account o ON o.id = c.owner_user_id LEFT JOIN service_identity si ON si.id = c.service_identity_id""";

    private View find(UUID id) {
        return jdbc.sql(SELECT + " WHERE c.id = ?").param(id).query(ApiClientService::view).optional().orElseThrow(() -> ApiException.notFound("API client"));
    }

    private View active(UUID id) {
        View v = find(id);
        if ("REVOKED".equals(v.status())) throw ApiException.conflict("api_client_revoked", "This API client has been revoked.");
        if (!"ACTIVE".equals(v.status())) throw ApiException.conflict("api_client_suspended", "This API client is suspended; resume or reassign it first.");
        return v;
    }

    private static View view(ResultSet rs, int n) throws SQLException {
        return new View(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), strings(rs.getArray(4)), rs.getObject(5, UUID.class), rs.getString(6),
                strings(rs.getArray(7)), rs.getString(8), ts(rs, 9), ts(rs, 10), rs.getInt(11), rs.getLong(12), rs.getLong(13), rs.getString(14), ts(rs, 15),
                ts(rs, 16), rs.getLong(17), rs.getString(18), rs.getObject(19, UUID.class), rs.getString(20), rs.getObject(21, UUID.class), rs.getString(22),
                rs.getString(23), ts(rs, 24));
    }

    static List<String> strings(Array a) throws SQLException {
        return a == null ? List.of() : List.of((String[]) a.getArray());
    }

    private static Instant ts(ResultSet rs, int col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    private static String name(String n) {
        if (n == null || n.isBlank() || n.trim().length() > 100) throw ApiException.badRequest("invalid_name", "Give the client a name (at most 100 characters).");
        return n.trim();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private void require(String permission) {
        if (!authz.has(permission)) throw new AccessDeniedException("Missing permission " + permission);
    }

    private static UUID actor() {
        return CurrentUser.require().userId();
    }
}
