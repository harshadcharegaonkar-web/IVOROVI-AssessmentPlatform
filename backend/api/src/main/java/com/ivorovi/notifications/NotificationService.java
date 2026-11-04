package com.ivorovi.notifications;

import com.ivorovi.platform.ids.Ids;
import com.ivorovi.platform.security.CurrentUser;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.platform.web.ApiException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Published API: other modules call {@link #notify} (inside their transaction) to inform a user; the event consumer
 * ({@code NotificationProducer}) creates notifications from domain events. In-app delivery only (no other channel).
 */
@Service
public class NotificationService {

    public enum Severity { INFO, SUCCESS, WARNING, ERROR }

    /** Categories shown as filters in the notification center. */
    public static final Set<String> CATEGORIES = Set.of("invitations", "accounts", "access", "students", "imports", "integrations", "system");

    @Schema(name = "Notification")
    public record Notification(UUID id, String category, Severity severity, String title, String body, String link, Instant createdAt, Instant readAt,
                               String eventType, String resourceType, String resourceId, String channel, String deliveryStatus) {}

    public record Page(List<Notification> items, long total, long unread) {}

    /** What a producer sends: one message, optionally tied to a source event (de-duplicated per recipient). */
    public record Message(String category, Severity severity, String title, String body, String link, UUID sourceEventId, String eventType,
                          String resourceType, String resourceId) {}

    private final JdbcClient jdbc;

    NotificationService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Creates a notification for a user of the current tenant. {@code link} must be an in-app path. */
    @Transactional
    public UUID notify(UUID recipientId, String category, Severity severity, String title, String body, String link) {
        return send(recipientId, new Message(category, severity, title, body, link, null, null, null, null));
    }

    /** Creates the notification unless the recipient already has one for the same source event; returns its id or null. */
    @Transactional
    public UUID send(UUID recipientId, Message m) {
        UUID id = Ids.newId();
        int n = jdbc.sql("""
                INSERT INTO notification (id, tenant_id, recipient_id, category, severity, title, body, link, source_event_id, event_type, resource_type, resource_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, recipient_id, source_event_id) WHERE source_event_id IS NOT NULL DO NOTHING""")
                .params(id, TenantContext.requireTenantId(), recipientId, m.category(), m.severity().name(), truncate(m.title(), 200), truncate(m.body(), 1000),
                        m.link(), m.sourceEventId(), m.eventType(), m.resourceType(), m.resourceId())
                .update();
        return n == 0 ? null : id;
    }

    @Transactional(readOnly = true)
    public Page mine(boolean unreadOnly, int page, int size) {
        return mine(unreadOnly ? "UNREAD" : "ALL", null, page, size);
    }

    /** {@code state}: ALL, UNREAD or READ; {@code category} optional. */
    @Transactional(readOnly = true)
    public Page mine(String state, String category, int page, int size) {
        UUID me = CurrentUser.require().userId();
        StringBuilder filter = new StringBuilder(" WHERE recipient_id = ?");
        List<Object> params = new ArrayList<>(List.of(me));
        if ("UNREAD".equals(state)) filter.append(" AND read_at IS NULL");
        else if ("READ".equals(state)) filter.append(" AND read_at IS NOT NULL");
        if (category != null && !category.isBlank()) {
            if (!CATEGORIES.contains(category)) throw ApiException.badRequest("invalid_category", "Unknown notification category.");
            filter.append(" AND category = ?");
            params.add(category);
        }
        long total = jdbc.sql("SELECT count(*) FROM notification" + filter).params(params).query(Long.class).single();
        List<Object> p = new ArrayList<>(params);
        p.add(size);
        p.add((long) page * size);
        List<Notification> items = jdbc.sql("""
                SELECT id, category, severity, title, body, link, created_at, read_at, event_type, resource_type, resource_id, channel, delivery_status
                FROM notification""" + filter + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?")
                .params(p)
                .query((rs, n) -> new Notification(rs.getObject("id", UUID.class), rs.getString("category"),
                        Severity.valueOf(rs.getString("severity")), rs.getString("title"), rs.getString("body"),
                        rs.getString("link"), rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("read_at") == null ? null : rs.getTimestamp("read_at").toInstant(), rs.getString("event_type"),
                        rs.getString("resource_type"), rs.getString("resource_id"), rs.getString("channel"), rs.getString("delivery_status")))
                .list();
        return new Page(items, total, unreadCount());
    }

    /** Unread count per category (for the filter chips). */
    @Transactional(readOnly = true)
    public Map<String, Long> unreadByCategory() {
        Map<String, Long> m = new java.util.TreeMap<>();
        jdbc.sql("SELECT category, count(*) FROM notification WHERE recipient_id = ? AND read_at IS NULL GROUP BY category")
                .param(CurrentUser.require().userId()).query(rs -> { m.put(rs.getString(1), rs.getLong(2)); });
        return m;
    }

    @Transactional(readOnly = true)
    public long unreadCount() {
        return jdbc.sql("SELECT count(*) FROM notification WHERE recipient_id = ? AND read_at IS NULL")
                .param(CurrentUser.require().userId()).query(Long.class).single();
    }

    @Transactional
    public void markRead(UUID id) {
        int n = jdbc.sql("UPDATE notification SET read_at = coalesce(read_at, now()) WHERE id = ? AND recipient_id = ?")
                .params(id, CurrentUser.require().userId()).update();
        if (n == 0) throw ApiException.notFound("Notification");
    }

    @Transactional
    public void markUnread(UUID id) {
        int n = jdbc.sql("UPDATE notification SET read_at = NULL WHERE id = ? AND recipient_id = ?")
                .params(id, CurrentUser.require().userId()).update();
        if (n == 0) throw ApiException.notFound("Notification");
    }

    @Transactional
    public int markAllRead() {
        return jdbc.sql("UPDATE notification SET read_at = now() WHERE recipient_id = ? AND read_at IS NULL")
                .param(CurrentUser.require().userId()).update();
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
