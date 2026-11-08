package com.ivorovi.platform.process;

import com.ivorovi.platform.tenancy.TenantContext;
import jakarta.annotation.PreDestroy;
import java.net.InetAddress;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every process writes a heartbeat row (platform table, no personal data). The outage detector treats a delivery role
 * whose heartbeats stopped as a platform incident. A graceful shutdown removes the row, so a planned scale-down or
 * deployment is not mistaken for an outage; a crash, freeze or network partition leaves it behind.
 */
@Component
public class ProcessHeartbeat {

    public record Beat(String instanceId, ProcessRole role, Instant startedAt, Instant lastBeatAt) {}

    private static final Logger log = LoggerFactory.getLogger(ProcessHeartbeat.class);
    private final JdbcClient jdbc;
    private final ProcessProperties props;
    private final String instanceId;

    ProcessHeartbeat(JdbcClient jdbc, ProcessProperties props) {
        this.jdbc = jdbc;
        this.props = props;
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

    public void beat() {
        TenantContext.callAsPlatform(() -> jdbc.sql("""
                INSERT INTO process_heartbeat (instance_id, role) VALUES (?, ?)
                ON CONFLICT (instance_id) DO UPDATE SET last_beat_at = now(), role = EXCLUDED.role""")
                .params(instanceId, props.role().name()).update());
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
