package com.ivorovi.students.internal;

import com.ivorovi.platform.audit.AuditLog;
import com.ivorovi.platform.audit.AuditOutcome;
import com.ivorovi.platform.ids.Ids;
import com.ivorovi.platform.outbox.Outbox;
import com.ivorovi.platform.security.IvoroviAuthentication;
import com.ivorovi.platform.security.IvoroviPrincipal;
import com.ivorovi.platform.security.PrincipalResolver;
import com.ivorovi.platform.storage.ObjectStorage;
import com.ivorovi.platform.tabular.TabularFile;
import com.ivorovi.platform.tabular.TabularFormatException;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.platform.web.ApiException;
import com.ivorovi.students.internal.StudentImportEngine.Classification;
import com.ivorovi.students.internal.StudentImportEngine.Decision;
import com.ivorovi.students.internal.StudentImportEngine.Issue;
import com.ivorovi.students.internal.StudentImportEngine.MatchCategory;
import com.ivorovi.students.internal.StudentImportEngine.Parsed;
import com.ivorovi.students.internal.StudentImportEngine.RowStatus;
import com.ivorovi.students.internal.StudentImportService.JobRow;
import com.ivorovi.students.internal.StudentImportService.MappingEntry;
import com.ivorovi.students.internal.StudentImportService.Mode;
import com.ivorovi.students.internal.StudentImportService.Phase;
import com.ivorovi.students.internal.StudentService.Status;
import jakarta.annotation.PreDestroy;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs student import jobs in the background. Jobs are announced through the transactional outbox
 * ({@code student.import.queued} on RabbitMQ); a sweeper picks up anything missed (broker unavailable, crashed worker).
 * <ul>
 *   <li><b>Exclusive:</b> a job is claimed with a lease (one worker at a time); heartbeats extend it after every batch.
 *       A crashed worker's lease expires and another instance resumes the job.</li>
 *   <li><b>Short transactions:</b> validation inserts rows in batches; processing runs <b>one transaction per row</b>
 *       that creates the student's records and records the row's outcome atomically, so a retried or resumed job never
 *       imports a row twice (the outcome update is conditional on PENDING).</li>
 *   <li><b>Concurrency:</b> each row takes transaction-scoped advisory locks on its identifiers and name keys and
 *       re-checks duplicates against the live database; unique indexes on student/registration/admission numbers remain
 *       the last line of defence.</li>
 *   <li><b>All-or-nothing:</b> students touched by the job stay deactivated (unchangeable) until the last row is
 *       imported and are then published in one statement; any failure or cancellation compensates every committed row
 *       (records created by the job are removed, matched students restored) before the job is marked failed.</li>
 *   <li><b>Identity:</b> the work runs as the user who requested the phase, with permissions resolved fresh from the
 *       database (a revoked permission stops the import).</li>
 * </ul>
 */
@Component
public class StudentImportWorker {

    static final String QUEUE = "ivorovi.student-imports";
    private static final Logger log = LoggerFactory.getLogger(StudentImportWorker.class);
    private static final TypeReference<List<MappingEntry>> MAPPING = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate tx;
    private final StudentImportService imports;
    private final StudentImportEngine engine;
    private final StudentAccess access;
    private final StudentService students;
    private final AdmissionService admissions;
    private final EnrolmentService enrolments;
    private final GuardianService guardians;
    private final PrincipalResolver principals;
    private final AuditLog audit;
    private final Outbox outbox;
    private final ObjectStorage storage;
    private final StudentImportProperties props;
    private final JsonMapper json;
    private final String owner = ManagementFactory.getRuntimeMXBean().getName() + ":" + UUID.randomUUID().toString().substring(0, 8);
    private final ExecutorService executor;
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    StudentImportWorker(JdbcClient jdbc, JdbcTemplate jdbcTemplate, TransactionTemplate tx, StudentImportService imports, StudentImportEngine engine,
                        StudentAccess access, StudentService students, AdmissionService admissions, EnrolmentService enrolments, GuardianService guardians,
                        PrincipalResolver principals, AuditLog audit, Outbox outbox, ObjectStorage storage, StudentImportProperties props, JsonMapper json) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.tx = tx;
        this.imports = imports;
        this.engine = engine;
        this.access = access;
        this.students = students;
        this.admissions = admissions;
        this.enrolments = enrolments;
        this.guardians = guardians;
        this.principals = principals;
        this.audit = audit;
        this.outbox = outbox;
        this.storage = storage;
        this.props = props;
        this.json = json;
        this.executor = Executors.newFixedThreadPool(props.concurrency(), Thread.ofPlatform().name("student-import-", 0).daemon(true).factory());
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    // ================================================================================================ triggers

    /** Message from the outbox: start the job now (the lease makes duplicate deliveries harmless). */
    @RabbitListener(queues = QUEUE, autoStartup = "${ivorovi.imports.worker-enabled:true}")
    void onQueued(Message message) {
        try {
            Object tenant = message.getMessageProperties().getHeaders().get("tenant_id");
            Map<String, Object> body = json.readValue(new String(message.getBody(), StandardCharsets.UTF_8), new TypeReference<Map<String, Object>>() {});
            if (tenant == null || body.get("jobId") == null) return;
            submit(UUID.fromString(tenant.toString()), UUID.fromString(body.get("jobId").toString()));
        } catch (RuntimeException e) {
            log.warn("Ignoring malformed import message: {}", e.getClass().getSimpleName());
        }
    }

