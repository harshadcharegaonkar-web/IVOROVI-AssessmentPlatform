package com.ivorovi.students.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Limits and background-processing settings of the student import ({@code ivorovi.imports.*}).
 *
 * @param maxFileBytes  largest accepted upload
 * @param maxRows       most data rows per file
 * @param retentionDays days after which source files and raw row values are purged (outcomes and counts stay)
 * @param batchSize     rows per progress update / lease heartbeat
 * @param lease         how long a worker owns a running job without a heartbeat
 * @param maxAttempts   worker attempts after crashes before a job is marked failed
 * @param workerEnabled run queued jobs on this instance (listener and sweeper)
 * @param concurrency   jobs processed at the same time on this instance
 */
@ConfigurationProperties("ivorovi.imports")
public record StudentImportProperties(long maxFileBytes, int maxRows, int retentionDays, int batchSize, Duration lease, int maxAttempts, boolean workerEnabled,
                                      int concurrency) {

    public StudentImportProperties {
        if (maxFileBytes <= 0) maxFileBytes = 10L * 1024 * 1024;
        if (maxRows <= 0) maxRows = 10_000;
        if (retentionDays <= 0) retentionDays = 30;
        if (batchSize <= 0) batchSize = 100;
        if (lease == null) lease = Duration.ofMinutes(2);
        if (maxAttempts <= 0) maxAttempts = 3;
        if (concurrency <= 0) concurrency = 2;
    }
}
