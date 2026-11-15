package com.ivorovi.platform.crypto;

import com.ivorovi.platform.tenancy.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fail-closed key check at startup (RB-3): every key id named by a stored value in any {@link EncryptedColumns} column
 * of any organization must be configured. Otherwise the application refuses to start with an operator message naming
 * the fields, key ids and counts (never values), instead of failing later on individual reads.
 * <p>It reads only the key-id prefix of each value (no decryption), once per organization and table, after migrations.
 */
@Component
public class KeyRingStartupCheck {

    private static final Logger log = LoggerFactory.getLogger(KeyRingStartupCheck.class);

    /** A field whose stored values name a key id that is not configured (or use no known format: {@code ?}). */
    public record Missing(String field, String keyId, long values, long organizations) {}

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final FieldCipher cipher;
    private final boolean enabled;

    KeyRingStartupCheck(JdbcClient jdbc, TransactionTemplate tx, FieldCipher cipher, @Value("${ivorovi.security.key-rotation.startup-check:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.cipher = cipher;
        this.enabled = enabled;
    }

    @EventListener(ApplicationStartedEvent.class)
    void onStarted() {
        if (!enabled) {
            log.warn("Encryption key startup check is disabled (IVOROVI_KEY_STARTUP_CHECK=false)");
            return;
        }
        List<Missing> missing = findMissing(cipher);
        if (!missing.isEmpty()) throw new IllegalStateException(message(missing));
        log.info("Encryption key check passed: current key id {}, configured key ids {}", cipher.currentKeyId(), cipher.configuredKeyIds());
    }

    /** Stored key ids that {@code ring} cannot decrypt, across every organization (empty when all are configured). */
    public List<Missing> findMissing(FieldCipher ring) {
        List<UUID> tenants = TenantContext.callAsPlatform(() -> jdbc.sql("SELECT id FROM tenant").query(UUID.class).list());
        Map<String, long[]> found = new TreeMap<>();                       // "field|keyId" -> {values, organizations}
        for (UUID t : tenants) {
            for (EncryptedColumns.Column c : EncryptedColumns.ALL) {
                String expr = EncryptedColumns.keyIdExpression(c);
                List<Object[]> ids = TenantContext.callInTenant(t, () -> tx.execute(s -> jdbc.sql("SELECT " + expr + " AS k, count(*) FROM " + c.table()
                        + " WHERE " + c.column() + " IS NOT NULL GROUP BY 1").query((rs, n) -> new Object[] {rs.getString(1), rs.getLong(2)}).list()));
                for (Object[] row : ids == null ? List.<Object[]>of() : ids) {
                    String keyId = (String) row[0];
                    if (ring.configuredKeyIds().contains(keyId)) continue;
                    long[] agg = found.computeIfAbsent(c.field() + "|" + keyId, k -> new long[2]);
                    agg[0] += (Long) row[1];
                    agg[1]++;
                }
            }
        }
        List<Missing> out = new ArrayList<>();
        found.forEach((k, v) -> out.add(new Missing(k.substring(0, k.indexOf('|')), k.substring(k.indexOf('|') + 1), v[0], v[1])));
        return out;
    }

    static String message(List<Missing> missing) {
        StringBuilder b = new StringBuilder("Refusing to start: stored encrypted values use key ids that are not configured. Configure the missing keys in "
                + "IVOROVI_DATA_KEYS_PREVIOUS (id:secret) from the secret store, or restore the previous configuration (runbooks/data-encryption-key.md). Missing:");
        for (Missing m : missing) {
            b.append(' ').append(m.field()).append(" key id ").append("?".equals(m.keyId()) ? "(unknown format)" : m.keyId())
                    .append(" (").append(m.values()).append(" values in ").append(m.organizations()).append(" organizations);");
        }
        return b.toString();
    }
}
