package com.ivorovi.platform.process;

import com.ivorovi.platform.tenancy.TenantContext;
import jakarta.annotation.PreDestroy;
import java.net.InetAddress;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every process writes a heartbeat row (platform table, no personal data). The outage detector treats a delivery role
 * whose heartbeats stopped as a platform incident. A graceful shutdown removes the row, so a planned scale-down or
 * deployment is not mistaken for an outage; a crash, freeze or network partition leaves it behind.
 *
 * <p>Since follow-up B (KI-71) each beat also carries this instance's health — version, requests in flight, the last
 * minute's request count, server errors and p95 latency, and the status of each {@link DependencyCheck} — and is kept as
 * a sample (rolling history, pruned by the detector), so partial failures of one instance or one dependency are visible.
 */
@Component
public class ProcessHeartbeat {

    public record Beat(String instanceId, ProcessRole role, Instant startedAt, Instant lastBeatAt) {}

    /** What one heartbeat reports. */
    public record Health(String version, RequestStats.Snapshot requests, Map<String, DependencyCheck.Status> checks) {}

    private static final Logger log = LoggerFactory.getLogger(ProcessHeartbeat.class);
    private final JdbcClient jdbc;
    private final ProcessProperties props;
    private final RequestStats stats;
    private final List<DependencyCheck> checks;
    private final JsonMapper json;
    private final String version;
    private final String instanceId;

    ProcessHeartbeat(JdbcClient jdbc, ProcessProperties props, RequestStats stats, List<DependencyCheck> checks, JsonMapper json,
                     @Value("${IVOROVI_VERSION:dev}") String version) {
        this.jdbc = jdbc;
        this.props = props;
        this.stats = stats;
        this.checks = checks;
        this.json = json;
        this.version = version.length() > 60 ? version.substring(0, 60) : version;
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown";
        }
        this.instanceId = (host.length() > 60 ? host.substring(0, 60) : host) + ":" + UUID.randomUUID().toString().substring(0, 8);
    }

    public String instanceId() {
        return instanceId;
    }

    public ProcessRole role() {
        return props.role();
    }

    @Scheduled(fixedDelayString = "${ivorovi.process.heartbeat-interval:PT10S}", initialDelayString = "PT1S")
    void scheduled() {
        if (!props.heartbeatEnabled()) return;
        try {
            beat();
        } catch (RuntimeException e) {
            log.warn("Heartbeat failed: {}", e.getClass().getSimpleName());
        }
    }

    /** Measures this instance's health now. */
    public Health health() {
        Map<String, DependencyCheck.Status> results = new TreeMap<>();
        for (DependencyCheck c : checks) {
            if (!c.appliesTo(props.role())) continue;
            DependencyCheck.Status s;
            try {
                s = c.check();
            } catch (RuntimeException e) {
                s = DependencyCheck.Status.FAILED;
            }
            results.put(c.name(), s);
        }
        return new Health(version, stats.snapshot(), results);
    }

    public void beat() {
        beat(health());
    }

    /** Writes a heartbeat with the given health (the scheduled beat measures it; tests may supply it). */
    public void beat(Health h) {
        String c = json.writeValueAsString(h.checks());
        RequestStats.Snapshot r = h.requests();
        TenantContext.callAsPlatform(() -> {
            jdbc.sql("""
                    INSERT INTO process_heartbeat (instance_id, role, version, in_flight, requests_1m, errors_1m, p95_ms, checks)
                    VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                    ON CONFLICT (instance_id) DO UPDATE SET last_beat_at = now(), role = EXCLUDED.role, version = EXCLUDED.version,
                        in_flight = EXCLUDED.in_flight, requests_1m = EXCLUDED.requests_1m, errors_1m = EXCLUDED.errors_1m, p95_ms = EXCLUDED.p95_ms,
                        checks = EXCLUDED.checks""")
                    .params(instanceId, props.role().name(), h.version(), r.inFlight(), r.requests(), r.errors(), r.p95Ms(), c).update();
            return jdbc.sql("""
                    INSERT INTO process_heartbeat_sample (instance_id, role, version, in_flight, requests_1m, errors_1m, p95_ms, checks)
                    VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb)) ON CONFLICT DO NOTHING""")
                    .params(instanceId, props.role().name(), h.version(), r.inFlight(), r.requests(), r.errors(), r.p95Ms(), c).update();
        });
    }

    public List<Beat> all() {
        return TenantContext.callAsPlatform(() -> jdbc.sql("SELECT instance_id, role, started_at, last_beat_at FROM process_heartbeat ORDER BY role, instance_id")
                .query((rs, n) -> new Beat(rs.getString(1), ProcessRole.valueOf(rs.getString(2)), rs.getTimestamp(3).toInstant(), rs.getTimestamp(4).toInstant()))
                .list());
    }

    @PreDestroy
    void deregister() {
        if (!props.heartbeatEnabled()) return;
        try {
            TenantContext.callAsPlatform(() -> jdbc.sql("DELETE FROM process_heartbeat WHERE instance_id = ?").param(instanceId).update());
        } catch (RuntimeException e) {
            log.debug("Heartbeat deregistration failed: {}", e.toString());
        }
    }
}