    /** Same instance, right after the queuing transaction committed: start without waiting for the broker. */
    @org.springframework.transaction.event.TransactionalEventListener(phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT)
    void onQueuedLocally(StudentImportService.Queued queued) {
        submit(queued.tenantId(), queued.jobId());
    }

    /** Safety net: queued jobs whose message was lost and running jobs whose worker died (expired lease). */
    @Scheduled(fixedDelayString = "${ivorovi.imports.sweep-interval:PT15S}", initialDelayString = "PT20S")
    void sweep() {
        if (!props.workerEnabled()) return;
        for (UUID tenant : activeTenants()) {
            for (UUID job : claimable(tenant, 20)) submit(tenant, job);
        }
    }

    private void submit(UUID tenant, UUID jobId) {
        if (!props.workerEnabled() || !inFlight.add(jobId)) return;
        executor.submit(() -> {
            try {
                run(tenant, jobId);
            } finally {
                inFlight.remove(jobId);
            }
        });
    }

    /** Runs every claimable job of the tenant on the calling thread until none is left (tests and maintenance). */
    public int runPending(UUID tenant) {
        int n = 0;
        for (int guard = 0; guard < 100; guard++) {
            List<UUID> jobs = claimable(tenant, 10);
            if (jobs.isEmpty()) break;
            for (UUID j : jobs) if (run(tenant, j)) n++;
        }
        return n;
    }

    private List<UUID> activeTenants() {
        return TenantContext.callAsPlatform(() -> jdbc.sql("SELECT id FROM tenant WHERE status = 'ACTIVE'").query(UUID.class).list());
    }

    private List<UUID> claimable(UUID tenant, int limit) {
        return TenantContext.callInTenant(tenant, () -> tx.execute(s -> jdbc.sql("""
                SELECT id FROM student_import_job WHERE status = 'QUEUED' OR (status IN ('VALIDATING', 'PROCESSING') AND lease_until < now())
                ORDER BY updated_at LIMIT ?""").param(limit).query(UUID.class).list()));
    }

    // ================================================================================================ claim and identity

    private record Claim(UUID jobId, Phase phase, int attempts, UUID requestedBy) {}

    /** Thrown when another worker took over the job (lease expired). */
    private static final class LeaseLost extends RuntimeException {
        LeaseLost() { super(null, null, false, false); }
    }

    /** Runs one job phase if it can be claimed; returns whether work was done. */
    public boolean run(UUID tenant, UUID jobId) {
        return TenantContext.callInTenant(tenant, () -> {
            Claim claim = claim(jobId);
            if (claim == null) return false;
            SecurityContext previous = SecurityContextHolder.getContext();
            try {
                if (claim.attempts() > props.maxAttempts()) {
                    fail(jobId, "SYSTEM", "The import stopped unexpectedly " + props.maxAttempts() + " times. Retry it, or contact support if it fails again.", true);
                    return true;
                }
                Optional<IvoroviPrincipal> principal = principalOf(tenant, claim.requestedBy());
                if (principal.isEmpty()) {
                    fail(jobId, "SYSTEM", "The user who started this step no longer has an active account. Another administrator can retry it.", true);
                    return true;
                }
                SecurityContext ctx = SecurityContextHolder.createEmptyContext();
                ctx.setAuthentication(new IvoroviAuthentication(principal.get(), null));
                SecurityContextHolder.setContext(ctx);
                if (claim.phase() == Phase.VALIDATE) validate(jobId); else process(jobId);
            } catch (LeaseLost e) {
                log.info("Import {} taken over by another worker", jobId);
            } catch (RuntimeException e) {
                if (transientFailure(e)) {
                    log.warn("Import {} paused after a database failure ({}); it resumes when its lease expires", jobId, e.getClass().getSimpleName());
                } else {
                    log.error("Import {} failed: {}", jobId, e.getClass().getName());
                    try {
                        fail(jobId, "SYSTEM", "The import stopped because of an unexpected error. You can retry it.", true);
                    } catch (RuntimeException ignored) {
                        log.warn("Import {} could not be marked failed; the sweeper retries", jobId);
                    }
                }
            } finally {
                SecurityContextHolder.setContext(previous);
            }
            return true;
        });
    }

    private Claim claim(UUID jobId) {
        return tx.execute(s -> jdbc.sql("""
                UPDATE student_import_job SET status = CASE WHEN phase = 'VALIDATE' THEN 'VALIDATING' ELSE 'PROCESSING' END, lease_owner = ?,
                    lease_until = now() + make_interval(secs => ?), attempts = attempts + 1,
                    started_at = CASE WHEN phase = 'IMPORT' THEN coalesce(started_at, now()) ELSE started_at END, updated_at = now(), version = version + 1
                WHERE id = ? AND phase IS NOT NULL AND (status = 'QUEUED' OR (status IN ('VALIDATING', 'PROCESSING') AND lease_until < now()))
                RETURNING phase, attempts, coalesce(phase_requested_by, confirmed_by, created_by)""")
                .params(owner, (double) props.lease().toSeconds(), jobId)
                .query((rs, n) -> new Claim(jobId, Phase.valueOf(rs.getString(1)), rs.getInt(2), rs.getObject(3, UUID.class))).optional().orElse(null));
    }

    private void heartbeat(UUID jobId) {
        Integer n = tx.execute(s -> jdbc.sql("UPDATE student_import_job SET lease_until = now() + make_interval(secs => ?) WHERE id = ? AND lease_owner = ?")
                .params((double) props.lease().toSeconds(), jobId, owner).update());
        if (n == null || n == 0) throw new LeaseLost();
    }

