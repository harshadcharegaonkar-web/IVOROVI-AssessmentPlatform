package com.ivorovi.notifications.internal;

import com.ivorovi.platform.crypto.FieldCipher;
import com.ivorovi.platform.jobs.JobLeases;
import com.ivorovi.platform.mail.EmailMessage;
import com.ivorovi.platform.mail.EmailSender;
import com.ivorovi.platform.tenancy.TenantContext;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends queued emails (P2-09): due deliveries are claimed with {@code FOR UPDATE SKIP LOCKED}, sent outside any
 * transaction, and recorded. A failure is retried with backoff (1, 5, 15, 60 min, then hourly) up to {@value #MAX_ATTEMPTS}
 * attempts, then marked FAILED with the error class. In-app notifications never depend on email.
 */
@Component
public class EmailDispatcher {

    static final int MAX_ATTEMPTS = 6;
    private static final long[] BACKOFF_MINUTES = {1, 5, 15, 60, 60};
    private static final Logger log = LoggerFactory.getLogger(EmailDispatcher.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final EmailSender mail;
    private final FieldCipher cipher;
    private final JobLeases leases;
    private final boolean enabled;

    EmailDispatcher(JdbcClient jdbc, TransactionTemplate tx, EmailSender mail, FieldCipher cipher, JobLeases leases,
                    @org.springframework.beans.factory.annotation.Value("${ivorovi.notifications.email-dispatcher-enabled:true}") boolean enabled) {
        this.enabled = enabled;
        this.jdbc = jdbc;
        this.tx = tx;
        this.mail = mail;
        this.cipher = cipher;
        this.leases = leases;
    }

    @Scheduled(fixedDelayString = "${ivorovi.notifications.email-dispatch-interval:PT10S}", initialDelayString = "PT20S")
    void scheduled() {
        if (!enabled) return;
        if (!leases.tryAcquire("notifications.email-dispatch", Duration.ofSeconds(30))) return;
        List<UUID> tenants = TenantContext.callAsPlatform(() -> jdbc.sql("SELECT id FROM tenant WHERE status IN ('ACTIVE', 'TRIAL')").query(UUID.class).list());
        for (UUID t : tenants) {
            try {
                dispatch(t, 100);
            } catch (RuntimeException e) {
                log.warn("Email dispatch for an organization failed: {}", e.getClass().getSimpleName());
            }
        }
    }

    record Due(UUID id, String recipientEnc, String subject, String body, int attempts) {}

    /** Sends up to {@code limit} due emails of one organization; returns how many were sent. */
    public int dispatch(UUID tenant, int limit) {
        List<Due> due = TenantContext.callInTenant(tenant, () -> tx.execute(s -> {
            List<Due> d = jdbc.sql("""
                    SELECT id, recipient_enc, subject, body, attempts FROM email_delivery WHERE status = 'PENDING' AND next_attempt_at <= now()
                    ORDER BY next_attempt_at LIMIT ? FOR UPDATE SKIP LOCKED""").param(limit)
                    .query((rs, n) -> new Due(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5))).list();
            for (Due x : d) jdbc.sql("UPDATE email_delivery SET next_attempt_at = now() + interval '2 minutes' WHERE id = ?").param(x.id()).update();  // lease
            return d;
        }));
        int sent = 0;
        for (Due d : due == null ? List.<Due>of() : due) {
            String error = null;
            try {
                String to = cipher.decrypt(d.recipientEnc(), com.ivorovi.notifications.NotificationService.RECIPIENT_FIELD_NAME, tenant);
                mail.send(new EmailMessage(to, d.subject(), d.body()));
            } catch (RuntimeException e) {
                error = e.getClass().getSimpleName();
            }
            String err = error;
            TenantContext.runInTenant(tenant, () -> tx.executeWithoutResult(s -> {
                if (err == null) {
                    jdbc.sql("UPDATE email_delivery SET status = 'SENT', attempts = attempts + 1, sent_at = now(), last_error = NULL WHERE id = ?").param(d.id()).update();
                } else {
                    int attempts = d.attempts() + 1;
                    boolean last = attempts >= MAX_ATTEMPTS;
                    long minutes = BACKOFF_MINUTES[Math.min(attempts - 1, BACKOFF_MINUTES.length - 1)];
                    jdbc.sql("""
                            UPDATE email_delivery SET status = ?, attempts = ?, last_error = ?, next_attempt_at = now() + make_interval(mins => ?) WHERE id = ?""")
                            .params(last ? "FAILED" : "PENDING", attempts, err, (int) minutes, d.id()).update();
                }
            }));
            if (error == null) sent++;
            else log.warn("Email {} not sent (attempt {}): {}", d.id(), d.attempts() + 1, error);
        }
        return sent;
    }
}
