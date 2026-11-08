package com.ivorovi.integrations.internal;

import com.ivorovi.integrations.internal.IntegrationAdapter.Kind;
import com.ivorovi.integrations.internal.IntegrationAdapter.Outcome;
import com.ivorovi.platform.audit.AuditLog;
import com.ivorovi.platform.audit.AuditOutcome;
import com.ivorovi.platform.crypto.FieldCipher;
import com.ivorovi.platform.ids.Ids;
import com.ivorovi.platform.outbox.Outbox;
import com.ivorovi.platform.security.Authz;
import com.ivorovi.platform.security.CurrentUser;
import com.ivorovi.platform.security.Permission;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.platform.web.ApiException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Integration configurations: create/update/enable/disable/delete, connection tests and syncs through the adapters
 * (each run recorded with attempts, outcome and duration), manual retries, scheduled health checks of enabled
 * integrations and failure tracking. The adapter secret is encrypted at rest and never returned. Outbound calls run
 * outside database transactions, are bounded by the request timeout and their failures stay inside this module.
 */
@Service
public class IntegrationService {

    private static final Logger log = LoggerFactory.getLogger(IntegrationService.class);
    static final String READ = Permission.INTEGRATION_READ.key();
    static final String MANAGE = Permission.INTEGRATION_MANAGE.key();
    static final String SECRET_FIELD = "integration.secret";

    public enum Operation { CONNECTION_TEST, SYNC }

    public enum Trigger { MANUAL, SCHEDULED, RETRY }

    @Schema(name = "Integration")
    public record View(UUID id, Kind kind, String adapter, String adapterLabel, String name, String description, Map<String, String> config,
                       boolean secretSet, boolean enabled, String connectionStatus, Instant lastCheckedAt, Instant lastSuccessAt, Instant lastSyncAt,
                       String lastError, int consecutiveFailures, Instant createdAt, Instant updatedAt, long version) {}

    @Schema(name = "IntegrationRun")
    public record Run(UUID id, UUID integrationId, Operation operation, Trigger trigger, String status, int attempts, String message, Integer durationMs,
                      UUID retryOf, String startedBy, Instant startedAt, Instant finishedAt) {}

    @Schema(name = "IntegrationDetail")
    public record Detail(View integration, IntegrationAdapter.Info adapter, List<Run> recentRuns, Map<String, Long> runsLast30Days) {}

    public record Command(Kind kind, String adapter, String name, String description, Map<String, String> config, String secret, Long version) {}

    public record Page<T>(List<T> items, long total) {}

    private final JdbcClient jdbc;
    private final Authz authz;
    private final AuditLog audit;
    private final Outbox outbox;
    private final FieldCipher cipher;
    private final JsonMapper json;
    private final TransactionTemplate tx;
    private final IntegrationsProperties props;
    private final Map<String, IntegrationAdapter> adapters;
    private final com.ivorovi.platform.jobs.JobLeases leases;

    IntegrationService(JdbcClient jdbc, Authz authz, AuditLog audit, Outbox outbox, FieldCipher cipher, JsonMapper json, TransactionTemplate tx,
                       IntegrationsProperties props, List<IntegrationAdapter> adapters, com.ivorovi.platform.jobs.JobLeases leases) {
        this.leases = leases;
        this.jdbc = jdbc;
        this.authz = authz;
        this.audit = audit;
        this.outbox = outbox;
        this.cipher = cipher;
        this.json = json;
        this.tx = tx;
        this.props = props;
        this.adapters = adapters.stream().collect(Collectors.toMap(a -> a.info().key(), Function.identity(), (a, b) -> a, TreeMap::new));
    }

    // ================================================================================================ reading

    public List<IntegrationAdapter.Info> adapters() {
        require(READ);
        return adapters.values().stream().map(IntegrationAdapter::info).toList();
    }

    public List<View> list() {
        require(READ);
        return tx.execute(s -> jdbc.sql(SELECT + " ORDER BY lower(name)").query(this::view).list());
    }

