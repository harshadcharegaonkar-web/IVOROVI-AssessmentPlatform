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
    public static final Set<String> CATEGORIES = Set.of("invitations", "accounts", "access", "students", "imports", "integrations", "approvals", "exams", "system");

    @Schema(name = "Notification")
    public record Notification(UUID id, String category, Severity severity, String title, String body, String link, Instant createdAt, Instant readAt,
                               String eventType, String resourceType, String resourceId, String channel, String deliveryStatus, String emailStatus) {}

    @Schema(name = "NotificationPreference")
    public record Preference(String category, boolean email) {}

    public record Page(List<Notification> items, long total, long unread) {}

    /** What a producer sends: one message, optionally tied to a source event (de-duplicated per recipient). */
    public record Message(String category, Severity severity, String title, String body, String link, UUID sourceEventId, String eventType,
                          String resourceType, String resourceId) {}

    private final JdbcClient jdbc;
    private final com.ivorovi.platform.crypto.FieldCipher cipher;
    private final String appBaseUrl;

    NotificationService(JdbcClient jdbc, com.ivorovi.platform.crypto.FieldCipher cipher,
                        @org.springframework.beans.factory.annotation.Value("${ivorovi.identity.app-base-url:http://localhost:5173}") String appBaseUrl) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.appBaseUrl = appBaseUrl.replaceAll("/+$", "");
    }

    public static final String RECIPIENT_FIELD_NAME = "email_delivery.recipient";
    static final String RECIPIENT_FIELD = RECIPIENT_FIELD_NAME;

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
        if (n == 0) return null;
        // email channel (P2-09): queued in the same transaction when the recipient's preference allows it (default on)
        boolean wanted = jdbc.sql("SELECT email_enabled FROM notification_preference WHERE user_id = ? AND category = ?").params(recipientId, m.category())
                .query(Boolean.class).optional().orElse(true);
        String address = jdbc.sql("SELECT email FROM user_account WHERE id = ? AND status = 'ACTIVE'").param(recipientId).query(String.class).optional().orElse(null);
        if (wanted && address != null && !address.isBlank()) {
            String body = (m.body() == null ? "" : m.body() + "\n\n") + (m.link() == null ? "" : "Open: " + appBaseUrl + m.link() + "\n\n")
                    + "You can change which notifications you receive by email under Notifications → Email preferences.";
            queueEmail(id, recipientId, address, m.category(), "[Ivorovi] " + m.title(), body);
        }
        return id;
    }

    /** Queues an email (e.g. to a candidate, who has no staff account). Sent with retries by the email dispatcher. */
    @Transactional
    public UUID email(String address, String category, String subject, String body) {
        return queueEmail(null, null, address, category, subject, body);
    }

    private UUID queueEmail(UUID notificationId, UUID userId, String address, String category, String subject, String body) {
        UUID id = Ids.newId();
        UUID tenant = TenantContext.requireTenantId();
        jdbc.sql("""
                INSERT INTO email_delivery (id, tenant_id, notification_id, recipient_user_id, recipient_enc, category, subject, body)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""").params(id, tenant, notificationId, userId, cipher.encrypt(address, RECIPIENT_FIELD, tenant), category,
                truncate(subject, 300), body).update();
        return id;
    }

    // ------------------------------------------------------------------------------------------------ preferences

    @Transactional(readOnly = true)
    public List<Preference> preferences() {
        UUID me = CurrentUser.require().userId();
        java.util.Map<String, Boolean> set = new java.util.HashMap<>();
        jdbc.sql("SELECT category, email_enabled FROM notification_preference WHERE user_id = ?").param(me)
                .query(rs -> { set.put(rs.getString(1), rs.getBoolean(2)); });
        return CATEGORIES.stream().sorted().map(c -> new Preference(c, set.getOrDefault(c, true))).toList();
    }

    @Transactional
    public List<Preference> updatePreferences(List<Preference> changes) {
        UUID me = CurrentUser.require().userId();
        for (Preference p : changes) {
            if (!CATEGORIES.contains(p.category())) throw ApiException.badRequest("invalid_category", "Unknown notification category.");
            jdbc.sql("""
                    INSERT INTO notification_preference (tenant_id, user_id, category, email_enabled) VALUES (?, ?, ?, ?)
                    ON CONFLICT (tenant_id, user_id, category) DO UPDATE SET email_enabled = EXCLUDED.email_enabled, updated_at = now()""")
                    .params(TenantContext.requireTenantId(), me, p.category(), p.email()).update();
        }
        return preferences();
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
                SELECT n.id, n.category, n.severity, n.title, n.body, n.link, n.created_at, n.read_at, n.event_type, n.resource_type, n.resource_id, n.channel,
                       n.delivery_status, (SELECT e.status FROM email_delivery e WHERE e.notification_id = n.id ORDER BY e.created_at DESC LIMIT 1) AS email_status
                FROM notification n""" + filter.toString().replace("recipient_id", "n.recipient_id").replace("read_at", "n.read_at").replace("category", "n.category") + " ORDER BY n.created_at DESC, n.id DESC LIMIT ? OFFSET ?")
                .params(p)
                .query((rs, n) -> new Notification(rs.getObject("id", UUID.class), rs.getString("category"),
                        Severity.valueOf(rs.getString("severity")), rs.getString("title"), rs.getString("body"),
                        rs.getString("link"), rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("read_at") == null ? null : rs.getTimestamp("read_at").toInstant(), rs.getString("event_type"),
                        rs.getString("resource_type"), rs.getString("resource_id"), rs.getString("channel"), rs.getString("delivery_status"),
                        rs.getString("email_status")))
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
