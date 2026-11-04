package com.ivorovi.integrations.internal;

import com.ivorovi.platform.audit.AuditLog;
import com.ivorovi.platform.audit.AuditOutcome;
import com.ivorovi.platform.security.ApiKeyAuthenticator;
import com.ivorovi.platform.security.IvoroviPrincipal;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.platform.tenancy.TenantDirectory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
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
 * Verifies API keys: the key id maps to its organization through a tenant-restricted lookup function; the secret is
 * then checked (constant time) inside that organization's row-level-security context together with client status,
 * expiry and IP allow-list. The resulting principal carries exactly the client's scopes, at the client's unit.
 */
@Component
class ApiClientAuthenticator implements ApiKeyAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(ApiClientAuthenticator.class);
    static final String ROLE = ClientAuthorization.ROLE;

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final TenantDirectory tenants;
    private final ClientAuthorization authorization;
    private final AuditLog audit;
    private final ApiClientUsage usage;

    ApiClientAuthenticator(JdbcClient jdbc, TransactionTemplate tx, TenantDirectory tenants, ClientAuthorization authorization, AuditLog audit,
                           ApiClientUsage usage) {
        this.usage = usage;
        this.jdbc = jdbc;
        this.tx = tx;
        this.tenants = tenants;
        this.authorization = authorization;
        this.audit = audit;
    }

    private record Row(UUID secretRowId, String hash, Instant secretExpires, Instant secretRevoked, UUID clientId, String name, String status,
                       Instant clientExpires, List<String> ips, List<String> scopes, UUID orgUnitId, Instant lastUsed, String kind, UUID owner,
                       UUID serviceIdentity) {}

    @Override
    public Result authenticate(String presentedKey, String clientIp) {
        if (presentedKey == null || !presentedKey.startsWith("ivk_") || presentedKey.length() > 120) return new Rejected("invalid_api_key", 401);
        String rest = presentedKey.substring(4);
        int sep = rest.indexOf('_');
        if (sep != 24) return new Rejected("invalid_api_key", 401);
        String keyId = rest.substring(0, sep);
        String secret = rest.substring(sep + 1);
        if (!keyId.matches("[0-9a-f]{24}") || secret.isEmpty()) return new Rejected("invalid_api_key", 401);
        UUID tenant = TenantContext.callAsPlatform(() -> tx.execute(s -> jdbc.sql("SELECT api_key_tenant(?)").param(keyId).query(UUID.class).optional().orElse(null)));
        if (tenant == null) return new Rejected("invalid_api_key", 401);
        Optional<TenantDirectory.TenantInfo> info = tenants.find(tenant);
        if (info.isEmpty() || !info.get().status().allowsAccess()) return new Rejected("invalid_api_key", 401);
        return TenantContext.callInTenant(tenant, () -> tx.execute(s -> {
            Optional<Row> found = jdbc.sql("""
                    SELECT s.id, s.secret_hash, s.expires_at, s.revoked_at, c.id, c.name, c.status, c.expires_at, c.allowed_ips, c.scopes, c.org_unit_id, s.last_used_at,
                           c.kind, c.owner_user_id, c.service_identity_id
                    FROM api_client_secret s JOIN api_client c ON c.id = s.client_id WHERE s.key_id = ?""").param(keyId)
                    .query((rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getString(2), ts(rs.getTimestamp(3)), ts(rs.getTimestamp(4)), rs.getObject(5, UUID.class),
                            rs.getString(6), rs.getString(7), ts(rs.getTimestamp(8)), ApiClientService.strings(rs.getArray(9)), ApiClientService.strings(rs.getArray(10)),
                            rs.getObject(11, UUID.class), ts(rs.getTimestamp(12)), rs.getString(13), rs.getObject(14, UUID.class), rs.getObject(15, UUID.class))).optional();
            if (found.isEmpty()) return new Rejected("invalid_api_key", 401);
            Row r = found.get();
            byte[] expected = r.hash().getBytes(StandardCharsets.US_ASCII);
            byte[] actual = ApiClientService.sha256(secret).getBytes(StandardCharsets.US_ASCII);
            if (!MessageDigest.isEqual(expected, actual)) {
                log.warn("security.api_key_rejected reason=wrong_secret keyId={}", keyId);
                return new Rejected("invalid_api_key", 401);
            }
            Instant now = Instant.now();
            if ("REVOKED".equals(r.status()) || r.secretRevoked() != null) return deny("api_key_revoked", 401, keyId, r.clientId(), tenant);
            if ("SUSPENDED".equals(r.status())) return deny("api_client_suspended", 401, keyId, r.clientId(), tenant);
            if ((r.secretExpires() != null && r.secretExpires().isBefore(now)) || (r.clientExpires() != null && r.clientExpires().isBefore(now))) {
                return deny("api_key_expired", 401, keyId, r.clientId(), tenant);
            }
            if (!IpRules.allowed(r.ips(), clientIp)) return deny("api_key_ip_not_allowed", 403, keyId, r.clientId(), tenant);
            if (r.lastUsed() == null || r.lastUsed().isBefore(now.minusSeconds(60))) {
                // coarse last-use tracking: at most one write per key per minute
                jdbc.sql("UPDATE api_client_secret SET last_used_at = now() WHERE id = ?").param(r.secretRowId()).update();
                jdbc.sql("UPDATE api_client SET last_used_at = now() WHERE id = ?").param(r.clientId()).update();
            }
            // KI-59: effective permissions from CURRENT authority, never from the stored scopes alone
            ClientAuthorization.Effective eff = authorization.evaluate(tenant, new ClientAuthorization.Subject(r.clientId(), r.kind(), r.owner(),
                    r.serviceIdentity(), r.scopes(), r.orgUnitId()));
            if (eff.denial() != null) return deny(eff.denial(), 401, keyId, r.clientId(), tenant);
            for (Map.Entry<String, String> lost : eff.lostScopes().entrySet()) scopeLost(r.clientId(), lost.getKey(), lost.getValue());
            IvoroviPrincipal principal = new IvoroviPrincipal(r.clientId(), tenant, "api-client:" + r.clientId(), null, "API client: " + r.name(), eff.grants(),
                    List.of(ROLE), null);
            return (Result) new Authenticated(principal, r.clientId());
        }));
    }

    /** One audit record per client, scope and day when a registered scope no longer works (and why). */
    private void scopeLost(UUID clientId, String scope, String reason) {
        int inserted = jdbc.sql("""
                INSERT INTO api_client_scope_loss (tenant_id, client_id, scope, day, reason) VALUES (current_tenant_id(), ?, ?, current_date, ?)
                ON CONFLICT DO NOTHING""").params(clientId, scope, reason).update();
        if (inserted > 0) {
            audit.record("api_client.scope_lost", "api_client", clientId.toString(), AuditOutcome.DENIED, Map.of("scope", scope, "reason", reason));
        }
    }

    private Rejected deny(String reason, int status, String keyId, UUID clientId, UUID tenant) {
        log.warn("security.api_key_rejected reason={} keyId={}", reason, keyId);
        recordUsage(tenant, clientId, true);
        return new Rejected(reason, status);
    }

    @Override
    public void recordUsage(UUID tenantId, UUID clientId, boolean denied) {
        usage.count(tenantId, clientId, denied);
    }

    private static Instant ts(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