    public Detail get(UUID id) {
        require(READ);
        return tx.execute(s -> {
            View v = find(id);
            Map<String, Long> counts = new LinkedHashMap<>();
            jdbc.sql("SELECT status, count(*) FROM integration_run WHERE integration_id = ? AND started_at >= now() - interval '30 days' GROUP BY status")
                    .param(id).query(rs -> { counts.put(rs.getString(1), rs.getLong(2)); });
            return new Detail(v, adapter(v.adapter()).info(), runs(id, 0, 10).items(), counts);
        });
    }

    public Page<Run> runs(UUID id, int page, int size) {
        require(READ);
        return tx.execute(s -> {
            find(id);
            long total = jdbc.sql("SELECT count(*) FROM integration_run WHERE integration_id = ?").param(id).query(Long.class).single();
            List<Run> items = jdbc.sql("""
                    SELECT r.id, r.integration_id, r.operation, r.trigger, r.status, r.attempts, r.message, r.duration_ms, r.retry_of, u.display_name,
                           r.started_at, r.finished_at
                    FROM integration_run r LEFT JOIN user_account u ON u.id = r.started_by WHERE r.integration_id = ?
                    ORDER BY r.started_at DESC LIMIT ? OFFSET ?""").params(id, size, (long) page * size).query(IntegrationService::run).list();
            return new Page<>(items, total);
        });
    }

    // ================================================================================================ configuration

    public View create(Command c) {
        require(MANAGE);
        IntegrationAdapter a = adapter(c.adapter());
        if (c.kind() == null || !a.info().kinds().contains(c.kind())) {
            throw ApiException.badRequest("adapter_kind_mismatch", "The adapter " + a.info().label() + " cannot be used for this kind of integration.");
        }
        Map<String, String> config = clean(a, c.config());
        String secret = blankToNull(c.secret());
        validate(a, config, secret != null);
        UUID id = Ids.newId();
        UUID tenant = TenantContext.requireTenantId();
        tx.executeWithoutResult(s -> {
            try {
                jdbc.sql("""
                        INSERT INTO integration (id, tenant_id, kind, adapter, name, description, config, secret_enc, created_by, updated_by)
                        VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?)""")
                        .params(id, tenant, c.kind().name(), a.info().key(), name(c.name()), blankToNull(c.description()), json.writeValueAsString(config),
                                secret == null ? null : cipher.encrypt(secret, SECRET_FIELD, tenant), actor(), actor()).update();
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("name_taken", "Another integration already has this name.");
            }
            audit.recordSuccess("integration.created", "integration", id.toString(), Map.of("name", name(c.name()), "kind", c.kind().name(),
                    "adapter", a.info().key(), "configKeys", String.join(",", config.keySet()), "secretSet", String.valueOf(secret != null)));
        });
        return tx.execute(s -> find(id));
    }

    /** {@code secret}: null keeps the stored secret, an empty string removes it. Kind and adapter cannot change. */
    public View update(UUID id, Command c) {
        require(MANAGE);
        View current = tx.execute(s -> find(id));
        IntegrationAdapter a = adapter(current.adapter());
        Map<String, String> config = c.config() == null ? current.config() : clean(a, c.config());
        boolean clearSecret = c.secret() != null && c.secret().isEmpty();
        String newSecret = blankToNull(c.secret());
        validate(a, config, newSecret != null || (current.secretSet() && !clearSecret));
        UUID tenant = TenantContext.requireTenantId();
        tx.executeWithoutResult(s -> {
            int n;
            try {
                n = jdbc.sql("""
                        UPDATE integration SET name = ?, description = ?, config = CAST(? AS jsonb),
                            secret_enc = CASE WHEN CAST(? AS boolean) THEN NULL WHEN CAST(? AS text) IS NOT NULL THEN CAST(? AS text) ELSE secret_enc END,
                            connection_status = 'UNKNOWN', updated_by = ?, updated_at = now(), version = version + 1
                        WHERE id = ? AND version = ?""")
                        .params(name(c.name() == null ? current.name() : c.name()), c.description() == null ? current.description() : blankToNull(c.description()),
                                json.writeValueAsString(config), clearSecret, newSecret, newSecret == null ? null : cipher.encrypt(newSecret, SECRET_FIELD, tenant),
                                actor(), id, c.version() == null ? current.version() : c.version()).update();
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("name_taken", "Another integration already has this name.");
            }
            if (n == 0) throw ApiException.conflict("concurrent_modification", "This integration was changed by someone else. Reload and try again.");
            Map<String, String> d = new LinkedHashMap<>();
            d.put("configKeys", String.join(",", config.keySet()));
            d.put("secret", clearSecret ? "removed" : newSecret != null ? "replaced" : "unchanged");
            audit.recordSuccess("integration.updated", "integration", id.toString(), d);
        });
        return tx.execute(s -> find(id));
    }

