package com.ivorovi.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ivorovi.notifications.NotificationService;
import com.ivorovi.notifications.internal.EmailDispatcher;
import com.ivorovi.platform.jobs.JobLeases;
import com.ivorovi.platform.outbox.OutboxPublisher;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.support.IntegrationTest;
import com.ivorovi.support.RecordingEmailSender;
import com.ivorovi.support.TestOrg;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** Increment 2.0-E: job leases across instances, parallel outbox drain under bulk load, email retries and failure records. */
class ReliabilityIT extends IntegrationTest {

    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired OutboxPublisher publisher;
    @Autowired RabbitAdmin admin;
    @Autowired TopicExchange exchange;
    @Autowired NotificationService notifications;
    @Autowired EmailDispatcher dispatcher;
    @Autowired RecordingEmailSender mail;

    // ------------------------------------------------------------------------------------------------ job leases

    @Test
    void exactlyOneOfThreeInstancesHoldsALeaseUntilItExpiresOrIsReleased() throws Exception {
        String job = "test.lease." + UUID.randomUUID();
        List<JobLeases> instances = List.of(new JobLeases(jdbc, tx, "api-1"), new JobLeases(jdbc, tx, "api-2"), new JobLeases(jdbc, tx, "api-3"));
        ExecutorService pool = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> attempts = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            JobLeases l = instances.get(i % 3);
            attempts.add(pool.submit(() -> {
                start.await();
                return l.tryAcquire(job, Duration.ofSeconds(30)) ? l.owner() : null;
            }));
        }
        start.countDown();
        java.util.Set<String> winners = new java.util.HashSet<>();
        for (Future<String> f : attempts) if (f.get() != null) winners.add(f.get());
        pool.shutdown();
        assertThat(winners).hasSize(1);                         // one instance runs the job; it may renew its own lease
        String holder = winners.iterator().next();
        JobLeases other = instances.stream().filter(l -> !l.owner().equals(holder)).findFirst().orElseThrow();
        assertThat(other.tryAcquire(job, Duration.ofSeconds(30))).isFalse();
        // released (graceful shutdown) → another instance takes over immediately
        instances.stream().filter(l -> l.owner().equals(holder)).findFirst().orElseThrow().release(job);
        assertThat(other.tryAcquire(job, Duration.ofSeconds(1))).isTrue();
        // expired (crashed holder) → taken over after the period
        Thread.sleep(1200);
        JobLeases third = instances.stream().filter(l -> !l.owner().equals(holder) && !l.owner().equals(other.owner())).findFirst().orElseThrow();
        assertThat(third.tryAcquire(job, Duration.ofSeconds(30))).isTrue();
    }

    // ------------------------------------------------------------------------------------------------ outbox

    @Test
    void theParallelPublisherDrainsABulkBacklogExactlyOnce() {
        // earlier suites leave unpublished events; publish them first so the measurement covers only this backlog
        publisher.drain(Duration.ofSeconds(30));
        String queue = "test.bulk." + UUID.randomUUID();
        var q = new org.springframework.amqp.core.Queue(queue, true, false, false);
        admin.declareQueue(q);
        admin.declareBinding(BindingBuilder.bind(q).to(exchange).with("test.bulk.#"));
        try {
            int n = 20_000;
            TestOrg org = new TestOrg(ownerJdbc(), mvc, "bulk");
            // a bulk operation: one statement, n events (as an import or bulk update would append them)
            ownerJdbc().sql("""
                    INSERT INTO outbox_event (id, tenant_id, aggregate_type, aggregate_id, event_type, payload)
                    SELECT gen_random_uuid(), ?, 'test', g::text, 'test.bulk.created', jsonb_build_object('n', g) FROM generate_series(1, ?) g""")
                    .params(org.tenant, n).update();
            long t = System.nanoTime();
            int published = publisher.drain(Duration.ofSeconds(120));
            double seconds = (System.nanoTime() - t) / 1e9;
            System.out.printf("outbox bulk drain: %d events in %.2f s = %.0f events/s%n", published, seconds, published / seconds);
            assertThat(published).isGreaterThanOrEqualTo(n);
            assertThat(ownerJdbc().sql("SELECT count(*) FROM outbox_event WHERE tenant_id = ? AND published_at IS NULL").param(org.tenant).query(Long.class).single())
                    .isZero();
            long delivered = 0;
            for (int i = 0; i < 50 && delivered < n; i++) {
                delivered = admin.getQueueInfo(queue).getMessageCount();
                if (delivered < n) sleep(100);
            }
            assertThat(delivered).isEqualTo(n);                // confirmed, nothing lost, nothing duplicated in a clean run
        } finally {
            admin.deleteQueue(queue);
        }
    }

    // ------------------------------------------------------------------------------------------------ email channel

    @Test
    void emailsFollowPreferencesAreRetriedAndFailuresAreRecordedWithoutAffectingInAppNotifications() throws Exception {
        TestOrg org = new TestOrg(ownerJdbc(), mvc, "email");
        TestOrg.Account user = org.account("ORG_ADMIN", null);
        // preferences through the API: approvals by email off
        mvc.perform(put("/api/v1/notifications/preferences").header(HttpHeaders.AUTHORIZATION, user.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("[{\"category\":\"approvals\",\"email\":false}]")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/notifications/preferences").header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(jsonPath("$[?(@.category=='approvals')].email").value(false));
        TenantContext.runInTenant(org.tenant, () -> {
            notifications.notify(user.id(), "approvals", NotificationService.Severity.INFO, "Approval needed", "x", "/approvals");
            notifications.notify(user.id(), "students", NotificationService.Severity.INFO, "Student added", "y", "/students");
        });
        assertThat(ownerJdbc().sql("SELECT count(*) FROM notification WHERE tenant_id = ?").param(org.tenant).query(Long.class).single()).isEqualTo(2);
        List<String> queued = ownerJdbc().sql("SELECT subject FROM email_delivery WHERE tenant_id = ?").param(org.tenant).query(String.class).list();
        assertThat(queued).containsExactly("[Ivorovi] Student added");
        // the address is stored encrypted
        assertThat(ownerJdbc().sql("SELECT recipient_enc FROM email_delivery WHERE tenant_id = ?").param(org.tenant).query(String.class).single())
                .startsWith("v1:").doesNotContain("@org.test");

        // relay down: retried with backoff, error recorded
        mail.failNext.set(100);
        assertThat(dispatcher.dispatch(org.tenant, 10)).isZero();
        var row = ownerJdbc().sql("SELECT status, attempts, last_error, next_attempt_at > now() + interval '50 seconds' AS later FROM email_delivery WHERE tenant_id = ?")
                .param(org.tenant).query((rs, i) -> new Object[] {rs.getString(1), rs.getInt(2), rs.getString(3), rs.getBoolean(4)}).single();
        assertThat(row).containsExactly("PENDING", 1, "MailSendException", true);
        // not due yet → not retried early
        assertThat(dispatcher.dispatch(org.tenant, 10)).isZero();
        assertThat(ownerJdbc().sql("SELECT attempts FROM email_delivery WHERE tenant_id = ?").param(org.tenant).query(Integer.class).single()).isEqualTo(1);
        // keep failing until the last attempt → FAILED
        for (int i = 0; i < 6; i++) {
            ownerJdbc().sql("UPDATE email_delivery SET next_attempt_at = now() WHERE tenant_id = ? AND status = 'PENDING'").param(org.tenant).update();
            dispatcher.dispatch(org.tenant, 10);
        }
        assertThat(ownerJdbc().sql("SELECT status || ':' || attempts FROM email_delivery WHERE tenant_id = ?").param(org.tenant).query(String.class).single())
                .isEqualTo("FAILED:6");
        // in-app notifications are untouched, and show the email status
        mvc.perform(get("/api/v1/notifications").header(HttpHeaders.AUTHORIZATION, user.bearer())).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2)).andExpect(jsonPath("$.items[?(@.title=='Student added')].emailStatus").value("FAILED"));

        // relay back: a new email is sent on the first attempt
        mail.failNext.set(0);
        TenantContext.runInTenant(org.tenant, () -> notifications.notify(user.id(), "students", NotificationService.Severity.INFO, "Another", "z", null));
        assertThat(dispatcher.dispatch(org.tenant, 10)).isEqualTo(1);
        assertThat(ownerJdbc().sql("SELECT count(*) FROM email_delivery WHERE tenant_id = ? AND status = 'SENT' AND sent_at IS NOT NULL").param(org.tenant)
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void twoDispatchersNeverSendTheSameEmailTwice() throws Exception {
        TestOrg org = new TestOrg(ownerJdbc(), mvc, "email2");
        TestOrg.Account user = org.account("ORG_ADMIN", null);
        TenantContext.runInTenant(org.tenant, () -> {
            for (int i = 0; i < 40; i++) notifications.notify(user.id(), "students", NotificationService.Severity.INFO, "N" + i, "b", null);
        });
        int before = (int) mail.sent.stream().filter(m -> m.to().equals(user.id() + "@org.test")).count();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        AtomicInteger sent = new AtomicInteger();
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 4; i++) fs.add(pool.submit(() -> sent.addAndGet(dispatcher.dispatch(org.tenant, 40))));
        for (Future<?> f : fs) f.get();
        pool.shutdown();
        assertThat(sent.get()).isEqualTo(40);
        assertThat(mail.sent.stream().filter(m -> m.to().equals(user.id() + "@org.test")).count() - before).isEqualTo(40);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