    private boolean cancelRequested(UUID jobId) {
        return Boolean.TRUE.equals(tx.execute(s -> jdbc.sql("SELECT cancel_requested FROM student_import_job WHERE id = ?").param(jobId).query(Boolean.class).single()));
    }

    private Optional<IvoroviPrincipal> principalOf(UUID tenant, UUID userId) {
        if (userId == null) return Optional.empty();
        Optional<String> subject = tx.execute(s -> jdbc.sql("SELECT external_subject FROM user_account WHERE id = ? AND status = 'ACTIVE'").param(userId)
                .query(String.class).optional());
        return subject == null || subject.isEmpty() ? Optional.empty() : tx.execute(s -> principals.resolve(tenant, subject.get()));
    }

    private static boolean transientFailure(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof CannotGetJdbcConnectionException || t instanceof DataAccessResourceFailureException || t instanceof TransientDataAccessException) return true;
        }
        return false;
    }

    // ================================================================================================ validation phase

    private StudentImportEngine.Context context(JobRow j) {
        List<String> headers = json.readValue(j.headersJson(), new TypeReference<List<String>>() {});
        Map<Integer, ImportField> mapping = new LinkedHashMap<>();
        for (MappingEntry m : json.readValue(j.mappingJson(), MAPPING)) {
            ImportField f = ImportField.ofKey(m.field());
            if (f != null) mapping.put(m.column(), f);
        }
        StudentImportEngine.Refs refs = engine.loadRefs(j.institutionId());
        return new StudentImportEngine.Context(j.institutionId(), refs, engine.settings(j.institutionId()), mapping, headers, imports.personalAllowed(j.institutionId()));
    }

    private record RowInsert(int rowNo, String cellsEnc, String displayName, String studentNo, String registrationNo, String status, String issues,
                             String category, UUID matchStudentId, Integer matchRowNo, String matchReason, String plan) {}

    void validate(UUID jobId) {
        long started = System.nanoTime();
        JobRow j = tx.execute(s -> imports.jobRow(jobId, false).orElseThrow());
        if (!imports.personalAllowed(j.institutionId()) && json.readValue(j.mappingJson(), MAPPING).stream()
                .anyMatch(m -> ImportField.ofKey(m.field()) != null && ImportField.ofKey(m.field()).personal)) {
            fail(jobId, "DATA", "The person who mapped the file may no longer import personal or guardian information.", false);
            return;
        }
        StudentImportEngine.Context ctx = tx.execute(s -> context(j));
        StudentImportEngine.Index index = tx.execute(s -> engine.loadIndex(j.institutionId(), ctx.settings()));
        StudentImportEngine.InFile inFile = new StudentImportEngine.InFile();
        tx.executeWithoutResult(s -> jdbc.sql("DELETE FROM student_import_row WHERE job_id = ?").param(jobId).update());
        List<RowInsert> buffer = new ArrayList<>();
        int[] seen = {0};
        boolean[] cancelled = {false};
        try {
            imports.withSource(j, reader -> reader.rows(r -> {
                List<String> cells = new ArrayList<>(r.cells().size());
                List<Integer> formulas = new ArrayList<>();
                for (int i = 0; i < r.cells().size(); i++) {
                    TabularFile.Cell c = r.cell(i);
                    cells.add(c.text());
                    if (c.formula()) formulas.add(i);
                }
                Parsed p = tx.execute(s -> engine.parse(ctx, r.number(), cells, formulas));
                Classification cl = engine.classify(ctx, p, index, inFile, Set.of());
                List<Issue> issues = new ArrayList<>(p.issues);
                issues.addAll(cl.extraIssues());
                RowStatus status = issues.stream().anyMatch(i -> i.severity() == StudentImportEngine.Severity.ERROR) ? RowStatus.INVALID
                        : cl.status() == RowStatus.VALID && !issues.isEmpty() ? RowStatus.WARNING : cl.status();
                String name = p.displayName();
                buffer.add(new RowInsert(r.number(), imports.encryptCells(new StudentImportService.Cells(cells, formulas)), name == null || name.isEmpty() ? null : trim(name, 300),
                        trim(p.studentNo, 40), trim(p.registrationNo, 40), status.name(), imports.issuesJson(issues),
                        cl.category() == null ? null : cl.category().name(), cl.studentId(), cl.matchRowNo(), trim(cl.reason(), 300),
                        status == RowStatus.INVALID ? null : json.writeValueAsString(cl.plan())));
                seen[0]++;
                if (buffer.size() >= 500) {
                    flush(jobId, buffer, seen[0]);
                    if (cancelRequested(jobId)) { cancelled[0] = true; return false; }
                }
                return true;
            }));
        } catch (TabularFormatException e) {
            fail(jobId, "DATA", e.getMessage(), false);
            return;
        }
        flush(jobId, buffer, seen[0]);
        if (cancelled[0] || cancelRequested(jobId)) {
            tx.executeWithoutResult(s -> {
                jdbc.sql("UPDATE student_import_job SET status = 'CANCELLED', cancel_requested = false, lease_owner = NULL, lease_until = NULL, finished_at = now(), "
                        + "updated_at = now(), version = version + 1 WHERE id = ?").param(jobId).update();
                audit.recordSuccess("student.import.cancelled", "student_import", jobId.toString(), Map.of("phase", "VALIDATE", "rows", String.valueOf(seen[0])));
            });
            return;
        }
        long ms = (System.nanoTime() - started) / 1_000_000;
        tx.executeWithoutResult(s -> {
            imports.recount(jobId);
            jdbc.sql("""
                    UPDATE student_import_job SET status = 'READY', total_rows = ?, progress_total = ?, processed_rows = ?, validated_at = now(), lease_owner = NULL,
                        lease_until = NULL, attempts = 0, updated_at = now(), version = version + 1 WHERE id = ?""")
                    .params(seen[0], seen[0], seen[0], jobId).update();
            Map<String, Object> c = jdbc.sql("SELECT valid_rows, invalid_rows, duplicate_rows, potential_duplicate_rows, conflict_rows FROM student_import_job WHERE id = ?")
                    .param(jobId).query().singleRow();
            Map<String, String> d = new LinkedHashMap<>();
            d.put("rows", String.valueOf(seen[0]));
            c.forEach((k, v) -> d.put(k, String.valueOf(v)));
            d.put("durationMs", String.valueOf(ms));
            audit.recordSuccess("student.import.validated", "student_import", jobId.toString(), d);
            outbox.append("student_import", jobId.toString(), "student.import.validated", Map.of("jobId", jobId.toString(), "rows", seen[0]));
        });
    }

    private void flush(UUID jobId, List<RowInsert> buffer, int seen) {
        if (buffer.isEmpty()) {
            heartbeat(jobId);
            return;
        }
        UUID tenant = TenantContext.requireTenantId();
        List<RowInsert> batch = List.copyOf(buffer);
        buffer.clear();
        tx.executeWithoutResult(s -> {
            jdbcTemplate.batchUpdate("""
                    INSERT INTO student_import_row (id, tenant_id, job_id, row_no, cells_enc, display_name, student_no, registration_no, validation_status, issues,
                        match_category, match_student_id, match_row_no, match_reason, plan) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, CAST(? AS jsonb))""",
                    batch, batch.size(), (ps, r) -> {
                        ps.setObject(1, Ids.newId());
                        ps.setObject(2, tenant);
                        ps.setObject(3, jobId);
                        ps.setInt(4, r.rowNo());
                        ps.setString(5, r.cellsEnc());
                        ps.setString(6, r.displayName());
                        ps.setString(7, r.studentNo());
                        ps.setString(8, r.registrationNo());
                        ps.setString(9, r.status());
                        ps.setString(10, r.issues());
                        ps.setString(11, r.category());
                        ps.setObject(12, r.matchStudentId());
                        ps.setObject(13, r.matchRowNo());
                        ps.setString(14, r.matchReason());
                        ps.setString(15, r.plan());
                    });
            jdbc.sql("UPDATE student_import_job SET processed_rows = ?, lease_until = now() + make_interval(secs => ?), updated_at = now() WHERE id = ? AND lease_owner = ?")
                    .params(seen, (double) props.lease().toSeconds(), jobId, owner).update();
        });
    }

    // ================================================================================================ import phase

    private record RowRef(UUID id, int rowNo, String cellsEnc, RowStatus status, MatchCategory category, UUID matchStudentId, Integer matchRowNo,
                          Decision decision, String plan) {}

    private static final class RowFailure extends RuntimeException {
        final String code;

        RowFailure(String code, String message) {
            super(message, null, false, false);
            this.code = code;
        }
    }

    private static final class AlreadyDone extends RuntimeException {
        AlreadyDone() { super(null, null, false, false); }
    }

    void process(UUID jobId) {
        JobRow j = tx.execute(s -> imports.jobRow(jobId, false).orElseThrow());
        Mode mode = j.mode();
        StudentImportEngine.Context ctx = tx.execute(s -> context(j));
        Set<UUID> own = Collections.synchronizedSet(new HashSet<>(tx.execute(s ->
                jdbc.sql("SELECT student_id FROM student_import_row WHERE job_id = ? AND outcome = 'IMPORTED'").param(jobId).query(UUID.class).list())));
        tx.executeWithoutResult(s -> audit.recordSuccess("student.import.processing", "student_import", jobId.toString(),
                Map.of("mode", mode.name(), "attempt", String.valueOf(j.attempts() + 1))));
        while (true) {
            heartbeat(jobId);
            if (cancelRequested(jobId)) {
                stop(jobId, mode, "CANCELLED", null, "Cancelled by an administrator.");
                return;
            }
            List<RowRef> rows = tx.execute(s -> jdbc.sql("""
                    SELECT id, row_no, cells_enc, validation_status, match_category, match_student_id, match_row_no, decision, plan FROM student_import_row
                    WHERE job_id = ? AND outcome = 'PENDING' ORDER BY row_no LIMIT ?""").params(jobId, props.batchSize())
                    .query((rs, n) -> new RowRef(rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3), RowStatus.valueOf(rs.getString(4)),
                            rs.getString(5) == null ? null : MatchCategory.valueOf(rs.getString(5)), rs.getObject(6, UUID.class), (Integer) rs.getObject(7),
                            rs.getString(8) == null ? null : Decision.valueOf(rs.getString(8)), rs.getString(9))).list());
            if (rows == null || rows.isEmpty()) break;
            for (int from = 0; from < rows.size(); from += TX_ROWS) {
                List<RowRef> group = rows.subList(from, Math.min(rows.size(), from + TX_ROWS));
                if (importGroup(j, mode, ctx, group, own)) continue;
                // a row of the group failed: the group was rolled back; isolate the failure row by row
                for (RowRef row : group) {
                    String failure = processRow(j, mode, ctx, row, own);
                    if (failure != null && mode == Mode.ALL_OR_NOTHING) {
                        String kind = failure.startsWith("system_error") ? "SYSTEM" : "DATA";
                        stop(jobId, mode, "FAILED", kind, "Row " + row.rowNo() + " could not be imported, so no record was kept (all-or-nothing). "
                                + failure.substring(failure.indexOf(':') + 1).trim());
                        return;
                    }
                }
            }
            tx.executeWithoutResult(s -> progress(jobId));
        }
        finish(jobId, mode);
    }

    /** Rows committed together; a failing row rolls the group back and the group is retried row by row. */
    static final int TX_ROWS = 25;

    /** Imports a group of rows in one transaction; false when any row failed (nothing of the group is kept). */
    private boolean importGroup(JobRow j, Mode mode, StudentImportEngine.Context ctx, List<RowRef> group, Set<UUID> own) {
        List<UUID> created = new ArrayList<>();
        try {
            tx.executeWithoutResult(s -> {
                for (RowRef row : group) importRow(j, mode, ctx, row, own, created);
            });
        } catch (RuntimeException e) {
            if (transientFailure(e) && !(e instanceof DataIntegrityViolationException)) throw e;
            return false;
        }
        own.addAll(created);
        return true;
    }

    /** One row in its own transaction. Returns null when the row was imported (or already done), else "code: message". */
    private String processRow(JobRow j, Mode mode, StudentImportEngine.Context ctx, RowRef row, Set<UUID> own) {
        List<UUID> created = new ArrayList<>();
        try {
            tx.executeWithoutResult(s -> importRow(j, mode, ctx, row, own, created));
            own.addAll(created);
            return null;
        } catch (AlreadyDone e) {
            return null;
        } catch (RowFailure e) {
            return recordFailure(row, e.code, e.getMessage());
        } catch (ApiException e) {
            return recordFailure(row, e.code(), e.getMessage());
        } catch (AccessDeniedException e) {
            return recordFailure(row, "not_permitted", "You are not permitted to import this row (check your permissions for its institution, department and personal details).");
        } catch (DataIntegrityViolationException e) {
            if (transientFailure(e)) throw e;
            return recordFailure(row, "duplicate_identifier", "Another student with the same identifier was created at the same time. Validate the file again.");
        } catch (RuntimeException e) {
            if (transientFailure(e)) throw e;
            log.error("Import {} row {} failed: {}", j.id(), row.rowNo(), e.getClass().getName());
            return recordFailure(row, "system_error", "Unexpected error while importing this row.");
        }
    }

    /**
     * Creates the row's records and its outcome in the caller's transaction (both commit or neither does). Re-validates
     * the row and re-checks duplicates against the live database under advisory locks.
     */
    private void importRow(JobRow j, Mode mode, StudentImportEngine.Context ctx, RowRef row, Set<UUID> own, List<UUID> created) {
        if (row.cellsEnc() == null) throw new RowFailure("source_purged", "The row's values were purged after the retention period.");
        StudentImportService.Cells cells = imports.decryptCells(row.cellsEnc());
        Parsed p = engine.parse(ctx, row.rowNo(), cells.c(), cells.f());
        if (p.hasErrors()) {
            Issue first = p.issues.stream().filter(i -> i.severity() == StudentImportEngine.Severity.ERROR).findFirst().orElseThrow();
            throw new RowFailure("validation_changed", "The row no longer passes validation: " + (first.column() == null ? "" : first.column() + ": ") + first.message());
        }
        lockKeys(p);
        StudentImportEngine.Index live = engine.rowIndex(j.institutionId(), ctx.settings(), p);
        Set<UUID> mine = new HashSet<>(own);
        mine.addAll(created);
        Classification c = engine.classify(ctx, p, live, null, mine);
        boolean addToExisting = row.decision() == Decision.ADD_TO_EXISTING;
        UUID studentId;
        String outcome;
        Map<String, Object> prior = null;
        if (!addToExisting) {
            boolean ok = c.status() == RowStatus.VALID || c.status() == RowStatus.WARNING
                    || (row.decision() == Decision.CREATE_NEW && c.category() == MatchCategory.POTENTIAL_DUPLICATE && c.studentId() != null
                        && c.studentId().equals(row.matchStudentId()));
            if (!ok) throw new RowFailure("changed_since_validation", "Found after validation: " + (c.reason() == null ? "the row now matches an existing student." : c.reason())
                    + " Validate the file again.");
            studentId = createAll(j, ctx, p);
            outcome = "IMPORTED";
        } else {
            if (c.category() != MatchCategory.EXISTING_NEW_ENROLMENT || c.studentId() == null || !c.studentId().equals(row.matchStudentId())) {
                throw new RowFailure("changed_since_validation", "The matched student changed after validation" + (c.reason() == null ? "." : ": " + c.reason())
                        + " Validate the file again.");
            }
            studentId = c.studentId();
            prior = jdbc.sql("SELECT status, status_effective_on::text AS effective FROM student WHERE id = ?").param(studentId).query().singleRow();
            addToExisting(p, c.plan(), studentId);
            outcome = "UPDATED";
        }
        if (mode == Mode.ALL_OR_NOTHING) {
            jdbc.sql("UPDATE student SET active = false WHERE id = ?").param(studentId).update();
        }
        StudentAccess.StudentRef ref = access.student(studentId);
        access.event(studentId, "student.imported", (outcome.equals("IMPORTED") ? "Imported" : "Records added") + " from bulk import "
                + j.id().toString().substring(0, 8) + " (row " + row.rowNo() + ")");
        Map<String, String> d = new LinkedHashMap<>();
        d.put("studentNo", ref.studentNo());
        d.put("importId", j.id().toString());
        d.put("rowNo", String.valueOf(row.rowNo()));
        d.put("outcome", outcome);
        audit.recordSuccess("student.imported", "student", studentId.toString(), d);
        int n = jdbc.sql("""
                UPDATE student_import_row SET outcome = ?, student_id = ?, student_no = ?, processed_tx = now(), prior_state = CAST(? AS jsonb), processed_at = now(),
                    outcome_code = NULL, outcome_message = ? WHERE id = ? AND outcome = 'PENDING'""")
                .params(outcome, studentId, ref.studentNo(), prior == null ? null : json.writeValueAsString(prior),
                        outcome.equals("IMPORTED") ? "Imported as " + ref.studentNo() + "." : "Records added to " + ref.studentNo() + ".", row.id()).update();
        if (n == 0) throw new AlreadyDone();
        if (outcome.equals("IMPORTED")) created.add(studentId);
    }

    private String recordFailure(RowRef row, String code, String message) {
        String msg = trim(message, 480);
        tx.executeWithoutResult(s -> jdbc.sql("UPDATE student_import_row SET outcome = 'FAILED', outcome_code = ?, outcome_message = ?, processed_at = now() "
                + "WHERE id = ? AND outcome = 'PENDING'").params(trim(code, 60), msg, row.id()).update());
        return code + ": " + msg;
    }

    /** Serialises concurrent imports of the same identifiers or names (transaction-scoped, sorted to avoid deadlocks). */
    private void lockKeys(Parsed p) {
        TreeSet<String> keys = new TreeSet<>();
        if (p.studentNo != null) keys.add("sid:" + p.studentNo.toLowerCase());
        if (p.registrationNo != null) keys.add("reg:" + p.registrationNo.toLowerCase());
        if (p.admissionNo != null) keys.add("adm:" + p.admissionNo.toLowerCase());
        if (p.institutionalEmail != null) keys.add("iem:" + p.institutionalEmail);
        if (p.personalEmail != null) keys.add("pem:" + p.personalEmail);
        if (p.nameKey() != null) keys.add("name:" + p.nameKey());
        UUID tenant = TenantContext.requireTenantId();
        for (String k : keys) {
            jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(?, 4242))").param(tenant + ":" + k).query((rs, n) -> 1).list();
        }
    }

    private UUID createAll(JobRow j, StudentImportEngine.Context ctx, Parsed p) {
        StudentService.PersonalCommand personal = p.hasPersonal
                ? new StudentService.PersonalCommand(p.dateOfBirth, p.gender, p.nationality, p.personalEmail, p.mobile, p.alternatePhone, p.currentAddress, p.permanentAddress)
                : null;
        boolean manual = ctx.refs().numberingMode == StudentNumbering.Mode.MANUAL;
        UUID id = students.create(new StudentService.Command(j.institutionId(), manual ? p.studentNo : null, p.admissionYear, p.registrationNo, p.rollNo, p.previousRef,
                p.first, p.middle, p.last, p.institutionalEmail, personal, null));
        if (p.hasAdmission && p.admissionDate != null && p.admissionDate.isBefore(LocalDate.now())) {
            // migrated history: the profile's first status takes effect on the admission date, so earlier admission and enrolment dates are preserved
            LocalDate since = p.admissionDate;
            jdbc.sql("DELETE FROM student_status_history WHERE student_id = ? AND from_status IS NULL").param(id).update();
            jdbc.sql("INSERT INTO student_status_history (id, tenant_id, student_id, from_status, to_status, effective_on, reason, actor_id) "
                    + "VALUES (?, ?, ?, NULL, 'PROSPECTIVE', ?, 'Profile created (imported from file)', ?)")
                    .params(Ids.newId(), StudentAccess.tenant(), id, since, StudentAccess.actor()).update();
            jdbc.sql("UPDATE student SET status_effective_on = ? WHERE id = ?").params(since, id).update();
        }
        addRecords(p, id, p.hasAdmission, p.hasEnrolment, p.guardians.size(), p.targetStatus == Status.ACTIVE, false);
        return id;
    }

    private void addToExisting(Parsed p, StudentImportEngine.Plan plan, UUID studentId) {
        addRecords(p, studentId, plan.admission(), plan.enrolment(), plan.guardians(), plan.activate(), true);
    }

    private void addRecords(Parsed p, UUID id, boolean admission, boolean enrolment, int guardianCount, boolean activate, boolean existing) {
        if (admission) {
            admissions.create(id, new AdmissionService.Command(p.admissionNo, p.admissionDate, p.admissionType, p.admissionCategory,
                    p.entryYear == null ? null : p.entryYear.id(), p.program == null ? null : p.program.id(), p.course == null ? null : p.course.id(), p.entryTerm,
                    p.admissionStatus, p.previousInstitution, null, null));
        }
        if (enrolment && p.batch != null) {
            LocalDate effective = p.enrolmentDate;
            if (existing && effective != null) {
                // an existing record's status date cannot go backwards (validation warned about the adjustment)
                LocalDate current = jdbc.sql("SELECT status_effective_on FROM student WHERE id = ?").param(id).query(LocalDate.class).single();
                if (effective.isBefore(current)) effective = current;
            }
            enrolments.change(id, new EnrolmentService.Command(EnrolmentService.ChangeType.INITIAL, p.batch.id(), p.year == null ? null : p.year.id(), null,
                    p.termNumber, effective, "Imported from file"));
        }
        for (int i = 0; i < Math.min(guardianCount, p.guardians.size()); i++) {
            StudentImportEngine.Guardian g = p.guardians.get(i);
            guardians.add(id, new GuardianService.Command(g.name(), g.relationship(), g.mobile(), g.email(), g.address(), i == 0, g.emergency(), null));
        }
        if (activate) {
            StudentAccess.StudentRef ref = access.student(id);
            if ("ENROLLED".equals(ref.status())) {
                LocalDate effective = p.enrolmentDate == null || p.enrolmentDate.isAfter(LocalDate.now()) ? LocalDate.now() : p.enrolmentDate;
                LocalDate current = jdbc.sql("SELECT status_effective_on FROM student WHERE id = ?").param(id).query(LocalDate.class).single();
                students.changeStatus(id, Status.ACTIVE, effective.isBefore(current) ? current : effective, "Imported as active", ref.version());
            }
        }
    }

    private void progress(UUID jobId) {
        jdbc.sql("""
                UPDATE student_import_job j SET (imported_rows, updated_rows, failed_rows, skipped_rows, processed_rows) = (
                    SELECT count(*) FILTER (WHERE outcome = 'IMPORTED'), count(*) FILTER (WHERE outcome = 'UPDATED'), count(*) FILTER (WHERE outcome = 'FAILED'),
                           count(*) FILTER (WHERE outcome IN ('SKIPPED', 'NOT_PROCESSED', 'ROLLED_BACK')),
                           count(*) FILTER (WHERE outcome IN ('IMPORTED', 'UPDATED', 'FAILED') OR (outcome IN ('ROLLED_BACK', 'NOT_PROCESSED') AND plan IS NOT NULL
                               AND (validation_status IN ('VALID', 'WARNING') OR decision IN ('CREATE_NEW', 'ADD_TO_EXISTING'))))
                    FROM student_import_row r WHERE r.job_id = j.id), updated_at = now()
                WHERE j.id = ?""").param(jobId).update();
    }

    private void finish(UUID jobId, Mode mode) {
        tx.executeWithoutResult(s -> {
            if (mode == Mode.ALL_OR_NOTHING) {
                jdbc.sql("UPDATE student SET active = true WHERE id IN (SELECT student_id FROM student_import_row WHERE job_id = ? AND outcome IN ('IMPORTED', 'UPDATED'))")
                        .param(jobId).update();
            }
            progress(jobId);
            Map<String, Object> c = jdbc.sql("SELECT imported_rows, updated_rows, failed_rows, skipped_rows FROM student_import_job WHERE id = ?").param(jobId).query().singleRow();
            int ok = ((Number) c.get("imported_rows")).intValue() + ((Number) c.get("updated_rows")).intValue();
            int failed = ((Number) c.get("failed_rows")).intValue();
            long notImported = jdbc.sql("SELECT count(*) FROM student_import_row WHERE job_id = ? AND (outcome = 'FAILED' OR (outcome = 'SKIPPED' AND outcome_code IN ('invalid', 'conflict')))")
                    .param(jobId).query(Long.class).single();
            String status = ok == 0 && failed > 0 ? "FAILED" : notImported > 0 ? "PARTIALLY_COMPLETED" : "COMPLETED";
            jdbc.sql("""
                    UPDATE student_import_job SET status = ?, failure_kind = ?, failure_message = ?, lease_owner = NULL, lease_until = NULL, finished_at = now(),
                        updated_at = now(), version = version + 1 WHERE id = ?""")
                    .params(status, status.equals("FAILED") ? "DATA" : null, status.equals("FAILED") ? "No row could be imported. Download the error report." : null, jobId)
                    .update();
            Map<String, String> d = new LinkedHashMap<>();
            d.put("status", status);
            c.forEach((k, v) -> d.put(k, String.valueOf(v)));
            audit.record(status.equals("FAILED") ? "student.import.failed" : "student.import.completed", "student_import", jobId.toString(),
                    status.equals("FAILED") ? AuditOutcome.FAILURE : AuditOutcome.SUCCESS, d);
            outbox.append("student_import", jobId.toString(), "student.import.finished", Map.of("jobId", jobId.toString(), "status", status));
        });
    }

    /** Stops a running import: all-or-nothing jobs are compensated; valid-rows-only jobs keep what was imported. */
    private void stop(UUID jobId, Mode mode, String status, String failureKind, String message) {
        int rolledBack = 0;
        if (mode == Mode.ALL_OR_NOTHING) rolledBack = compensate(jobId);
        int compensated = rolledBack;
        tx.executeWithoutResult(s -> {
            jdbc.sql("UPDATE student_import_row SET outcome = 'NOT_PROCESSED', outcome_code = ?, outcome_message = ?, processed_at = now() WHERE job_id = ? AND outcome = 'PENDING'")
                    .params(status.equals("CANCELLED") ? "cancelled" : "stopped", status.equals("CANCELLED") ? "Not imported: the import was cancelled."
                            : "Not imported: the import stopped before this row.", jobId).update();
            progress(jobId);
            jdbc.sql("""
                    UPDATE student_import_job SET status = ?, failure_kind = ?, failure_message = ?, rolled_back = ?, cancel_requested = false, lease_owner = NULL,
                        lease_until = NULL, finished_at = now(), updated_at = now(), version = version + 1 WHERE id = ?""")
                    .params(status, failureKind, trim(message, 480), mode == Mode.ALL_OR_NOTHING, jobId).update();
            Map<String, String> d = new LinkedHashMap<>();
            d.put("mode", mode.name());
            d.put("rolledBackRows", String.valueOf(compensated));
            if (failureKind != null) d.put("failureKind", failureKind);
            audit.record(status.equals("CANCELLED") ? "student.import.cancelled" : "student.import.failed", "student_import", jobId.toString(),
                    status.equals("CANCELLED") ? AuditOutcome.SUCCESS : AuditOutcome.FAILURE, d);
            outbox.append("student_import", jobId.toString(), "student.import.finished", Map.of("jobId", jobId.toString(), "status", status));
            if (mode == Mode.ALL_OR_NOTHING && compensated > 0) {
                audit.recordSuccess("student.import.rolled_back", "student_import", jobId.toString(), Map.of("rows", String.valueOf(compensated)));
                outbox.append("student_import", jobId.toString(), "student.import.rolled_back", Map.of("jobId", jobId.toString(), "rows", compensated));
            }
        });
    }

    /** Removes everything an all-or-nothing job committed, newest first, one student per transaction. */
    private int compensate(UUID jobId) {
        record Done(UUID rowId, UUID studentId, String outcome, String prior) {}
        List<Done> done = tx.execute(s -> jdbc.sql("""
                SELECT id, student_id, outcome, prior_state::text FROM student_import_row WHERE job_id = ? AND outcome IN ('IMPORTED', 'UPDATED') ORDER BY row_no DESC""")
                .param(jobId).query((rs, n) -> new Done(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4))).list());
        int n = 0;
        for (Done d : done) {
            tx.executeWithoutResult(s -> {
                String txSql = "(SELECT processed_tx FROM student_import_row WHERE id = ?)";
                if ("IMPORTED".equals(d.outcome())) {
                    for (String t : List.of("enrolment", "guardian", "admission_record", "student_event", "student_status_history")) {
                        jdbc.sql("DELETE FROM " + t + " WHERE student_id = ?").param(d.studentId()).update();
                    }
                    jdbc.sql("DELETE FROM student WHERE id = ?").param(d.studentId()).update();
                } else {
                    for (String t : List.of("enrolment", "guardian", "admission_record", "student_event", "student_status_history")) {
                        jdbc.sql("DELETE FROM " + t + " WHERE student_id = ? AND created_at = " + txSql).params(d.studentId(), d.rowId()).update();
                    }
                    Map<String, Object> prior = json.readValue(d.prior(), new TypeReference<Map<String, Object>>() {});
                    jdbc.sql("UPDATE student SET status = ?, status_effective_on = CAST(? AS date), active = true, updated_at = now(), version = version + 1 WHERE id = ?")
                            .params(prior.get("status"), prior.get("effective"), d.studentId()).update();
                    access.refreshScope(d.studentId());
                }
                jdbc.sql("UPDATE student_import_row SET outcome = 'ROLLED_BACK', outcome_message = 'Rolled back: the all-or-nothing import did not complete.' WHERE id = ?")
                        .param(d.rowId()).update();
            });
            n++;
        }
        return n;
    }

    /** Marks a job failed outside the processing loop (all-or-nothing imports are compensated first). */
    private void fail(UUID jobId, String kind, String message, boolean compensateImport) {
        JobRow j = tx.execute(s -> imports.jobRow(jobId, false).orElseThrow());
        if (compensateImport && j.phase() == Phase.IMPORT && j.mode() != null) {
            stop(jobId, j.mode(), "FAILED", kind, message);
            return;
        }
        tx.executeWithoutResult(s -> {
            jdbc.sql("""
                    UPDATE student_import_job SET status = 'FAILED', failure_kind = ?, failure_message = ?, lease_owner = NULL, lease_until = NULL, finished_at = now(),
                        updated_at = now(), version = version + 1 WHERE id = ?""").params(kind, trim(message, 480), jobId).update();
            audit.record("student.import.failed", "student_import", jobId.toString(), AuditOutcome.FAILURE,
                    Map.of("phase", String.valueOf(j.phase()), "failureKind", kind));
        });
    }

    // ================================================================================================ retention

    /** Deletes source files and raw row values after the retention period; abandoned uploads are cancelled. */
    @Scheduled(cron = "${ivorovi.retention.cron:0 17 * * * *}")
    void purgeScheduled() {
        for (UUID tenant : activeTenants()) TenantContext.runInTenant(tenant, this::purgeExpired);
    }

    public int purgeExpired() {
        record Expired(UUID id, String path, String status) {}
        List<Expired> jobs = tx.execute(s -> jdbc.sql("""
                SELECT id, object_path, status FROM student_import_job WHERE source_purged_at IS NULL AND (
                    (finished_at IS NOT NULL AND finished_at < now() - make_interval(days => ?))
                    OR (status IN ('UPLOADED', 'READY') AND updated_at < now() - make_interval(days => ?)))""")
                .params(props.retentionDays(), props.retentionDays()).query((rs, n) -> new Expired(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3))).list());
        for (Expired e : jobs) {
            if (e.path() != null) {
                try {
                    storage.delete(e.path());
                } catch (RuntimeException ex) {
                    log.warn("Import source {} could not be deleted: {}", e.id(), ex.getClass().getSimpleName());
                    continue;
                }
            }
            tx.executeWithoutResult(s -> {
                jdbc.sql("UPDATE student_import_row SET cells_enc = NULL WHERE job_id = ?").param(e.id()).update();
                jdbc.sql("UPDATE student_import_job SET source_purged_at = now(), status = CASE WHEN status IN ('UPLOADED', 'READY') THEN 'CANCELLED' ELSE status END, "
                        + "finished_at = coalesce(finished_at, now()), updated_at = now(), version = version + 1 WHERE id = ?").param(e.id()).update();
                audit.recordSuccess("student.import.purged", "student_import", e.id().toString(), Map.of("previousStatus", e.status()));
            });
        }
        return jobs.size();
    }

    static String trim(String s, int max) {
        return s == null ? null : s.length() <= max ? s : s.substring(0, max);
    }
}