    public View setEnabled(UUID id, boolean enabled) {
        require(MANAGE);
        tx.executeWithoutResult(s -> {
            find(id);
            jdbc.sql("UPDATE integration SET enabled = ?, updated_by = ?, updated_at = now(), version = version + 1 WHERE id = ?").params(enabled, actor(), id).update();
            audit.recordSuccess(enabled ? "integration.enabled" : "integration.disabled", "integration", id.toString(), Map.of());
        });
        return tx.execute(s -> find(id));
    }

    public void delete(UUID id) {
        require(MANAGE);
        tx.executeWithoutResult(s -> {
            View v = find(id);
            if (v.enabled()) throw ApiException.conflict("integration_enabled", "Disable the integration before deleting it.");
            jdbc.sql("DELETE FROM integration WHERE id = ?").param(id).update();
            audit.recordSuccess("integration.deleted", "integration", id.toString(), Map.of("name", v.name()));
        });
    }

    // ================================================================================================ runs

    public Run test(UUID id) {
        require(MANAGE);
        return execute(id, Operation.CONNECTION_TEST, Trigger.MANUAL, null);
    }

    public Run sync(UUID id) {
        require(MANAGE);
        View v = tx.execute(s -> find(id));
        if (!v.enabled()) throw ApiException.conflict("integration_disabled", "Enable the integration before synchronizing.");
        if (!adapter(v.adapter()).info().supportsSync()) throw ApiException.badRequest("sync_not_supported", "This adapter does not support synchronization.");
        return execute(id, Operation.SYNC, Trigger.MANUAL, null);
    }

    /** Re-runs a failed run's operation (recorded as a RETRY of it). */
    public Run retry(UUID integrationId, UUID runId) {
        require(MANAGE);
        Run r = tx.execute(s -> jdbc.sql("""
                SELECT r.id, r.integration_id, r.operation, r.trigger, r.status, r.attempts, r.message, r.duration_ms, r.retry_of, NULL, r.started_at, r.finished_at
                FROM integration_run r WHERE r.id = ? AND r.integration_id = ?""").params(runId, integrationId).query(IntegrationService::run).optional()
                .orElseThrow(() -> ApiException.notFound("Run")));
        if (!"FAILED".equals(r.status())) throw ApiException.conflict("run_not_failed", "Only a failed run can be retried.");
        return execute(integrationId, r.operation(), Trigger.RETRY, runId);
    }

