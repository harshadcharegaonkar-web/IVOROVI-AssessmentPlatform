package com.ivorovi.platform.idempotency;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Persistence of idempotency records. Each call is its own (auto-commit) statement, outside business transactions. */
@Component
class IdempotencyStore {

    static final Duration RETENTION = Duration.ofHours(24);

    record Existing(String requestHash, String status, Integer responseStatus, String contentType, byte[] body) {}

    private final JdbcClient jdbc;

    IdempotencyStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns true if this call claimed the key. */
    boolean claim(UUID tenant, UUID user, String key, String method, String path, String hash) {
        return jdbc.sql("""
                INSERT INTO idempotency_record (tenant_id, user_account_id, idem_key, method, path, request_hash, status, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, 'IN_PROGRESS', now() + make_interval(secs => ?))
                ON CONFLICT DO NOTHING""")
                .params(tenant, user, key, method, path, hash, (double) RETENTION.toSeconds())
                .update() == 1;
    }

    Optional<Existing> find(UUID tenant, UUID user, String key) {
        return jdbc.sql("""
                SELECT request_hash, status, response_status, response_content_type, response_body
                FROM idempotency_record WHERE tenant_id = ? AND user_account_id = ? AND idem_key = ? AND expires_at > now()""")
                .params(tenant, user, key)
                .query((rs, n) -> new Existing(rs.getString(1), rs.getString(2), (Integer) rs.getObject(3),
                        rs.getString(4), rs.getBytes(5)))
                .optional();
    }

    void complete(UUID tenant, UUID user, String key, int status, String contentType, byte[] body) {
        jdbc.sql("""
                UPDATE idempotency_record SET status = 'COMPLETED', response_status = ?, response_content_type = ?, response_body = ?
                WHERE tenant_id = ? AND user_account_id = ? AND idem_key = ?""")
                .params(status, contentType, body, tenant, user, key).update();
    }

    void release(UUID tenant, UUID user, String key) {
        jdbc.sql("DELETE FROM idempotency_record WHERE tenant_id = ? AND user_account_id = ? AND idem_key = ?")
                .params(tenant, user, key).update();
    }
}
