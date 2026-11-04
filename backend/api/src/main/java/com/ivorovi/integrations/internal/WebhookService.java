package com.ivorovi.integrations.internal;

import com.ivorovi.platform.audit.AuditLog;
import com.ivorovi.platform.crypto.FieldCipher;
import com.ivorovi.platform.ids.Ids;
import com.ivorovi.platform.security.Authz;
import com.ivorovi.platform.security.CurrentUser;
import com.ivorovi.platform.security.Permission;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.platform.web.ApiException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Webhook endpoints of an organization: subscription to catalogue event types, HTTPS target (SSRF-checked), signing
 * secret (shown once, rotatable), pause/resume, delivery history with status, failures and replay, and a test ping.
 */
@Service
public class WebhookService {

    static final String READ = Permission.INTEGRATION_READ.key();
    static final String MANAGE = Permission.INTEGRATION_MANAGE.key();
    static final String SECRET_FIELD = "webhook.secret";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Schema(name = "WebhookEndpoint")
    public record View(UUID id, String name, String url, List<String> eventTypes, boolean includeIdentifiers, String status, int consecutiveFailures,
                       Instant lastDeliveryAt, Instant lastSuccessAt, long delivered30Days, long failed30Days, long pending, Instant createdAt, long version) {}

    @Schema(name = "WebhookDelivery")
    public record Delivery(UUID id, UUID endpointId, UUID eventId, String eventType, String status, int attempts, Instant nextAttemptAt, Integer lastStatusCode,
                           String lastError, Integer lastDurationMs, Instant deliveredAt, UUID replayOf, Instant createdAt, String payload) {}

    @Schema(name = "WebhookEndpointCreated")
    public record Created(View endpoint, String signingSecret) {}

    public record Command(String name, String url, List<String> eventTypes, Boolean includeIdentifiers, Long version) {}

    public record Page<T>(List<T> items, long total) {}

    private final JdbcClient jdbc;
    private final Authz authz;
    private final AuditLog audit;
    private final FieldCipher cipher;
    private final OutboundGuard guard;
    private final JsonMapper json;

    WebhookService(JdbcClient jdbc, Authz authz, AuditLog audit, FieldCipher cipher, OutboundGuard guard, JsonMapper json) {
        this.jdbc = jdbc;
        this.authz = authz;
        this.audit = audit;
        this.cipher = cipher;
        this.guard = guard;
        this.json = json;
    }

    public List<WebhookEvents.Type> eventTypes() {
        require(READ);
        return WebhookEvents.CATALOGUE;
    }

    @Transactional(readOnly = true)
    public List<View> list() {
        require(READ);
        return jdbc.sql(SELECT + " ORDER BY lower(e.name)").query(WebhookService::view).list();
    }

    @Transactional(readOnly = true)
    public View get(UUID id) {
        require(READ);
        return find(id);
    }

    @Transactional
    public Created create(Command c) {
        require(MANAGE);
        String url = guard.checkUrl(c.url(), "endpoint URL").toString();
        List<String> types = types(c.eventTypes());
        String secret = newSecret();
        UUID id = Ids.newId();
        UUID tenant = TenantContext.requireTenantId();
        jdbc.sql("""
                INSERT INTO webhook_endpoint (id, tenant_id, name, url, event_types, include_identifiers, secret_enc, created_by)
                VALUES (?, ?, ?, ?, CAST(? AS text[]), ?, ?, ?)""")
                .params(id, tenant, name(c.name()), url, types.toArray(String[]::new), Boolean.TRUE.equals(c.includeIdentifiers()),
                        cipher.encrypt(secret, SECRET_FIELD, tenant), actor()).update();
        audit.recordSuccess("webhook.created", "webhook_endpoint", id.toString(), Map.of("name", name(c.name()), "host", java.net.URI.create(url).getHost(),
                "eventTypes", String.join(",", types), "includeIdentifiers", String.valueOf(Boolean.TRUE.equals(c.includeIdentifiers()))));
        return new Created(find(id), secret);
    }