    private Run execute(UUID id, Operation op, Trigger trigger, UUID retryOf) {
        UUID tenant = TenantContext.requireTenantId();
        record Loaded(View view, String secret) {}
        Loaded l = tx.execute(s -> {
            View v = find(id);
            String enc = jdbc.sql("SELECT secret_enc FROM integration WHERE id = ?").param(id).query(String.class).optional().orElse(null);
            return new Loaded(v, enc == null ? null : cipher.decrypt(enc, SECRET_FIELD, tenant));
        });
        IntegrationAdapter a = adapter(l.view().adapter());
        UUID runId = Ids.newId();
        UUID actor = trigger == Trigger.SCHEDULED ? null : actor();
        tx.executeWithoutResult(s -> jdbc.sql("""
                INSERT INTO integration_run (id, tenant_id, integration_id, operation, trigger, status, attempts, retry_of, started_by)
                VALUES (?, ?, ?, ?, ?, 'RUNNING', 0, ?, ?)""").params(runId, tenant, id, op.name(), trigger.name(), retryOf, actor).update());
        long started = System.nanoTime();
        Outcome outcome = null;
        int attempts = 0;
        while (attempts < props.runAttempts()) {
            attempts++;
            try {
                outcome = op == Operation.SYNC ? a.sync(l.view().config(), l.secret()) : a.test(l.view().config(), l.secret());
            } catch (ApiException e) {
                outcome = new Outcome(false, e.getMessage());   // configuration no longer valid (e.g. host now private): no point retrying
                break;
            } catch (RuntimeException e) {
                log.warn("Integration {} {} attempt {} failed: {}", id, op, attempts, e.getClass().getSimpleName());
                outcome = new Outcome(false, "Unexpected error: " + e.getClass().getSimpleName() + ".");
            }
            if (outcome.ok() || attempts >= props.runAttempts()) break;
            try {
                Thread.sleep(Math.min(2000, 250L << attempts));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        Outcome o = outcome;
        int tries = attempts;
        int ms = (int) ((System.nanoTime() - started) / 1_000_000);
        tx.executeWithoutResult(s -> {
            jdbc.sql("UPDATE integration_run SET status = ?, attempts = ?, message = ?, duration_ms = ?, finished_at = now() WHERE id = ?")
                    .params(o.ok() ? "SUCCEEDED" : "FAILED", tries, truncate(o.message(), 500), ms, runId).update();
            if (o.ok()) {
                jdbc.sql("""
                        UPDATE integration SET connection_status = 'HEALTHY', last_checked_at = now(), last_success_at = now(),
                            last_sync_at = CASE WHEN CAST(? AS boolean) THEN now() ELSE last_sync_at END, last_error = NULL, consecutive_failures = 0 WHERE id = ?""")
                        .params(op == Operation.SYNC, id).update();
            } else {
                int failures = jdbc.sql("""
                        UPDATE integration SET connection_status = 'FAILING', last_checked_at = now(), last_error = ?, consecutive_failures = consecutive_failures + 1
                        WHERE id = ? RETURNING consecutive_failures""").params(truncate(o.message(), 500), id).query(Integer.class).single();
                if (failures == props.failingThreshold()) {
                    outbox.append("integration", id.toString(), "integration.failing",
                            Map.of("name", l.view().name(), "failures", failures, "error", truncate(o.message(), 200)));
                }
            }
            Map<String, String> d = new LinkedHashMap<>();
            d.put("runId", runId.toString());
            d.put("trigger", trigger.name());
            d.put("attempts", String.valueOf(tries));
            if (retryOf != null) d.put("retryOf", retryOf.toString());
            audit.record(op == Operation.SYNC ? "integration.synced" : "integration.tested", "integration", id.toString(),
                    o.ok() ? AuditOutcome.SUCCESS : AuditOutcome.FAILURE, d);
        });
        return tx.execute(s -> runs(id, 0, 1).items().getFirst());
    }

    /** Scheduled connection checks of every enabled integration (each organization in its own context). */
    @Scheduled(fixedDelayString = "${ivorovi.integrations.health-check-interval:PT15M}", initialDelayString = "PT2M")
    void healthChecks() {
        if (props.healthCheckInterval().isZero()) return;
        // KI-61: one instance per interval (the lease outlives the interval slightly so a slower peer cannot start late)
        if (!leases.tryAcquire("integrations.health-checks", props.healthCheckInterval().minusSeconds(5))) return;
        runHealthChecks();
    }

    /** One pass over every organization's due integrations (called by the leased scheduler and by tests). */
    public void runHealthChecks() {
        List<UUID> tenants = TenantContext.callAsPlatform(() -> jdbc.sql("SELECT id FROM tenant WHERE status IN ('ACTIVE', 'TRIAL')").query(UUID.class).list());
        for (UUID tenant : tenants) {
            TenantContext.runInTenant(tenant, () -> {
                List<UUID> due = tx.execute(s -> jdbc.sql("""
                        SELECT id FROM integration WHERE enabled AND (last_checked_at IS NULL OR last_checked_at < now() - make_interval(secs => ?)) LIMIT 50""")
                        .param((double) props.healthCheckInterval().toSeconds()).query(UUID.class).list());
                for (UUID id : due) {
                    try {
                        execute(id, Operation.CONNECTION_TEST, Trigger.SCHEDULED, null);
                    } catch (RuntimeException e) {
                        log.warn("Scheduled check of integration {} failed: {}", id, e.getClass().getSimpleName());
                    }
                }
            });
        }
    }

    // ================================================================================================ helpers

    private static final String SELECT = """
            SELECT id, kind, adapter, name, description, config::text AS config, secret_enc IS NOT NULL AS secret_set, enabled, connection_status,
                   last_checked_at, last_success_at, last_sync_at, last_error, consecutive_failures, created_at, updated_at, version FROM integration""";

    private View find(UUID id) {
        return jdbc.sql(SELECT + " WHERE id = ?").param(id).query(this::view).optional().orElseThrow(() -> ApiException.notFound("Integration"));
    }

    private View view(ResultSet rs, int n) throws SQLException {
        String adapter = rs.getString("adapter");
        IntegrationAdapter a = adapters.get(adapter);
        return new View(rs.getObject("id", UUID.class), Kind.valueOf(rs.getString("kind")), adapter, a == null ? adapter : a.info().label(), rs.getString("name"),
                rs.getString("description"), json.readValue(rs.getString("config"), new TypeReference<Map<String, String>>() {}), rs.getBoolean("secret_set"),
                rs.getBoolean("enabled"), rs.getString("connection_status"), ts(rs, "last_checked_at"), ts(rs, "last_success_at"), ts(rs, "last_sync_at"),
                rs.getString("last_error"), rs.getInt("consecutive_failures"), ts(rs, "created_at"), ts(rs, "updated_at"), rs.getLong("version"));
    }

    private static Run run(ResultSet rs, int n) throws SQLException {
        return new Run(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), Operation.valueOf(rs.getString(3)), Trigger.valueOf(rs.getString(4)),
                rs.getString(5), rs.getInt(6), rs.getString(7), (Integer) rs.getObject(8), rs.getObject(9, UUID.class), rs.getString(10),
                rs.getTimestamp(11).toInstant(), rs.getTimestamp(12) == null ? null : rs.getTimestamp(12).toInstant());
    }

    private static Instant ts(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    private IntegrationAdapter adapter(String key) {
        IntegrationAdapter a = key == null ? null : adapters.get(key);
        if (a == null) throw ApiException.badRequest("unknown_adapter", "Unknown adapter.");
        return a;
    }

    /** Only the adapter's declared fields, trimmed and bounded. */
    private static Map<String, String> clean(IntegrationAdapter a, Map<String, String> raw) {
        Map<String, String> m = new TreeMap<>();
        if (raw == null) return m;
        for (IntegrationAdapter.Field f : a.info().fields()) {
            String v = raw.get(f.key());
            if (v != null && !v.isBlank()) {
                if (v.length() > 500) throw ApiException.badRequest("invalid_config", "The value of " + f.label() + " is too long.");
                m.put(f.key(), v.trim());
            }
        }
        return m;
    }

    private static void validate(IntegrationAdapter a, Map<String, String> config, boolean secretPresent) {
        for (IntegrationAdapter.Field f : a.info().fields()) {
            if (f.required() && !config.containsKey(f.key())) throw ApiException.badRequest("invalid_config", f.label() + " is required.");
        }
        if (a.info().secretRequired() && !secretPresent) throw ApiException.badRequest("invalid_config", a.info().secretLabel() + " is required.");
        List<String> errors = a.validate(config, secretPresent);
        if (!errors.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_config", String.join(" ", errors));
    }

    private static String name(String n) {
        if (n == null || n.isBlank() || n.trim().length() > 100) throw ApiException.badRequest("invalid_name", "Give the integration a name (at most 100 characters).");
        return n.trim();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private void require(String permission) {
        if (!authz.has(permission)) throw new AccessDeniedException("Missing permission " + permission);
    }

    private static UUID actor() {
        return CurrentUser.require().userId();
    }
}
