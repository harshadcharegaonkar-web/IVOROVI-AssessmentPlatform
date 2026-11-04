package com.ivorovi.integrations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ivorovi.integrations.internal.WebhookDispatcher;
import com.ivorovi.integrations.internal.WebhookFanout;
import com.ivorovi.integrations.internal.WebhookSigner;
import com.ivorovi.platform.outbox.IntegrationEvent;
import com.ivorovi.support.IntegrationTest;
import com.ivorovi.support.TestOrg;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Webhooks: subscriptions, signing, minimal payloads, retries, failure tracking, auto-disable, replay, tenant isolation. */
class WebhooksIT extends IntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    WebhookFanout fanout;
    @Autowired
    WebhookDispatcher dispatcher;

    private TestOrg org;
    private UUID college;
    private TestOrg.Account admin, instAdmin;
    private Receiver receiver;

    @BeforeEach
    void setUp() throws Exception {
        org = new TestOrg(ownerJdbc(), mvc, "whk");
        college = org.unit("COLLEGE", null, "C");
        admin = org.account("ORG_ADMIN", null);
        instAdmin = org.account("INSTITUTION_ADMIN", college);
        receiver = new Receiver();
    }

    @AfterEach
    void tearDown() {
        receiver.close();
    }

    private JsonNode call(MockHttpServletRequestBuilder r, TestOrg.Account who, ResultMatcher expect) throws Exception {
        String body = mvc.perform(r.header(HttpHeaders.AUTHORIZATION, who.bearer()).contentType(MediaType.APPLICATION_JSON)).andExpect(expect)
                .andReturn().getResponse().getContentAsString();
        return body.isEmpty() ? null : JSON.readTree(body);
    }

    private JsonNode endpoint(String path, boolean identifiers) throws Exception {
        return call(post("/api/v1/webhooks").content("{\"name\":\"Hook " + path + "\",\"url\":\"" + receiver.url(path)
                + "\",\"eventTypes\":[\"student.created\",\"student.status_changed\"],\"includeIdentifiers\":" + identifiers + "}"), admin, status().isCreated());
    }

    private int fanOut(Instant since) {
        int n = 0;
        for (IntegrationEvent e : org.events(since)) n += fanout.handle(e);
        return n;
    }

    @Test
    void signedTestPingAndMinimalStudentPayload() throws Exception {
        JsonNode created = endpoint("/hook", false);
        String secret = created.path("signingSecret").asString();
        String id = created.path("endpoint").path("id").asString();
        assertThat(secret).startsWith("whsec_");
        assertThat(call(get("/api/v1/webhooks/" + id), admin, status().isOk()).toString()).doesNotContain(secret);
        assertThat(ownerJdbc().sql("SELECT secret_enc FROM webhook_endpoint WHERE id = ?").param(UUID.fromString(id)).query(String.class).single())
                .startsWith("v1:").doesNotContain(secret);

        JsonNode ping = call(post("/api/v1/webhooks/" + id + "/test"), admin, status().isOk());
        assertThat(ping.path("delivered").asBoolean()).isTrue();
        Receiver.Request r = receiver.requests.getLast();
        assertThat(r.method()).isEqualTo("POST");
        assertThat(r.header("Ivorovi-Event-Type")).isEqualTo("webhook.ping");
        String sig = r.header(WebhookSigner.HEADER);
        long t = Long.parseLong(sig.substring(2, sig.indexOf(',')));
        assertThat(sig).isEqualTo("t=" + t + ",v1=" + WebhookSigner.hmac(secret, t + "." + r.body()));
        assertThat(Math.abs(Instant.now().getEpochSecond() - t)).isLessThan(60);

        Instant since = Instant.now().minusSeconds(1);
        org.student(admin, college, "Asha", "Rao");
        assertThat(fanOut(since)).isEqualTo(1);
        assertThat(fanOut(since)).as("idempotent").isZero();
        assertThat(dispatcher.dispatch(org.tenant, 10)).isEqualTo(1);
        Receiver.Request delivered = receiver.requests.getLast();
        JsonNode body = JSON.readTree(delivered.body());
        assertThat(body.path("type").asString()).isEqualTo("student.created");
        assertThat(body.path("tenantId").asString()).isEqualTo(org.tenant.toString());
        assertThat(body.path("data").path("resourceType").asString()).isEqualTo("student");
        assertThat(delivered.body()).doesNotContain("Asha").doesNotContain("Rao").doesNotContain("studentNo");
        JsonNode list = call(get("/api/v1/webhooks/" + id + "/deliveries?status=DELIVERED"), admin, status().isOk());
        assertThat(list.path("totalElements").asLong()).isEqualTo(2);   // ping + student.created
    }

    @Test
    void identifiersOnlyWithOptIn() throws Exception {
        endpoint("/ids", true);
        Instant since = Instant.now().minusSeconds(1);
        org.student(admin, college, "Asha", "Rao");
        fanOut(since);
        dispatcher.dispatch(org.tenant, 10);
        String body = receiver.requests.getLast().body();
        assertThat(JSON.readTree(body).path("data").path("studentNo").asString()).startsWith("S-");
        assertThat(body).doesNotContain("Asha").doesNotContain("Rao");
    }

    @Test
    void failuresAreRetriedThenFailedAndTheEndpointIsDisabledThenReplayed() throws Exception {
        String id = endpoint("/fail", false).path("endpoint").path("id").asString();
        Instant since = Instant.now().minusSeconds(1);
        org.student(admin, college, "Asha", "Rao");
        fanOut(since);
        for (int attempt = 1; attempt <= 3; attempt++) {
            ownerJdbc().sql("UPDATE webhook_delivery SET next_attempt_at = now() WHERE endpoint_id = ? AND status = 'PENDING'").param(UUID.fromString(id)).update();
            assertThat(dispatcher.dispatch(org.tenant, 10)).isZero();
        }
        JsonNode d = call(get("/api/v1/webhooks/" + id + "/deliveries"), admin, status().isOk()).path("items").get(0);
        assertThat(d.path("status").asString()).isEqualTo("FAILED");
        assertThat(d.path("attempts").asInt()).isEqualTo(3);
        assertThat(d.path("lastStatusCode").asInt()).isEqualTo(500);
        JsonNode ep = call(get("/api/v1/webhooks/" + id), admin, status().isOk());
        assertThat(ep.path("status").asString()).isEqualTo("AUTO_DISABLED");
        assertThat(ep.path("consecutiveFailures").asInt()).isEqualTo(3);
        assertThat(ownerJdbc().sql("SELECT count(*) FROM outbox_event WHERE tenant_id = ? AND event_type = 'webhook.endpoint.disabled'").param(org.tenant)
                .query(Long.class).single()).isEqualTo(1);

        // fix the receiver URL, resume and replay the failed delivery
        long version = ep.path("version").asLong();
        call(put("/api/v1/webhooks/" + id).content("{\"url\":\"" + receiver.url("/fixed") + "\",\"version\":" + version + "}"), admin, status().isOk());
        call(put("/api/v1/webhooks/" + id + "/status").content("{\"status\":\"ACTIVE\"}"), admin, status().isOk());
        JsonNode replay = call(post("/api/v1/webhooks/" + id + "/deliveries/" + d.path("id").asString() + "/replay"), admin, status().isCreated());
        assertThat(replay.path("replayOf").asString()).isEqualTo(d.path("id").asString());
        assertThat(dispatcher.dispatch(org.tenant, 10)).isEqualTo(1);
        assertThat(receiver.requests.getLast().path()).isEqualTo("/fixed");
        assertThat(JSON.readTree(receiver.requests.getLast().body()).path("id").asString()).isEqualTo(JSON.readTree(d.path("payload").asString()).path("id").asString());
    }

    @Test
    void pausedEndpointsHoldDeliveriesAndEventsNeverCrossOrganizations() throws Exception {
        String id = endpoint("/hook", false).path("endpoint").path("id").asString();
        TestOrg other = new TestOrg(ownerJdbc(), mvc, "whk-b");
        UUID otherCollege = other.unit("COLLEGE", null, "X");
        TestOrg.Account otherAdmin = other.account("ORG_ADMIN", null);
        Instant since = Instant.now().minusSeconds(1);
        other.student(otherAdmin, otherCollege, "Zed", "Other");
        int created = 0;
        for (IntegrationEvent e : other.events(since)) created += fanout.handle(e);
        assertThat(created).as("no endpoint of this organization receives the other organization's events").isZero();
        // a forged event claiming another organization finds no endpoints there either
        assertThat(fanout.handle(new IntegrationEvent(UUID.randomUUID(), other.tenant, "student", UUID.randomUUID().toString(), "student.created", "{}", null,
                Instant.now()))).isZero();
        call(get("/api/v1/webhooks/" + id), otherAdmin, status().isNotFound());

        call(put("/api/v1/webhooks/" + id + "/status").content("{\"status\":\"PAUSED\"}"), admin, status().isOk());
        Instant s2 = Instant.now().minusSeconds(1);
        org.student(admin, college, "Asha", "Rao");
        assertThat(fanOut(s2)).isEqualTo(1);
        assertThat(dispatcher.dispatch(org.tenant, 10)).isZero();
        assertThat(receiver.requests).isEmpty();
        call(put("/api/v1/webhooks/" + id + "/status").content("{\"status\":\"ACTIVE\"}"), admin, status().isOk());
        assertThat(dispatcher.dispatch(org.tenant, 10)).isEqualTo(1);
    }

    @Test
    void validationAndPermissions() throws Exception {
        call(post("/api/v1/webhooks").content("{\"name\":\"X\",\"url\":\"" + receiver.url("/") + "\",\"eventTypes\":[\"student.deleted_forever\"]}"), admin,
                status().isBadRequest());
        call(post("/api/v1/webhooks").content("{\"name\":\"X\",\"url\":\"ftp://example.org/\",\"eventTypes\":[\"student.created\"]}"), admin, status().isBadRequest());
        call(post("/api/v1/webhooks").content("{\"name\":\"X\",\"url\":\"" + receiver.url("/") + "\",\"eventTypes\":[]}"), admin, status().isBadRequest());
        call(get("/api/v1/webhooks"), instAdmin, status().isForbidden());
        call(post("/api/v1/webhooks").content("{\"name\":\"X\",\"url\":\"" + receiver.url("/") + "\",\"eventTypes\":[\"student.created\"]}"), instAdmin,
                status().isForbidden());
        JsonNode types = call(get("/api/v1/webhooks/event-types"), admin, status().isOk());
        assertThat(types.findValuesAsString("key")).contains("student.created", "academics.*");
        String id = endpoint("/hook", false).path("endpoint").path("id").asString();
        String rotated = call(post("/api/v1/webhooks/" + id + "/rotate-secret"), admin, status().isOk()).path("signingSecret").asString();
        call(post("/api/v1/webhooks/" + id + "/test"), admin, status().isOk());
        String sig = receiver.requests.getLast().header(WebhookSigner.HEADER);
        long t = Long.parseLong(sig.substring(2, sig.indexOf(',')));
        assertThat(sig).endsWith(WebhookSigner.hmac(rotated, t + "." + receiver.requests.getLast().body()));
    }
}