    @Transactional
    public View update(UUID id, Command c) {
        require(MANAGE);
        View v = find(id);
        String url = c.url() == null ? v.url() : guard.checkUrl(c.url(), "endpoint URL").toString();
        List<String> types = c.eventTypes() == null ? v.eventTypes() : types(c.eventTypes());
        boolean ids = c.includeIdentifiers() == null ? v.includeIdentifiers() : c.includeIdentifiers();
        int n = jdbc.sql("""
                UPDATE webhook_endpoint SET name = ?, url = ?, event_types = CAST(? AS text[]), include_identifiers = ?, updated_at = now(), version = version + 1
                WHERE id = ? AND version = ?""").params(c.name() == null ? v.name() : name(c.name()), url, types.toArray(String[]::new), ids, id,
                c.version() == null ? v.version() : c.version()).update();
        if (n == 0) throw ApiException.conflict("concurrent_modification", "This endpoint was changed by someone else. Reload and try again.");
        audit.recordSuccess("webhook.updated", "webhook_endpoint", id.toString(), Map.of("host", java.net.URI.create(url).getHost(),
                "eventTypes", String.join(",", types), "includeIdentifiers", String.valueOf(ids)));
        return find(id);
    }

    /** ACTIVE (resume, resets the failure counter) or PAUSED. Pending deliveries wait while paused. */
    @Transactional
    public View setStatus(UUID id, String status) {
        require(MANAGE);
        if (!status.equals("ACTIVE") && !status.equals("PAUSED")) throw ApiException.badRequest("invalid_status", "Use ACTIVE or PAUSED.");
        find(id);
        jdbc.sql("UPDATE webhook_endpoint SET status = ?, consecutive_failures = CASE WHEN ? = 'ACTIVE' THEN 0 ELSE consecutive_failures END, "
                + "updated_at = now(), version = version + 1 WHERE id = ?").params(status, status, id).update();
        audit.recordSuccess(status.equals("ACTIVE") ? "webhook.resumed" : "webhook.paused", "webhook_endpoint", id.toString(), Map.of());
        return find(id);
    }

    @Transactional
    public String rotateSecret(UUID id) {
        require(MANAGE);
        find(id);
        String secret = newSecret();
        jdbc.sql("UPDATE webhook_endpoint SET secret_enc = ?, updated_at = now(), version = version + 1 WHERE id = ?")
                .params(cipher.encrypt(secret, SECRET_FIELD, TenantContext.requireTenantId()), id).update();
        audit.recordSuccess("webhook.secret_rotated", "webhook_endpoint", id.toString(), Map.of());
        return secret;
    }

    @Transactional
    public void delete(UUID id) {
        require(MANAGE);
        View v = find(id);
        jdbc.sql("DELETE FROM webhook_endpoint WHERE id = ?").param(id).update();
        audit.recordSuccess("webhook.deleted", "webhook_endpoint", id.toString(), Map.of("name", v.name()));
    }

