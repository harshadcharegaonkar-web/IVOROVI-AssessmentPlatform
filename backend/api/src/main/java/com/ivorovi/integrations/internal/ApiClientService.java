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
 */
@Service
public class ApiClientService {

    static final String READ = Permission.INTEGRATION_READ.key();
    static final String MANAGE = Permission.INTEGRATION_MANAGE.key();
    static final int MAX_GRACE_HOURS = 168;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Scopes an API client can never hold. */
    static final List<String> EXCLUDED = List.of(Permission.AUDIT_READ.key(), Permission.STUDENT_PERSONAL_READ.key(), READ);

    @Schema(name = "ApiScope")
    public record Scope(String key, String name, String description, String domain) {}

    @Schema(name = "ApiClient")
    public record View(UUID id, String name, String description, List<String> scopes, UUID orgUnitId, String orgUnitName, List<String> allowedIps,
                       String status, Instant expiresAt, Instant lastUsedAt, int activeKeys, long requests30Days, long denied30Days, String createdBy,
                       Instant createdAt, Instant revokedAt, long version) {}

    @Schema(name = "ApiClientKey")
    public record Key(UUID id, String keyId, String hint, String status, Instant createdAt, Instant expiresAt, Instant revokedAt, Instant lastUsedAt) {}

    @Schema(name = "ApiClientUsageDay")
    public record UsageDay(LocalDate day, long requests, long denied) {}

    @Schema(name = "ApiClientDetail")
    public record Detail(View client, List<Key> keys, List<UsageDay> usage) {}

    /** Returned once: the full key is never retrievable again. */
    @Schema(name = "ApiClientCreated")
    public record Issued(View client, String apiKey, String keyId, Instant keyExpiresAt) {}

    public record Command(String name, String description, List<String> scopes, UUID orgUnitId, List<String> allowedIps, Instant expiresAt, Long version) {}

    private final JdbcClient jdbc;
    private final Authz authz;
    private final AuditLog audit;

    ApiClientService(JdbcClient jdbc, Authz authz, AuditLog audit) {
        this.jdbc = jdbc;
        this.authz = authz;
        this.audit = audit;
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
        return new Detail(v, keys, usage);
    }

    @Transactional
    public Issued create(Command c) {
        require(MANAGE);
        String name = name(c.name());
        List<String> scopes = scopes(c.scopes(), c.orgUnitId());
        List<String> ips = IpRules.normalize(c.allowedIps());
        Instant expires = expiry(c.expiresAt());
        UUID id = Ids.newId();
        try {
            jdbc.sql("""
                    INSERT INTO api_client (id, tenant_id, name, description, scopes, org_unit_id, allowed_ips, expires_at, created_by)
                    VALUES (?, ?, ?, ?, CAST(? AS text[]), ?, CAST(? AS text[]), ?, ?)""")
                    .params(id, TenantContext.requireTenantId(), name, blankToNull(c.description()), scopes.toArray(String[]::new), c.orgUnitId(),
                            ips.toArray(String[]::new), expires == null ? null : Timestamp.from(expires), actor()).update();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("name_taken", "Another API client already has this name.");
        }
        NewKey k = newKey(id, expires);
        Map<String, String> d = new LinkedHashMap<>();
        d.put("name", name);
        d.put("scopes", String.join(",", scopes));
        d.put("orgUnitId", c.orgUnitId() == null ? "organization" : c.orgUnitId().toString());
        d.put("allowedIps", String.valueOf(ips.size()));
        d.put("keyId", k.keyId());
        if (expires != null) d.put("expiresAt", expires.toString());
        audit.recordSuccess("api_client.created", "api_client", id.toString(), d);
        return new Issued(find(id), k.key(), k.keyId(), expires);
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
                   u.display_name AS creator, c.created_at, c.revoked_at, c.version
            FROM api_client c LEFT JOIN org_unit ou ON ou.id = c.org_unit_id LEFT JOIN user_account u ON u.id = c.created_by""";

    private View find(UUID id) {
        return jdbc.sql(SELECT + " WHERE c.id = ?").param(id).query(ApiClientService::view).optional().orElseThrow(() -> ApiException.notFound("API client"));
    }

    private View active(UUID id) {
        View v = find(id);
        if (!"ACTIVE".equals(v.status())) throw ApiException.conflict("api_client_revoked", "This API client has been revoked.");
        return v;
    }

    private static View view(ResultSet rs, int n) throws SQLException {
        return new View(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), strings(rs.getArray(4)), rs.getObject(5, UUID.class), rs.getString(6),
                strings(rs.getArray(7)), rs.getString(8), ts(rs, 9), ts(rs, 10), rs.getInt(11), rs.getLong(12), rs.getLong(13), rs.getString(14), ts(rs, 15),
                ts(rs, 16), rs.getLong(17));
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
