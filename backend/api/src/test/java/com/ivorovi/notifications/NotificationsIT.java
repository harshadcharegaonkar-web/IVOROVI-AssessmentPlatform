package com.ivorovi.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ivorovi.notifications.internal.NotificationProducer;
import com.ivorovi.platform.outbox.IntegrationEvent;
import com.ivorovi.support.IntegrationTest;
import com.ivorovi.support.TestOrg;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Event-driven notifications: who is informed, never the actor, idempotent, tenant-isolated, read/unread and history. */
class NotificationsIT extends IntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    NotificationProducer producer;

    private TestOrg org;
    private TestOrg.Account admin, admin2, user;
    private UUID college;

    @BeforeEach
    void setUp() {
        org = new TestOrg(ownerJdbc(), mvc, "ntf");
        college = org.unit("COLLEGE", null, "C");
        admin = org.account("ORG_ADMIN", null);
        admin2 = org.account("ORG_ADMIN", null);
        user = org.account("FACULTY", college);
    }

    private int drain(Instant since) {
        int n = 0;
        for (IntegrationEvent e : org.events(since)) n += producer.handle(e);
        return n;
    }

    private JsonNode mine(TestOrg.Account who, String query) throws Exception {
        return JSON.readTree(mvc.perform(get("/api/v1/notifications" + query).header(HttpHeaders.AUTHORIZATION, who.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private UUID roleId(String code) {
        return ownerJdbc().sql("SELECT id FROM role WHERE code = ? AND tenant_id IS NULL").param(code).query(UUID.class).single();
    }

    @Test
    void roleChangesNotifyTheUserButNeverTheActor() throws Exception {
        Instant since = Instant.now().minusSeconds(1);
        mvc.perform(post("/api/v1/users/" + user.id() + "/role-assignments").header(HttpHeaders.AUTHORIZATION, admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"roleId\":\"" + roleId("EVALUATOR") + "\",\"orgUnitId\":\"" + college + "\"}"))
                .andExpect(status().is2xxSuccessful());
        assertThat(drain(since)).isEqualTo(1);
        JsonNode n = mine(user, "");
        assertThat(n.path("unread").asLong()).isEqualTo(1);
        JsonNode item = n.path("items").get(0);
        assertThat(item.path("category").asString()).isEqualTo("access");
        assertThat(item.path("title").asString()).isEqualTo("Role assigned");
        assertThat(item.path("body").asString()).contains("Unit C");
        assertThat(item.path("link").asString()).isEqualTo("/me");
        assertThat(item.path("eventType").asString()).isEqualTo("role.assigned");
        assertThat(item.path("channel").asString()).isEqualTo("IN_APP");
        assertThat(item.path("deliveryStatus").asString()).isEqualTo("DELIVERED");
        assertThat(mine(admin, "").path("totalElements").asLong()).isZero();

        // redelivery of the same events creates nothing new
        assertThat(drain(since)).isZero();
    }

    @Test
    void accountStatusChangesNotifyOtherAdministrators() throws Exception {
        Instant since = Instant.now().minusSeconds(1);
        long version = ownerJdbc().sql("SELECT version FROM user_account WHERE id = ?").param(user.id()).query(Long.class).single();
        mvc.perform(post("/api/v1/users/" + user.id() + "/status").header(HttpHeaders.AUTHORIZATION, admin.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"SUSPEND\",\"version\":" + version + ",\"reason\":\"Leave\"}")).andExpect(status().is2xxSuccessful());
        drain(since);
        JsonNode n = mine(admin2, "?category=accounts");
        assertThat(n.path("totalElements").asLong()).isEqualTo(1);
        assertThat(n.path("items").get(0).path("title").asString()).isEqualTo("Account suspended");
        assertThat(n.path("items").get(0).path("body").asString()).doesNotContain("Leave");   // free-text reasons are not copied
        assertThat(n.path("items").get(0).path("link").asString()).isEqualTo("/people/users/" + user.id());
        assertThat(mine(admin, "").path("totalElements").asLong()).as("the actor").isZero();
    }

    @Test
    void importResultsAndIntegrationFailuresReachTheRightPeople() {
        UUID job = UUID.randomUUID();
        ownerJdbc().sql("""
                INSERT INTO student_import_job (id, tenant_id, institution_id, file_name, file_format, file_size, file_sha256, scan_status, headers, status, created_by,
                    confirmed_by) VALUES (?, ?, ?, 'list.csv', 'CSV', 10, repeat('a', 64), 'NOT_SCANNED', '[]', 'COMPLETED', ?, ?)""").params(job, org.tenant, college, admin.id(), admin2.id()).update();
        IntegrationEvent finished = new IntegrationEvent(UUID.randomUUID(), org.tenant, "student_import", job.toString(), "student.import.finished",
                "{\"jobId\":\"" + job + "\",\"status\":\"PARTIALLY_COMPLETED\"}", null, Instant.now());
        assertThat(producer.handle(finished)).isEqualTo(2);    // uploader and confirmer
        // the uploader started the import, but its asynchronous result still reaches them (unlike synchronous actions)
        IntegrationEvent validated = new IntegrationEvent(UUID.randomUUID(), org.tenant, "student_import", job.toString(), "student.import.validated",
                "{\"jobId\":\"" + job + "\",\"rows\":12}", admin.id(), Instant.now());
        assertThat(producer.handle(validated)).isEqualTo(1);
        Map<String, Object> row = ownerJdbc().sql("SELECT severity, title, link FROM notification WHERE tenant_id = ? AND recipient_id = ? AND event_type = 'student.import.finished'")
                .params(org.tenant, admin.id()).query().singleRow();
        assertThat(row.get("severity")).isEqualTo("WARNING");
        assertThat(row.get("link")).isEqualTo("/students/imports/" + job + "/result");

        IntegrationEvent failing = new IntegrationEvent(UUID.randomUUID(), org.tenant, "integration", UUID.randomUUID().toString(), "integration.failing",
                "{\"name\":\"SIS\",\"failures\":3,\"error\":\"HTTP 500\"}", null, Instant.now());
        assertThat(producer.handle(failing)).isEqualTo(2);     // both organization administrators hold integration.manage
    }

    @Test
    void eventsNeverCrossOrganizations() {
        TestOrg other = new TestOrg(ownerJdbc(), mvc, "ntf-b");
        // an event of the other organization naming a user of this one: the recipient is invisible there
        IntegrationEvent e = new IntegrationEvent(UUID.randomUUID(), other.tenant, "role_assignment", UUID.randomUUID().toString(), "role.assigned",
                "{\"userId\":\"" + user.id() + "\",\"role\":\"EVALUATOR\",\"scope\":\"TENANT\"}", null, Instant.now());
        assertThat(producer.handle(e)).isZero();
        assertThat(ownerJdbc().sql("SELECT count(*) FROM notification WHERE recipient_id = ?").param(user.id()).query(Long.class).single()).isZero();
    }

    @Test
    void readUnreadHistoryAndCategories() throws Exception {
        for (int i = 0; i < 3; i++) {
            producer.handle(new IntegrationEvent(UUID.randomUUID(), org.tenant, "role_assignment", UUID.randomUUID().toString(), i == 2 ? "role.revoked" : "role.assigned",
                    "{\"userId\":\"" + user.id() + "\",\"role\":\"EVALUATOR\",\"scope\":\"TENANT\"}", null, Instant.now()));
        }
        JsonNode all = mine(user, "?size=2");
        assertThat(all.path("totalElements").asLong()).isEqualTo(3);
        assertThat(all.path("totalPages").asInt()).isEqualTo(2);
        assertThat(all.path("unreadByCategory").path("access").asLong()).isEqualTo(3);
        String id = all.path("items").get(0).path("id").asString();
        mvc.perform(post("/api/v1/notifications/" + id + "/read").header(HttpHeaders.AUTHORIZATION, user.bearer())).andExpect(status().isNoContent());
        assertThat(mine(user, "?state=READ").path("totalElements").asLong()).isEqualTo(1);
        assertThat(mine(user, "?state=UNREAD").path("totalElements").asLong()).isEqualTo(2);
        mvc.perform(post("/api/v1/notifications/" + id + "/unread").header(HttpHeaders.AUTHORIZATION, user.bearer())).andExpect(status().isNoContent());
        assertThat(mine(user, "?state=UNREAD").path("totalElements").asLong()).isEqualTo(3);
        // another user's notification is not addressable
        mvc.perform(post("/api/v1/notifications/" + id + "/read").header(HttpHeaders.AUTHORIZATION, admin.bearer())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/notifications?category=nope").header(HttpHeaders.AUTHORIZATION, user.bearer())).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/notifications/read-all").header(HttpHeaders.AUTHORIZATION, user.bearer())).andExpect(status().isOk());
        assertThat(mine(user, "").path("unread").asLong()).isZero();
        List<String> titles = mine(user, "?category=access").findValuesAsString("title");
        assertThat(titles).contains("Role removed", "Role assigned");
    }
}
