package com.ivorovi.notifications;

import com.ivorovi.platform.ids.Ids;
import com.ivorovi.platform.security.CurrentUser;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.platform.web.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Published API: other modules call {@link #notify} (inside their transaction) to inform a user. */
@Service
public class NotificationService {

    public enum Severity { INFO, SUCCESS, WARNING, ERROR }

    public record Notification(UUID id, String category, Severity severity, String title, String body, String link,
                               Instant createdAt, Instant readAt) {}

    public record Page(List<Notification> items, long total, long unread) {}

    private final JdbcClient jdbc;

    NotificationService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Creates a notification for a user of the current tenant. {@code link} must be an in-app path. */
    @Transactional
    public UUID notify(UUID recipientId, String category, Severity severity, String title, String body, String link) {
        UUID id = Ids.newId();
        jdbc.sql("""
                INSERT INTO notification (id, tenant_id, recipient_id, category, severity, title, body, link)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(id, TenantContext.requireTenantId(), recipientId, category, severity.name(), title, body, link)
                .update();
        return id;
    }

    @Transactional(readOnly = true)
    public Page mine(boolean unreadOnly, int page, int size) {
        UUID me = CurrentUser.require().userId();
        String filter = unreadOnly ? " AND read_at IS NULL" : "";
        long total = jdbc.sql("SELECT count(*) FROM notification WHERE recipient_id = ?" + filter).param(me)
                .query(Long.class).single();
        List<Notification> items = jdbc.sql("""
                SELECT id, category, severity, title, body, link, created_at, read_at FROM notification
                WHERE recipient_id = ?""" + filter + " ORDER BY created_at DESC LIMIT ? OFFSET ?")
                .params(me, size, (long) page * size)
                .query((rs, n) -> new Notification(rs.getObject("id", UUID.class), rs.getString("category"),
                        Severity.valueOf(rs.getString("severity")), rs.getString("title"), rs.getString("body"),
                        rs.getString("link"), rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("read_at") == null ? null : rs.getTimestamp("read_at").toInstant()))
                .list();
        return new Page(items, total, unreadCount());
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
    public int markAllRead() {
        return jdbc.sql("UPDATE notification SET read_at = now() WHERE recipient_id = ? AND read_at IS NULL")
                .param(CurrentUser.require().userId()).update();
    }
}
