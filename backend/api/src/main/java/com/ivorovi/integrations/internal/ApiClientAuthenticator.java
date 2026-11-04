package com.ivorovi.integrations.internal;

import com.ivorovi.platform.security.ApiKeyAuthenticator;
import com.ivorovi.platform.security.Grant;
import com.ivorovi.platform.security.IvoroviPrincipal;
import com.ivorovi.platform.security.ScopeType;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.platform.tenancy.TenantDirectory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
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
    static final String ROLE = "API_CLIENT";

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final TenantDirectory tenants;

    ApiClientAuthenticator(JdbcClient jdbc, TransactionTemplate tx, TenantDirectory tenants) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.tenants = tenants;
    }

    private record Row(UUID secretRowId, String hash, Instant secretExpires, Instant secretRevoked, UUID clientId, String name, String status,
                       Instant clientExpires, List<String> ips, List<String> scopes, UUID orgUnitId, Instant lastUsed) {}

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
                    SELECT s.id, s.secret_hash, s.expires_at, s.revoked_at, c.id, c.name, c.status, c.expires_at, c.allowed_ips, c.scopes, c.org_unit_id, s.last_used_at
                    FROM api_client_secret s JOIN api_client c ON c.id = s.client_id WHERE s.key_id = ?""").param(keyId)
                    .query((rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getString(2), ts(rs.getTimestamp(3)), ts(rs.getTimestamp(4)), rs.getObject(5, UUID.class),
                            rs.getString(6), rs.getString(7), ts(rs.getTimestamp(8)), ApiClientService.strings(rs.getArray(9)), ApiClientService.strings(rs.getArray(10)),
                            rs.getObject(11, UUID.class), ts(rs.getTimestamp(12)))).optional();
            if (found.isEmpty()) return new Rejected("invalid_api_key", 401);
            Row r = found.get();
            byte[] expected = r.hash().getBytes(StandardCharsets.US_ASCII);
            byte[] actual = ApiClientService.sha256(secret).getBytes(StandardCharsets.US_ASCII);
            if (!MessageDigest.isEqual(expected, actual)) {
                log.warn("security.api_key_rejected reason=wrong_secret keyId={}", keyId);
                return new Rejected("invalid_api_key", 401);
            }
            Instant now = Instant.now();
            if (!"ACTIVE".equals(r.status()) || r.secretRevoked() != null) return deny("api_key_revoked", 401, keyId, r.clientId(), tenant);
            if ((r.secretExpires() != null && r.secretExpires().isBefore(now)) || (r.clientExpires() != null && r.clientExpires().isBefore(now))) {
                return deny("api_key_expired", 401, keyId, r.clientId(), tenant);
            }
            if (!IpRules.allowed(r.ips(), clientIp)) return deny("api_key_ip_not_allowed", 403, keyId, r.clientId(), tenant);
            if (r.lastUsed() == null || r.lastUsed().isBefore(now.minusSeconds(60))) {
                // coarse last-use tracking: at most one write per key per minute
                jdbc.sql("UPDATE api_client_secret SET last_used_at = now() WHERE id = ?").param(r.secretRowId()).update();
                jdbc.sql("UPDATE api_client SET last_used_at = now() WHERE id = ?").param(r.clientId()).update();
            }
            ScopeType type = r.orgUnitId() == null ? ScopeType.TENANT : ScopeType.ORG_UNIT;
            UUID scopeId = r.orgUnitId() == null ? tenant : r.orgUnitId();
            List<Grant> grants = r.scopes().stream().map(p -> new Grant(p, ROLE, type, scopeId)).toList();
            IvoroviPrincipal principal = new IvoroviPrincipal(r.clientId(), tenant, "api-client:" + r.clientId(), null, "API client: " + r.name(), grants,
                    List.of(ROLE), null);
            return (Result) new Authenticated(principal, r.clientId());
        }));
    }

    private Rejected deny(String reason, int status, String keyId, UUID clientId, UUID tenant) {
        log.warn("security.api_key_rejected reason={} keyId={}", reason, keyId);
        recordUsage(tenant, clientId, true);
        return new Rejected(reason, status);
    }

    @Override
    public void recordUsage(UUID tenantId, UUID clientId, boolean denied) {
        try {
            TenantContext.runInTenant(tenantId, () -> tx.executeWithoutResult(s -> jdbc.sql("""
                    INSERT INTO api_client_usage (tenant_id, client_id, day, requests, denied) VALUES (?, ?, current_date, 1, ?)
                    ON CONFLICT (tenant_id, client_id, day) DO UPDATE SET requests = api_client_usage.requests + 1, denied = api_client_usage.denied + EXCLUDED.denied""")
                    .params(tenantId, clientId, denied ? 1 : 0).update()));
        } catch (RuntimeException e) {
            log.warn("API client usage not recorded: {}", e.getClass().getSimpleName());
        }
    }

    private static Instant ts(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
