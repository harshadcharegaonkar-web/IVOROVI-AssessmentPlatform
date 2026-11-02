package com.ivorovi.platform.audit;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Read side of the audit trail (always RLS-restricted to the current tenant). */
@Component
public class AuditTrail {

    @io.swagger.v3.oas.annotations.media.Schema(name = "AuditEvent")
    public record Entry(UUID id, long seq, Instant occurredAt, UUID actorId, String actorSubject, String actorName, String action,
                        String resourceType, String resourceId, AuditOutcome outcome, Map<String, String> details,
                        String clientIp, String traceId) {}

    public record Query(String action, String resourceType, AuditOutcome outcome, Instant from, Instant to) {}

    public record Page(List<Entry> items, long total) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "AuditVerification")
    public record Verification(boolean valid, long eventsChecked, Long firstInvalidSeq) {}

    private static final TypeReference<Map<String, String>> DETAILS = new TypeReference<>() {};
    private final JdbcClient jdbc;
    private final JsonMapper json;

    AuditTrail(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public Page search(Query q, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (q.action() != null) { where.append(" AND e.action = ?"); params.add(q.action()); }
        if (q.resourceType() != null) { where.append(" AND e.resource_type = ?"); params.add(q.resourceType()); }
        if (q.outcome() != null) { where.append(" AND e.outcome = ?"); params.add(q.outcome().name()); }
        if (q.from() != null) { where.append(" AND e.occurred_at >= ?"); params.add(Timestamp.from(q.from())); }
        if (q.to() != null) { where.append(" AND e.occurred_at < ?"); params.add(Timestamp.from(q.to())); }

        long total = jdbc.sql("SELECT count(*) FROM audit_event e" + where).params(params).query(Long.class).single();
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(size);
        pageParams.add((long) page * size);
        // Actor display name via the tenant's own user accounts (RLS applies to both tables).
        List<Entry> items = jdbc.sql("""
                SELECT e.id, e.seq, e.occurred_at, e.actor_id, e.actor_subject, ua.display_name AS actor_name, e.action,
                       e.resource_type, e.resource_id, e.outcome, e.details::text AS details, e.client_ip, e.trace_id
                FROM audit_event e
                LEFT JOIN user_account ua ON ua.tenant_id = e.tenant_id AND ua.id = e.actor_id""" + where
                        + " ORDER BY e.seq DESC LIMIT ? OFFSET ?")
                .params(pageParams)
                .query((rs, n) -> new Entry(rs.getObject("id", UUID.class), rs.getLong("seq"),
                        rs.getTimestamp("occurred_at").toInstant(), rs.getObject("actor_id", UUID.class),
                        rs.getString("actor_subject"), rs.getString("actor_name"), rs.getString("action"),
                        rs.getString("resource_type"), rs.getString("resource_id"),
                        AuditOutcome.valueOf(rs.getString("outcome")), json.readValue(rs.getString("details"), DETAILS),
                        rs.getString("client_ip"), rs.getString("trace_id")))
                .list();
        return new Page(items, total);
    }

    /** Recomputes the hash chain for the current tenant. O(n); to be moved to a background job at scale. */
    @Transactional(readOnly = true)
    public Verification verify() {
        long[] checked = {0};
        Long[] firstInvalid = {null};
        String[] expectedPrev = {AuditHasher.GENESIS};
        long[] expectedSeq = {1};
        jdbc.sql("""
                SELECT tenant_id, seq, occurred_at, actor_id, actor_subject, action, resource_type, resource_id, outcome,
                       details::text AS details, prev_hash, hash
                FROM audit_event ORDER BY seq""")
                .query(rs -> {
                    if (firstInvalid[0] != null) return;
                    long seq = rs.getLong("seq");
                    String recomputed = AuditHasher.hash(rs.getString("prev_hash"), rs.getObject("tenant_id", UUID.class),
                            seq, rs.getTimestamp("occurred_at").toInstant(), rs.getObject("actor_id", UUID.class),
                            rs.getString("actor_subject"), rs.getString("action"), rs.getString("resource_type"),
                            rs.getString("resource_id"), AuditOutcome.valueOf(rs.getString("outcome")),
                            json.readValue(rs.getString("details"), DETAILS));
                    boolean ok = seq == expectedSeq[0] && rs.getString("prev_hash").equals(expectedPrev[0])
                            && recomputed.equals(rs.getString("hash"));
                    checked[0]++;
                    if (!ok) firstInvalid[0] = seq;
                    expectedPrev[0] = rs.getString("hash");
                    expectedSeq[0] = seq + 1;
                });
        return new Verification(firstInvalid[0] == null, checked[0], firstInvalid[0]);
    }
}
