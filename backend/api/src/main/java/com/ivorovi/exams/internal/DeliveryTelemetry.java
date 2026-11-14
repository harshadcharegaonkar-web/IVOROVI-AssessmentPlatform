package com.ivorovi.exams.internal;

import com.ivorovi.platform.process.ProcessHeartbeat;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Candidate-level delivery evidence for incident impact (follow-up B): every presence beat and save records the serving
 * instance, and a silence longer than {@link #GAP} becomes a gap row with the instance before and after it — the
 * verified lost time of that candidate. Runs inside the caller's transaction (tenant context bound).
 */
@Component
class DeliveryTelemetry {

    /** Presence beats every 15 s; anything above two missed beats is a gap. */
    static final Duration GAP = Duration.ofSeconds(30);

    record Beat(long beats, long saves) {}

    private final JdbcClient jdbc;
    private final ProcessHeartbeat process;

    DeliveryTelemetry(JdbcClient jdbc, ProcessHeartbeat process) {
        this.jdbc = jdbc;
        this.process = process;
    }

    String instanceId() {
        return process.instanceId();
    }

    /** Presence ({@code save=false}) or an acknowledged practice save with its sequence. */
    Beat beat(UUID tenant, UUID candidate, UUID session, boolean save, long seq) {
        record Row(long beats, long saves, Timestamp prevSeen, String prevInstance, Timestamp now) {}
        Row r = jdbc.sql("""
                INSERT INTO delivery_presence (tenant_id, candidate_id, session_id, saves, last_save_seq, instance_id)
                VALUES (:tenant, :candidate, :session, :saves, :seq, :instance)
                ON CONFLICT (tenant_id, candidate_id, session_id) DO UPDATE SET
                    prev_seen_at = delivery_presence.last_seen_at,
                    prev_instance_id = CASE WHEN delivery_presence.instance_id IS DISTINCT FROM EXCLUDED.instance_id
                                            THEN delivery_presence.instance_id ELSE delivery_presence.prev_instance_id END,
                    instance_changed_at = CASE WHEN delivery_presence.instance_id IS DISTINCT FROM EXCLUDED.instance_id
                                               THEN now() ELSE delivery_presence.instance_changed_at END,
                    instance_id = EXCLUDED.instance_id,
                    last_seen_at = now(),
                    beats = delivery_presence.beats + CASE WHEN :save THEN 0 ELSE 1 END,
                    saves = delivery_presence.saves + EXCLUDED.saves,
                    last_save_seq = greatest(delivery_presence.last_save_seq, EXCLUDED.last_save_seq)
                RETURNING beats, saves, prev_seen_at,
                    CASE WHEN instance_changed_at = now() THEN prev_instance_id ELSE instance_id END, now()""")
                .param("tenant", tenant).param("candidate", candidate).param("session", session).param("saves", save ? 1 : 0)
                .param("seq", save ? seq : 0).param("instance", process.instanceId()).param("save", save)
                .query((rs, n) -> new Row(rs.getLong(1), rs.getLong(2), rs.getTimestamp(3), rs.getString(4), rs.getTimestamp(5))).single();
        if (r.prevSeen() != null && r.now().getTime() - r.prevSeen().getTime() > GAP.toMillis()) {
            jdbc.sql("""
                    INSERT INTO delivery_presence_gap (tenant_id, candidate_id, session_id, gap_start, gap_end, instance_before, instance_after)
                    VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING""")
                    .params(tenant, candidate, session, r.prevSeen(), r.now(), r.prevInstance(), process.instanceId()).update();
        }
        return new Beat(r.beats(), r.saves());
    }
}
