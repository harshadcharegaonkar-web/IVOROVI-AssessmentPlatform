package com.ivorovi.platform.retention;

import com.ivorovi.platform.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Deletes published outbox events older than the retention window and expired idempotency records. */
@Component
public class RetentionJob {

    public record Result(int outboxDeleted, int idempotencyDeleted) {}

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);
    private final JdbcClient jdbc;
    private final int outboxRetentionDays;

    RetentionJob(JdbcClient jdbc, @org.springframework.beans.factory.annotation.Value("${ivorovi.retention.outbox-days:7}") int outboxRetentionDays) {
        this.jdbc = jdbc;
        this.outboxRetentionDays = outboxRetentionDays;
    }

    @Scheduled(cron = "${ivorovi.retention.cron:0 17 * * * *}")
    public void scheduled() {
        Result r = run();
        if (r.outboxDeleted() + r.idempotencyDeleted() > 0) log.info("Retention: {}", r);
    }

    public Result run() {
        return TenantContext.callAsPlatform(() -> new Result(
                jdbc.sql("DELETE FROM outbox_event WHERE published_at IS NOT NULL AND published_at < now() - make_interval(days => ?)")
                        .param(outboxRetentionDays).update(),
                jdbc.sql("DELETE FROM idempotency_record WHERE expires_at < now()").update()));
    }
}
