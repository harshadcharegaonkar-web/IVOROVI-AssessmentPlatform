package com.ivorovi.integrations.internal;

import com.ivorovi.platform.audit.AuditLog;
import com.ivorovi.platform.audit.AuditOutcome;
import com.ivorovi.platform.crypto.FieldCipher;
import com.ivorovi.platform.outbox.Outbox;
import com.ivorovi.platform.tenancy.TenantContext;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Delivers pending webhooks: HTTPS POST of the stored payload, signed with the endpoint secret, 5-second timeout, no
 * redirects, target re-checked against the SSRF guard on every attempt. Non-2xx answers and errors are retried with
 * exponential backoff up to the attempt limit; consecutive failures disable the endpoint automatically (and notify the
 * integration managers). Deliveries are claimed with a short lease ({@code FOR UPDATE SKIP LOCKED}) so several
 * instances can dispatch safely; a bounded thread pool keeps slow receivers away from the request threads.
 */
@Component
public class WebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);
    private static final Duration LEASE = Duration.ofMinutes(2);
    private static final Duration MAX_BACKOFF = Duration.ofHours(1);

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final FieldCipher cipher;
    private final OutboundGuard guard;
    private final IntegrationsProperties props;
    private final AuditLog audit;
    private final Outbox outbox;
    private final HttpClient http;
    private final ExecutorService executor;

    WebhookDispatcher(JdbcClient jdbc, TransactionTemplate tx, FieldCipher cipher, OutboundGuard guard, IntegrationsProperties props, AuditLog audit, Outbox outbox) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.cipher = cipher;
        this.guard = guard;
        this.props = props;
        this.audit = audit;
        this.outbox = outbox;
        this.http = HttpClient.newBuilder().connectTimeout(props.requestTimeout()).followRedirects(HttpClient.Redirect.NEVER).build();
        this.executor = Executors.newFixedThreadPool(props.webhooks().concurrency(), Thread.ofPlatform().name("webhook-", 0).daemon().factory());
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    @Scheduled(fixedDelayString = "${ivorovi.integrations.webhooks.dispatch-interval:PT5S}", initialDelayString = "PT15S")
    void scheduled() {
        if (!props.webhooks().dispatcherEnabled()) return;
        List<UUID> tenants = TenantContext.callAsPlatform(() -> jdbc.sql("SELECT id FROM tenant WHERE status IN ('ACTIVE', 'TRIAL')").query(UUID.class).list());
        for (UUID t : tenants) {
            try {
                // keep draining while batches come back full (bounded per poll so one organization cannot starve the others)
                for (int round = 0; round < 20 && run(t, 50).claimed() == 50; round++) { }
            } catch (RuntimeException e) {
                log.warn("Webhook dispatch for an organization failed: {}", e.getClass().getSimpleName());
            }
        }
    }

    record Claimed(UUID id, UUID endpointId, String url, String secretEnc, String payload, int attempts, String eventType) {}

    /** Claims and delivers up to {@code limit} due deliveries of one organization; waits for them. Returns how many succeeded. */
    public int dispatch(UUID tenant, int limit) {
        return run(tenant, limit).delivered();
    }

    private record Outcome(int claimed, int delivered) {}

    private Outcome run(UUID tenant, int limit) {
        List<Claimed> batch = TenantContext.callInTenant(tenant, () -> tx.execute(s -> {
            List<Claimed> c = jdbc.sql("""
                    SELECT d.id, d.endpoint_id, e.url, e.secret_enc, d.payload::text, d.attempts, d.event_type
                    FROM webhook_delivery d JOIN webhook_endpoint e ON e.id = d.endpoint_id
                    WHERE d.status = 'PENDING' AND d.next_attempt_at <= now() AND e.status = 'ACTIVE'
                    ORDER BY d.next_attempt_at LIMIT ? FOR UPDATE OF d SKIP LOCKED""").param(limit)
                    .query((rs, n) -> new Claimed(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getString(5),
                            rs.getInt(6), rs.getString(7))).list();
            for (Claimed d : c) {
                jdbc.sql("UPDATE webhook_delivery SET next_attempt_at = now() + make_interval(secs => ?) WHERE id = ?").params((double) LEASE.toSeconds(), d.id()).update();
            }
            return c;
        }));
        if (batch == null || batch.isEmpty()) return new Outcome(0, 0);
        List<Callable<Boolean>> work = new ArrayList<>();
        for (Claimed d : batch) work.add(() -> TenantContext.callInTenant(tenant, () -> attempt(tenant, d)));
        int ok = 0;
        try {
            for (var f : executor.invokeAll(work, props.requestTimeout().multipliedBy(4).toMillis() * batch.size(), TimeUnit.MILLISECONDS)) {
                try {
                    if (Boolean.TRUE.equals(f.get())) ok++;
                } catch (Exception e) {
                    log.warn("Webhook delivery task failed: {}", e.getClass().getSimpleName());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return new Outcome(batch.size(), ok);
    }

    /** Delivers one pending delivery now (test ping); returns whether it succeeded. Runs on the bounded pool. */
    public boolean dispatchOne(UUID tenant, UUID deliveryId) {
        Claimed d = TenantContext.callInTenant(tenant, () -> tx.execute(s -> {
            // claim with a lease so the scheduled dispatcher does not send it a second time
            int claimed = jdbc.sql("UPDATE webhook_delivery SET next_attempt_at = now() + make_interval(secs => ?) WHERE id = ? AND status = 'PENDING' "
                    + "AND next_attempt_at <= now()").params((double) LEASE.toSeconds(), deliveryId).update();
            if (claimed == 0) return null;
            return jdbc.sql("""
                    SELECT d.id, d.endpoint_id, e.url, e.secret_enc, d.payload::text, d.attempts, d.event_type
                    FROM webhook_delivery d JOIN webhook_endpoint e ON e.id = d.endpoint_id WHERE d.id = ?""")
                    .param(deliveryId).query((rs, n) -> new Claimed(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getInt(6), rs.getString(7))).single();
        }));
        if (d == null) return false;
        try {
            return executor.submit(() -> TenantContext.callInTenant(tenant, () -> attempt(tenant, d))).get(props.requestTimeout().multipliedBy(3).toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean attempt(UUID tenant, Claimed d) {
        long started = System.nanoTime();
        Integer status = null;
        String error = null;
        try {
            URI target = guard.checkUrl(d.url(), "endpoint URL");
            String secret = cipher.decrypt(d.secretEnc(), WebhookService.SECRET_FIELD, tenant);
            long ts = Instant.now().getEpochSecond();
            HttpRequest request = HttpRequest.newBuilder(target).timeout(props.requestTimeout())
                    .header("Content-Type", "application/json").header("User-Agent", "Ivorovi-Webhooks/1.0")
                    .header(WebhookSigner.HEADER, WebhookSigner.sign(secret, ts, d.payload()))
                    .header("Ivorovi-Event-Type", d.eventType()).header("Ivorovi-Delivery-Id", d.id().toString())
                    .POST(HttpRequest.BodyPublishers.ofString(d.payload())).build();
            HttpResponse<Void> res = http.send(request, HttpResponse.BodyHandlers.discarding());
            status = res.statusCode();
            if (status < 200 || status >= 300) error = "HTTP " + status;
        } catch (com.ivorovi.platform.web.ApiException e) {
            error = e.getMessage();
        } catch (java.net.http.HttpTimeoutException e) {
            error = "Timed out after " + props.requestTimeout().toSeconds() + " s";
        } catch (java.io.IOException e) {
            error = "Connection failed: " + e.getClass().getSimpleName();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error = "Interrupted";
        } catch (RuntimeException e) {
            error = "Unexpected error: " + e.getClass().getSimpleName();
        }
        int ms = (int) ((System.nanoTime() - started) / 1_000_000);
        boolean ok = error == null;
        Integer code = status;
        String err = error == null ? null : error.length() > 300 ? error.substring(0, 300) : error;
        tx.executeWithoutResult(s -> record(d, ok, code, err, ms));
        return ok;
    }

    private void record(Claimed d, boolean ok, Integer status, String error, int ms) {
        int attempts = d.attempts() + 1;
        if (ok) {
            jdbc.sql("""
                    UPDATE webhook_delivery SET status = 'DELIVERED', attempts = ?, last_status_code = ?, last_error = NULL, last_duration_ms = ?, delivered_at = now()
                    WHERE id = ?""").params(attempts, status, ms, d.id()).update();
            jdbc.sql("UPDATE webhook_endpoint SET consecutive_failures = 0, last_delivery_at = now(), last_success_at = now() WHERE id = ?").param(d.endpointId()).update();
            return;
        }
        boolean last = attempts >= props.webhooks().maxAttempts();
        long backoff = Math.min(MAX_BACKOFF.toSeconds(), props.webhooks().backoffBase().toSeconds() * (1L << Math.min(attempts - 1, 20)));
        jdbc.sql("""
                UPDATE webhook_delivery SET status = ?, attempts = ?, last_status_code = ?, last_error = ?, last_duration_ms = ?,
                    next_attempt_at = now() + make_interval(secs => ?) WHERE id = ?""")
                .params(last ? "FAILED" : "PENDING", attempts, status, error, ms, (double) Math.max(backoff, 1), d.id()).update();
        record E(int failures, String status, String name) {}
        E e = jdbc.sql("""
                UPDATE webhook_endpoint SET consecutive_failures = consecutive_failures + 1, last_delivery_at = now() WHERE id = ?
                RETURNING consecutive_failures, status, name""").param(d.endpointId()).query((rs, n) -> new E(rs.getInt(1), rs.getString(2), rs.getString(3))).single();
        if (e.failures() >= props.webhooks().disableAfter() && "ACTIVE".equals(e.status())) {
            jdbc.sql("UPDATE webhook_endpoint SET status = 'AUTO_DISABLED', updated_at = now(), version = version + 1 WHERE id = ?").param(d.endpointId()).update();
            audit.record("webhook.auto_disabled", "webhook_endpoint", d.endpointId().toString(), AuditOutcome.FAILURE,
                    Map.of("consecutiveFailures", String.valueOf(e.failures())));
            outbox.append("webhook_endpoint", d.endpointId().toString(), "webhook.endpoint.disabled", Map.of("name", e.name(), "failures", e.failures()));
        }
    }
}
