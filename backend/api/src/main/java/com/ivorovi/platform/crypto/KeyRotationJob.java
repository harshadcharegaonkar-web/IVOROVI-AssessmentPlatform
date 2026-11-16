package com.ivorovi.platform.crypto;

import com.ivorovi.platform.audit.AuditLog;
import com.ivorovi.platform.jobs.JobLeases;
import com.ivorovi.platform.tenancy.TenantContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Re-encrypts every stored {@link EncryptedColumns} value that is not on the current key (RB-3 / KI-49).
 * <ul>
 *   <li><b>Leased:</b> one instance at a time ({@link JobLeases}, job {@value #JOB}).</li>
 *   <li><b>Per organization under RLS:</b> data is read and written in the organization's own context; progress is
 *       kept in {@code key_rotation_progress} (platform context only).</li>
 *   <li><b>Resumable and idempotent:</b> rows are processed in id order from the stored resume point, and only values
 *       not on the target key are selected, so a restart (or a crash between a batch and its progress update) repeats
 *       no work and skips nothing.</li>
 *   <li><b>Concurrent writers win:</b> each value is replaced with compare-and-set on the old ciphertext; when a writer
 *       changed it first, the job leaves the writer's value and counts a conflict.</li>
 *   <li><b>Verified:</b> once a field is complete, a later run re-counts values not on the target key (for example
 *       written by an instance still on the old configuration) and reopens the field if any remain.</li>
 *   <li><b>No secrets out:</b> logs, audit records and errors carry field names, key ids and counts only.</li>
 * </ul>
 * Columns marked {@code guarded} (immutable records) are not rewritten here; they are reported as pending.
 */
@Component
public class KeyRotationJob {

    static final String JOB = "platform.key-rotation";
    private static final Logger log = LoggerFactory.getLogger(KeyRotationJob.class);

    /** Outcome of one run, for the scheduler log line and tests. */
    public record RunResult(String targetKeyId, int batches, long rewritten, long conflicts, int fieldsCompleted, boolean finished,
                            List<String> pendingGuardedFields) {}

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final FieldCipher cipher;
    private final JobLeases leases;
    private final AuditLog audit;
    private final boolean enabled;
    private final int batchSize;
    private final int batchesPerRun;
    private final List<EncryptedColumns.Column> columns;
    /** Test hook: runs after a batch is read and before it is written (simulates a concurrent writer). */
    volatile Runnable beforeWrite = () -> {};
    /** Test hook: limits a run to some organizations (tests share one database); production processes all. */
    volatile java.util.function.Predicate<UUID> tenantFilter = t -> true;

    @Autowired
    KeyRotationJob(JdbcClient jdbc, TransactionTemplate tx, FieldCipher cipher, JobLeases leases, AuditLog audit,
                   @Value("${ivorovi.security.key-rotation.enabled:false}") boolean enabled,
                   @Value("${ivorovi.security.key-rotation.batch-size:500}") int batchSize,
                   @Value("${ivorovi.security.key-rotation.batches-per-run:20}") int batchesPerRun) {
        this(jdbc, tx, cipher, leases, audit, enabled, batchSize, batchesPerRun, EncryptedColumns.ALL);
    }

    KeyRotationJob(JdbcClient jdbc, TransactionTemplate tx, FieldCipher cipher, JobLeases leases, AuditLog audit, boolean enabled,
                   int batchSize, int batchesPerRun, List<EncryptedColumns.Column> columns) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.cipher = cipher;
        this.leases = leases;
        this.audit = audit;
        this.enabled = enabled;
        this.batchSize = Math.max(1, Math.min(10_000, batchSize));
        this.batchesPerRun = Math.max(1, batchesPerRun);
        this.columns = List.copyOf(columns);
    }

    @Scheduled(fixedDelayString = "${ivorovi.security.key-rotation.interval:PT30S}", initialDelayString = "PT45S")
    void scheduled() {
        if (!enabled) return;
        if (!leases.tryAcquire(JOB, Duration.ofMinutes(5))) return;
        try {
            RunResult r = runOnce();
            if (r.batches() > 0 || !r.finished()) {
                log.info("Key rotation to key id {}: {} batches, {} values re-encrypted, {} left to concurrent writers, {} fields completed, finished={}, pending guarded fields={}",
                        r.targetKeyId(), r.batches(), r.rewritten(), r.conflicts(), r.fieldsCompleted(), r.finished(), r.pendingGuardedFields());
            }
        } catch (RuntimeException e) {
            log.warn("Key rotation run failed: {}", e.getClass().getSimpleName());   // never the message: it may name values in driver errors
        }
    }

    private record Work(UUID tenant, EncryptedColumns.Column column, UUID lastId, boolean completed) {}

    private record Row(UUID id, String stored) {}

    /** One bounded run: up to {@code batchesPerRun} batches over all organizations and fields, then returns. */
    public RunResult runOnce() {
        String target = cipher.currentKeyId();
        List<UUID> tenants = TenantContext.callAsPlatform(() -> jdbc.sql("SELECT id FROM tenant ORDER BY id").query(UUID.class).list())
                .stream().filter(tenantFilter).toList();
        int batches = 0;
        long rewritten = 0;
        long conflicts = 0;
        int completed = 0;
        boolean finished = true;
        List<String> guarded = new ArrayList<>();
        for (EncryptedColumns.Column c : columns) {
            if (c.guarded()) {
                for (UUID t : tenants) {
                    if (countStale(t, c, target) > 0) {
                        guarded.add(c.field());
                        finished = false;
                        break;
                    }
                }
                continue;
            }
            for (UUID t : tenants) {
                Work w = progress(target, t, c);
                if (w.completed()) {
                    if (countStale(t, c, target) == 0) continue;
                    reopen(target, t, c);                                 // values not on the key appeared after completion
                    w = new Work(t, c, null, false);
                }
                UUID last = w.lastId();
                while (true) {
                    if (batches >= batchesPerRun) return new RunResult(target, batches, rewritten, conflicts, completed, false, guarded);
                    long[] counts = new long[2];
                    UUID next = batch(t, c, target, last, counts);
                    batches++;
                    rewritten += counts[0];
                    conflicts += counts[1];
                    if (next == null) {
                        complete(target, t, c, counts);
                        completed++;
                        break;
                    }
                    saveProgress(target, t, c, next, counts);
                    last = next;
                }
            }
        }
        return new RunResult(target, batches, rewritten, conflicts, completed, finished, guarded);
    }

    /** Re-encrypts one batch after {@code last}; returns the last id processed, or null when nothing was left. */
    private UUID batch(UUID tenant, EncryptedColumns.Column c, String target, UUID last, long[] counts) {
        String select = "SELECT " + c.idColumn() + ", " + c.column() + " FROM " + c.table() + " WHERE " + c.column() + " IS NOT NULL AND "
                + EncryptedColumns.notOnKey(c, target) + (last == null ? "" : " AND " + c.idColumn() + " > ?") + " ORDER BY " + c.idColumn() + " LIMIT " + batchSize;
        List<Row> rows = TenantContext.callInTenant(tenant, () -> tx.execute(s -> {
            var q = jdbc.sql(select);
            if (last != null) q = q.param(last);
            return q.query((rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getString(2))).list();
        }));
        if (rows == null || rows.isEmpty()) return null;
        // re-encrypt outside the write transaction; decryption failures stop the run (fail closed, no value in the error)
        List<String[]> updates = new ArrayList<>(rows.size());
        for (Row r : rows) updates.add(new String[] {r.stored(), cipher.reencrypt(r.stored(), c.field(), tenant)});
        beforeWrite.run();
        String update = "UPDATE " + c.table() + " SET " + c.column() + " = ? WHERE " + c.idColumn() + " = ? AND " + c.column() + " = ?";
        TenantContext.runInTenant(tenant, () -> tx.executeWithoutResult(s -> {
            for (int i = 0; i < rows.size(); i++) {
                int n = jdbc.sql(update).params(updates.get(i)[1], rows.get(i).id(), updates.get(i)[0]).update();
                if (n == 1) counts[0]++; else counts[1]++;
            }
        }));
        return rows.get(rows.size() - 1).id();
    }

    private long countStale(UUID tenant, EncryptedColumns.Column c, String target) {
        Long n = TenantContext.callInTenant(tenant, () -> tx.execute(s -> jdbc.sql("SELECT count(*) FROM " + c.table() + " WHERE " + c.column()
                + " IS NOT NULL AND " + EncryptedColumns.notOnKey(c, target)).query(Long.class).single()));
        return n == null ? 0 : n;
    }

    private Work progress(String target, UUID tenant, EncryptedColumns.Column c) {
        return TenantContext.callAsPlatform(() -> tx.execute(s -> {
            jdbc.sql("INSERT INTO key_rotation_progress (target_key_id, tenant_id, field) VALUES (?, ?, ?) ON CONFLICT DO NOTHING")
                    .params(target, tenant, c.field()).update();
            return jdbc.sql("SELECT last_id, completed_at IS NOT NULL FROM key_rotation_progress WHERE target_key_id = ? AND tenant_id = ? AND field = ?")
                    .params(target, tenant, c.field()).query((rs, n) -> new Work(tenant, c, rs.getObject(1, UUID.class), rs.getBoolean(2))).single();
        }));
    }

    private void saveProgress(String target, UUID tenant, EncryptedColumns.Column c, UUID last, long[] counts) {
        TenantContext.callAsPlatform(() -> tx.execute(s -> jdbc.sql("""
                UPDATE key_rotation_progress SET last_id = ?, rewritten = rewritten + ?, conflicts = conflicts + ?, updated_at = now()
                WHERE target_key_id = ? AND tenant_id = ? AND field = ?""").params(last, counts[0], counts[1], target, tenant, c.field()).update()));
    }

    private void reopen(String target, UUID tenant, EncryptedColumns.Column c) {
        TenantContext.callAsPlatform(() -> tx.execute(s -> jdbc.sql("""
                UPDATE key_rotation_progress SET last_id = NULL, completed_at = NULL, updated_at = now()
                WHERE target_key_id = ? AND tenant_id = ? AND field = ?""").params(target, tenant, c.field()).update()));
    }

    private void complete(String target, UUID tenant, EncryptedColumns.Column c, long[] counts) {
        record Totals(long rewritten, long conflicts, int passes) {}
        Totals t = TenantContext.callAsPlatform(() -> tx.execute(s -> jdbc.sql("""
                UPDATE key_rotation_progress SET last_id = NULL, rewritten = rewritten + ?, conflicts = conflicts + ?, passes = passes + 1,
                    completed_at = now(), updated_at = now()
                WHERE target_key_id = ? AND tenant_id = ? AND field = ? RETURNING rewritten, conflicts, passes""")
                .params(counts[0], counts[1], target, tenant, c.field()).query((rs, n) -> new Totals(rs.getLong(1), rs.getLong(2), rs.getInt(3))).single()));
        if (t == null) return;
        TenantContext.runInTenant(tenant, () -> tx.executeWithoutResult(s -> audit.recordSuccess("platform.key_rotation.field_completed", "encrypted_field",
                c.field(), Map.of("targetKeyId", target, "rewritten", Long.toString(t.rewritten()), "conflicts", Long.toString(t.conflicts()),
                        "pass", Integer.toString(t.passes())))));
    }
}