    @Transactional(readOnly = true)
    public Page<Delivery> deliveries(UUID id, String status, int page, int size) {
        require(READ);
        find(id);
        List<Object> params = new ArrayList<>(List.of(id));
        String filter = "";
        if (status != null && !status.isBlank()) {
            if (!List.of("PENDING", "DELIVERED", "FAILED").contains(status)) throw ApiException.badRequest("invalid_status", "Unknown delivery status.");
            filter = " AND status = ?";
            params.add(status);
        }
        long total = jdbc.sql("SELECT count(*) FROM webhook_delivery WHERE endpoint_id = ?" + filter).params(params).query(Long.class).single();
        params.add(size);
        params.add((long) page * size);
        return new Page<>(jdbc.sql(DELIVERY + " WHERE endpoint_id = ?" + filter + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?").params(params)
                .query(WebhookService::delivery).list(), total);
    }

    @Transactional(readOnly = true)
    public Delivery delivery(UUID endpointId, UUID deliveryId) {
        require(READ);
        return jdbc.sql(DELIVERY + " WHERE endpoint_id = ? AND id = ?").params(endpointId, deliveryId).query(WebhookService::delivery).optional()
                .orElseThrow(() -> ApiException.notFound("Delivery"));
    }

    /** Queues the same payload again as a new delivery (original kept unchanged). */
    @Transactional
    public Delivery replay(UUID endpointId, UUID deliveryId) {
        require(MANAGE);
        Delivery d = delivery(endpointId, deliveryId);
        if ("PENDING".equals(d.status())) throw ApiException.conflict("delivery_pending", "This delivery is still being attempted.");
        UUID id = Ids.newId();
        jdbc.sql("""
                INSERT INTO webhook_delivery (id, tenant_id, endpoint_id, event_id, event_type, payload, replay_of, created_by)
                SELECT ?, tenant_id, endpoint_id, event_id, event_type, payload, id, ? FROM webhook_delivery WHERE id = ?""").params(id, actor(), deliveryId).update();
        audit.recordSuccess("webhook.replayed", "webhook_endpoint", endpointId.toString(), Map.of("deliveryId", deliveryId.toString(), "eventType", d.eventType()));
        return delivery(endpointId, id);
    }

    /** Queues a {@code webhook.ping} delivery (no data) and returns it; the caller dispatches it immediately. */
    @Transactional
    public UUID queuePing(UUID endpointId) {
        require(MANAGE);
        find(endpointId);
        UUID event = Ids.newId(), id = Ids.newId();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", event.toString());
        payload.put("type", WebhookEvents.PING);
        payload.put("occurredAt", Instant.now().toString());
        payload.put("tenantId", TenantContext.requireTenantId().toString());
        payload.put("data", Map.of("endpointId", endpointId.toString()));
        jdbc.sql("""
                INSERT INTO webhook_delivery (id, tenant_id, endpoint_id, event_id, event_type, payload, created_by) VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?)""")
                .params(id, TenantContext.requireTenantId(), endpointId, event, WebhookEvents.PING, json.writeValueAsString(payload), actor()).update();
        audit.recordSuccess("webhook.tested", "webhook_endpoint", endpointId.toString(), Map.of("deliveryId", id.toString()));
        return id;
    }

    // ================================================================================================ helpers

    static String newSecret() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static List<String> types(List<String> requested) {
        if (requested == null || requested.isEmpty()) throw ApiException.badRequest("event_types_required", "Choose at least one event type.");
        List<String> out = requested.stream().distinct().sorted().toList();
        for (String t : out) if (!WebhookEvents.KEYS.contains(t)) throw ApiException.badRequest("invalid_event_type", "Unknown event type " + t + ".");
        return out;
    }

    private static final String SELECT = """
            SELECT e.id, e.name, e.url, e.event_types, e.include_identifiers, e.status, e.consecutive_failures, e.last_delivery_at, e.last_success_at,
                   (SELECT count(*) FROM webhook_delivery d WHERE d.endpoint_id = e.id AND d.status = 'DELIVERED' AND d.created_at >= now() - interval '30 days'),
                   (SELECT count(*) FROM webhook_delivery d WHERE d.endpoint_id = e.id AND d.status = 'FAILED' AND d.created_at >= now() - interval '30 days'),
                   (SELECT count(*) FROM webhook_delivery d WHERE d.endpoint_id = e.id AND d.status = 'PENDING'),
                   e.created_at, e.version
            FROM webhook_endpoint e""";

    private static final String DELIVERY = """
            SELECT id, endpoint_id, event_id, event_type, status, attempts, next_attempt_at, last_status_code, last_error, last_duration_ms, delivered_at, replay_of,
                   created_at, payload::text FROM webhook_delivery""";

    private View find(UUID id) {
        return jdbc.sql(SELECT + " WHERE e.id = ?").param(id).query(WebhookService::view).optional().orElseThrow(() -> ApiException.notFound("Webhook endpoint"));
    }

    private static View view(ResultSet rs, int n) throws SQLException {
        return new View(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), ApiClientService.strings(rs.getArray(4)), rs.getBoolean(5), rs.getString(6),
                rs.getInt(7), ts(rs, 8), ts(rs, 9), rs.getLong(10), rs.getLong(11), rs.getLong(12), ts(rs, 13), rs.getLong(14));
    }

    private static Delivery delivery(ResultSet rs, int n) throws SQLException {
        return new Delivery(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5), rs.getInt(6),
                ts(rs, 7), (Integer) rs.getObject(8), rs.getString(9), (Integer) rs.getObject(10), ts(rs, 11), rs.getObject(12, UUID.class), ts(rs, 13),
                rs.getString(14));
    }

    private static Instant ts(ResultSet rs, int col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    private static String name(String n) {
        if (n == null || n.isBlank() || n.trim().length() > 100) throw ApiException.badRequest("invalid_name", "Give the endpoint a name (at most 100 characters).");
        return n.trim();
    }

    private void require(String permission) {
        if (!authz.has(permission)) throw new AccessDeniedException("Missing permission " + permission);
    }

    private static UUID actor() {
        return CurrentUser.require().userId();
    }
}
