package com.ivorovi.exams.internal;

import com.ivorovi.exams.ExamsProperties;
import com.ivorovi.platform.jobs.JobLeases;
import com.ivorovi.platform.process.ProcessProperties;
import com.ivorovi.platform.process.ProcessRole;
import com.ivorovi.platform.tenancy.TenantContext;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Detects exam-delivery outages from process heartbeats (C-3). Runs in the administrative process (it must keep working
 * while delivery is down), on exactly one instance (database lease). A delivery role counts as down when none of its
 * recently seen instances has beaten within {@code outageThreshold}; rows removed by a graceful shutdown never count.
 * <ol>
 *   <li>down and no open incident → open a platform incident; organizations with open sessions get an impact and a notice;</li>
 *   <li>up again with an open incident → resolve it; every organization with affected candidates gets the impact window
 *       and the candidate list, ready for a compensation proposal.</li>
 * </ol>
 */
@Component
public class OutageDetector {

    public record Result(String change, UUID platformIncidentId, int organizations) {}

    private static final Logger log = LoggerFactory.getLogger(OutageDetector.class);
    private static final Duration RECENT = Duration.ofHours(6);
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final JobLeases leases;
    private final IncidentService incidents;
    private final ExamsProperties props;
    private final ProcessProperties process;

    OutageDetector(JdbcClient jdbc, TransactionTemplate tx, JobLeases leases, IncidentService incidents, ExamsProperties props, ProcessProperties process) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.leases = leases;
        this.incidents = incidents;
        this.props = props;
        this.process = process;
    }

    @Scheduled(fixedDelayString = "${ivorovi.exams.outage-check-interval:PT15S}", initialDelayString = "PT30S")
    void scheduled() {
        if (!props.outageDetectorEnabled() || process.role() != ProcessRole.ADMIN) return;
        if (!leases.tryAcquire("exams.outage-detector", props.outageCheckInterval().minusSeconds(1))) return;
        try {
            check(Instant.now());
        } catch (RuntimeException e) {
            log.warn("Outage check failed: {}", e.toString());
        }
    }

    public Result check(Instant now) {
        record Beats(int recent, int alive, Instant last) {}
        Beats b = TenantContext.callAsPlatform(() -> jdbc.sql("""
                SELECT count(*), count(*) FILTER (WHERE last_beat_at > CAST(? AS timestamptz)), max(last_beat_at)
                FROM process_heartbeat WHERE role = 'DELIVERY' AND last_beat_at > CAST(? AS timestamptz)""")
                .params(Timestamp.from(now.minus(props.outageThreshold())), Timestamp.from(now.minus(RECENT)))
                .query((rs, n) -> new Beats(rs.getInt(1), rs.getInt(2), rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant())).single());
        record Open(UUID id, Instant started) {}
        Optional<Open> open = TenantContext.callAsPlatform(() -> jdbc.sql(
                "SELECT id, started_at FROM platform_incident WHERE kind = 'DELIVERY_OUTAGE' AND status = 'OPEN' ORDER BY started_at LIMIT 1")
                .query((rs, n) -> new Open(rs.getObject(1, UUID.class), rs.getTimestamp(2).toInstant())).optional());

        if (b.recent() > 0 && b.alive() == 0 && open.isEmpty()) {
            UUID id = com.ivorovi.platform.ids.Ids.newId();
            Instant started = b.last();
            TenantContext.callAsPlatform(() -> tx.execute(s -> jdbc.sql("""
                    INSERT INTO platform_incident (id, kind, started_at, summary) VALUES (?, 'DELIVERY_OUTAGE', ?, ?)""")
                    .params(id, Timestamp.from(started), "No exam delivery process has responded since " + started).update()));
            log.error("platform.incident.opened kind=DELIVERY_OUTAGE id={} since={}", id, started);
            int orgs = 0;
            for (UUID tenant : tenants()) {
                try {
                    if (TenantContext.callInTenant(tenant, () -> incidents.platformOutageStarted(id, started)) != null) orgs++;
                } catch (RuntimeException e) {
                    log.warn("Incident start for an organization failed: {}", e.getClass().getSimpleName());
                }
            }
            return new Result("OPENED", id, orgs);
        }
        if (b.alive() > 0 && open.isPresent()) {
            Instant ended = now;
            TenantContext.callAsPlatform(() -> tx.execute(s -> jdbc.sql(
                    "UPDATE platform_incident SET status = 'RESOLVED', ended_at = ? WHERE id = ?").params(Timestamp.from(ended), open.get().id()).update()));
            log.warn("platform.incident.resolved kind=DELIVERY_OUTAGE id={} duration={}s", open.get().id(),
                    Duration.between(open.get().started(), ended).toSeconds());
            int orgs = 0;
            for (UUID tenant : tenants()) {
                try {
                    if (TenantContext.callInTenant(tenant, () -> incidents.platformOutageResolved(open.get().id(), open.get().started(), ended)) != null) orgs++;
                } catch (RuntimeException e) {
                    log.warn("Incident impact for an organization failed: {}", e.getClass().getSimpleName());
                }
            }
            return new Result("RESOLVED", open.get().id(), orgs);
        }
        // housekeeping: forget instances not seen for a day
        TenantContext.callAsPlatform(() -> jdbc.sql("DELETE FROM process_heartbeat WHERE last_beat_at < CAST(? AS timestamptz)")
                .param(Timestamp.from(now.minus(Duration.ofDays(1)))).update());
        return new Result(open.isPresent() ? "ONGOING" : "NONE", open.map(Open::id).orElse(null), 0);
    }

    private List<UUID> tenants() {
        return TenantContext.callAsPlatform(() -> jdbc.sql("SELECT id FROM tenant WHERE status IN ('ACTIVE', 'TRIAL')").query(UUID.class).list());
    }
}
