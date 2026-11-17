package com.ivorovi.proctoring.internal;

import com.ivorovi.platform.jobs.JobLeases;
import com.ivorovi.platform.tenancy.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Deletes proctoring snapshots at the end of their retention period (one instance at a time, organization by organization). */
@Component
class EvidenceRetention {

    private static final Logger log = LoggerFactory.getLogger(EvidenceRetention.class);
    private final JdbcClient jdbc;
    private final JobLeases leases;
    private final ProctoringService proctoring;

    EvidenceRetention(JdbcClient jdbc, JobLeases leases, ProctoringService proctoring) {
        this.jdbc = jdbc;
        this.leases = leases;
        this.proctoring = proctoring;
    }

    @Scheduled(fixedDelayString = "${ivorovi.proctoring.retention-interval:PT1H}", initialDelayString = "PT2M")
    void scheduled() {
        if (!leases.tryAcquire("proctoring.evidence-retention", Duration.ofMinutes(50))) return;
        List<UUID> tenants = TenantContext.callAsPlatform(() -> jdbc.sql("SELECT id FROM tenant").query(UUID.class).list());
        for (UUID t : tenants) {
            try {
                int n = TenantContext.callInTenant(t, () -> proctoring.deleteExpired(Instant.now()));
                if (n > 0) log.info("Proctoring evidence retention: {} snapshot(s) deleted", n);
            } catch (RuntimeException e) {
                log.warn("Proctoring evidence retention failed for an organization: {}", e.getClass().getSimpleName());
            }
        }
    }
}
