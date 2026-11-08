package com.ivorovi.exams.internal;

import com.ivorovi.exams.CandidateMfa;
import com.ivorovi.platform.audit.AuditLog;
import com.ivorovi.platform.audit.AuditOutcome;
import com.ivorovi.platform.jobs.JobLeases;
import com.ivorovi.platform.process.ProcessProperties;
import com.ivorovi.platform.process.ProcessRole;
import com.ivorovi.platform.tenancy.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * C-1 recovery: after a candidate resets their password, every session of that candidate ends (Keycloak only does so when
 * the candidate ticks "sign out other devices"). Runs in the administrative process on one instance; idempotent, so a
 * failover re-checks the last minutes. Each enforced sign-out is audited in every organization the candidate belongs to.
 * The candidate is notified by the identity provider's security email for the password change.
 */
@Component
public class RecoveryGuard {

    private static final Logger log = LoggerFactory.getLogger(RecoveryGuard.class);
    private final CandidateMfa idp;
    private final JobLeases leases;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final AuditLog audit;
    private final ProcessProperties process;
    private final boolean enabled;
    private volatile Instant watermark = Instant.now().minus(Duration.ofMinutes(10));
    private final Map<String, Instant> handled = new java.util.concurrent.ConcurrentHashMap<>();

    RecoveryGuard(CandidateMfa idp, JobLeases leases, JdbcClient jdbc, TransactionTemplate tx, AuditLog audit, ProcessProperties process,
                  @Value("${ivorovi.exams.recovery-guard-enabled:true}") boolean enabled) {
        this.idp = idp;
        this.leases = leases;
        this.jdbc = jdbc;
        this.tx = tx;
        this.audit = audit;
        this.process = process;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${ivorovi.exams.recovery-guard-interval:PT10S}", initialDelayString = "PT20S")
    void scheduled() {
        if (!enabled || process.role() != ProcessRole.ADMIN) return;
        if (!leases.tryAcquire("exams.recovery-guard", Duration.ofSeconds(9))) return;
        try {
            enforce();
        } catch (RuntimeException e) {
            log.warn("Recovery guard check failed: {}", e.getClass().getSimpleName());
        }
    }

    /** Returns how many candidates were signed out everywhere. */
    public int enforce() {
        Instant from = watermark;
        Instant now = Instant.now();
        List<CandidateMfa.PasswordReset> resets = idp.passwordResetsSince(from);
        int n = 0;
        handled.values().removeIf(t -> t.isBefore(now.minus(Duration.ofHours(2))));
        for (CandidateMfa.PasswordReset r : resets) {
            if (handled.putIfAbsent(r.subject() + "@" + r.at().toEpochMilli(), r.at()) != null) continue;
            if (!r.otherSessionsSignedOut()) {
                // KI-79: sessions that existed before the reset end; the session the reset itself signed in stays
                idp.signOutSessionsBefore(r.subject(), r.at(), r.resetSessionId());
                n++;
            }
            List<UUID> tenants = TenantContext.callAsPlatform(() -> jdbc.sql("SELECT id FROM tenant WHERE status IN ('ACTIVE', 'TRIAL')").query(UUID.class).list());
            for (UUID t : tenants) {
                TenantContext.runInTenant(t, () -> tx.executeWithoutResult(s -> jdbc.sql("SELECT id FROM candidate_account WHERE subject = ?").param(r.subject())
                        .query(UUID.class).optional().ifPresent(id -> audit.record("exam.candidate.recovered", "candidate_account", id.toString(), AuditOutcome.SUCCESS,
                                Map.of("at", r.at().toString(), "sessionsEnded", r.otherSessionsSignedOut() ? "by_candidate" : "enforced")))));
            }
        }
        watermark = now.minus(Duration.ofSeconds(30)); // overlap: events written late are still seen; repeats are harmless
        return n;
    }
}
