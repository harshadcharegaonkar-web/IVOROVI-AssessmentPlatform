package com.ivorovi.platform.jobs;

import com.ivorovi.platform.tenancy.TenantContext;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Leases for scheduled jobs (KI-61): with several application instances, a named job runs on exactly one instance per
 * lease period. The holder keeps the lease by re-acquiring it each run; another instance takes over only after the lease
 * expired (holder crashed or stopped). Acquisition is a single atomic upsert, so concurrent instances cannot both win.
 */
@Component
public class JobLeases {

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final String owner;

    @org.springframework.beans.factory.annotation.Autowired
    public JobLeases(JdbcClient jdbc, TransactionTemplate tx) {
        this(jdbc, tx, ManagementFactory.getRuntimeMXBean().getName() + "/" + UUID.randomUUID().toString().substring(0, 8));
    }

    /** For tests: simulate several instances with distinct owners. */
    public JobLeases(JdbcClient jdbc, TransactionTemplate tx, String owner) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.owner = owner;
    }

    public String owner() {
        return owner;
    }

    /** True when this instance holds the lease for {@code job} for the next {@code period}. */
    public boolean tryAcquire(String job, Duration period) {
        Integer n = TenantContext.callAsPlatform(() -> tx.execute(s -> jdbc.sql("""
                INSERT INTO job_lease (name, owner, lease_until, runs) VALUES (?, ?, now() + make_interval(secs => ?), 1)
                ON CONFLICT (name) DO UPDATE SET owner = EXCLUDED.owner, lease_until = EXCLUDED.lease_until, acquired_at = now(), runs = job_lease.runs + 1
                WHERE job_lease.lease_until <= now() OR job_lease.owner = EXCLUDED.owner""")
                .params(job, owner, (double) Math.max(1, period.toSeconds())).update()));
        return n != null && n > 0;
    }

    /** Gives the lease up (e.g. on shutdown) so another instance can run the job at once. */
    public void release(String job) {
        TenantContext.callAsPlatform(() -> tx.execute(s -> jdbc.sql("UPDATE job_lease SET lease_until = now() WHERE name = ? AND owner = ?")
                .params(job, owner).update()));
    }
}
