package com.ivorovi.platform.kernel;

import static com.ivorovi.support.Fixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ivorovi.notifications.NotificationService;
import com.ivorovi.platform.retention.RetentionJob;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.support.Containers;
import com.ivorovi.support.Fixture;
import com.ivorovi.support.IntegrationTest;
import com.ivorovi.support.TestTokens;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/** Increment 1.0 AC5 (retention) and the notification centre API. */
class RetentionAndNotificationsIT extends IntegrationTest {

    @Autowired RetentionJob retention;
    @Autowired NotificationService notifications;
    @Autowired TransactionTemplate tx;

    private static UUID outbox(String publishedAgo, String createdAgo) {
        UUID id = UUID.randomUUID();
        ownerJdbc().sql("""
                INSERT INTO outbox_event (id, tenant_id, aggregate_type, aggregate_id, event_type, payload, created_at, published_at)
                VALUES (?, ?, 'test', 'x', 'test.retention', '{}'::jsonb, now() - CAST(? AS interval),
                        CASE WHEN CAST(? AS text) IS NULL THEN NULL ELSE now() - CAST(? AS interval) END)""")
                .params(id, TENANT_A, createdAgo, publishedAgo, publishedAgo).update();
        return id;
    }

    private static boolean exists(UUID id) {
        return ownerJdbc().sql("SELECT count(*) FROM outbox_event WHERE id = ?").param(id).query(Long.class).single() == 1;
    }

    @Test
    void retentionDeletesOnlyOldPublishedEventsAndExpiredIdempotencyRecords() {
        UUID oldPublished = outbox("8 days", "8 days");
        UUID recentPublished = outbox("1 day", "1 day");
        UUID oldUnpublished = outbox(null, "30 days");
        ownerJdbc().sql("""
                INSERT INTO idempotency_record (tenant_id, user_account_id, idem_key, method, path, request_hash, status, expires_at)
                SELECT tenant_id, id, 'expired-key-001', 'POST', '/x', repeat('0',64), 'COMPLETED', now() - interval '1 minute'
                FROM user_account WHERE email = 'alice.admin@aurora.test'""").update();

        retention.run();

        assertThat(exists(oldPublished)).isFalse();
        assertThat(exists(recentPublished)).isTrue();
        assertThat(exists(oldUnpublished)).as("unpublished events are never deleted").isTrue();
        assertThat(ownerJdbc().sql("SELECT count(*) FROM idempotency_record WHERE idem_key = 'expired-key-001'")
                .query(Long.class).single()).isZero();
        ownerJdbc().sql("DELETE FROM outbox_event WHERE event_type = 'test.retention'").update();
    }

    @Test
    void tenantContextCannotDeleteOutboxEvents() throws Exception {
        UUID oldPublished = outbox("8 days", "8 days");
        try (var c = java.sql.DriverManager.getConnection(Containers.jdbcUrl(), "ivorovi_app", Containers.APP_PASSWORD);
             var st = c.createStatement()) {
            st.execute("SELECT set_config('app.tenant_id', '" + TENANT_A + "', false)");
            assertThat(st.executeUpdate("DELETE FROM outbox_event")).isZero();
        }
        assertThat(exists(oldPublished)).isTrue();
        ownerJdbc().sql("DELETE FROM outbox_event WHERE event_type = 'test.retention'").update();
    }

    private ResultActions as(Fixture.Caller c, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder r) throws Exception {
        return mvc.perform(r.header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.forCaller(c)));
    }

    @Test
    void notificationsAreVisibleOnlyToTheirRecipient() throws Exception {
        UUID alice = UUID.fromString("0192a000-0000-7000-8000-0000000a1001");
        UUID id = TenantContext.callInTenant(TENANT_A, () -> tx.execute(s -> notifications.notify(alice, "test",
                NotificationService.Severity.SUCCESS, "Import finished", "120 students imported", "/students")));

        as(ALICE_ORG_ADMIN_A, get("/api/v1/notifications")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id == '" + id + "')].title").value("Import finished"));
        long unread = Long.parseLong(as(ALICE_ORG_ADMIN_A, get("/api/v1/notifications/unread-count"))
                .andReturn().getResponse().getContentAsString().replaceAll("\\D", ""));
        assertThat(unread).isPositive();

        as(ARUN_FACULTY_A1, get("/api/v1/notifications")).andExpect(jsonPath("$.items[?(@.id == '" + id + "')]").isEmpty());
        as(ARUN_FACULTY_A1, post("/api/v1/notifications/" + id + "/read")).andExpect(status().isNotFound());
        as(BELA_ORG_ADMIN_B, post("/api/v1/notifications/" + id + "/read")).andExpect(status().isNotFound());

        as(ALICE_ORG_ADMIN_A, post("/api/v1/notifications/" + id + "/read")).andExpect(status().isNoContent());
        as(ALICE_ORG_ADMIN_A, post("/api/v1/notifications/read-all")).andExpect(status().isOk());
        as(ALICE_ORG_ADMIN_A, get("/api/v1/notifications/unread-count")).andExpect(jsonPath("$.unread").value(0));
    }
}
