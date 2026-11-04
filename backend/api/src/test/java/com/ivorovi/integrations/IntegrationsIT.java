package com.ivorovi.integrations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ivorovi.support.IntegrationTest;
import com.ivorovi.support.TestOrg;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Integration framework: configuration, secrets, connection tests, sync, retries, history, failure tracking, permissions. */
class IntegrationsIT extends IntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SECRET = "sis-token-9b1f2c";

    private TestOrg org;
    private TestOrg.Account admin, instAdmin;
    private Receiver receiver;

    @BeforeEach
    void setUp() throws Exception {
        org = new TestOrg(ownerJdbc(), mvc, "int");
        UUID college = org.unit("COLLEGE", null, "C");
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

    private JsonNode createRest(String healthPath) throws Exception {
        return call(post("/api/v1/integrations").content("""
                {"kind":"SIS_ERP","adapter":"generic-rest","name":"Campus SIS","config":{"baseUrl":"%s","healthPath":"%s","syncPath":"/students","authType":"BEARER"},
                 "secret":"%s"}""".formatted(receiver.url(""), healthPath, SECRET)), admin, status().isCreated());
    }

    @Test
    void configurationIsValidatedAndTheSecretIsWriteOnly() throws Exception {
        JsonNode i = createRest("/health");
        assertThat(i.path("secretSet").asBoolean()).isTrue();
        assertThat(i.toString()).doesNotContain(SECRET);
        assertThat(i.path("enabled").asBoolean()).isFalse();
        assertThat(i.path("connectionStatus").asString()).isEqualTo("UNKNOWN");
        String stored = ownerJdbc().sql("SELECT secret_enc FROM integration WHERE id = ?").param(UUID.fromString(i.path("id").asString())).query(String.class).single();
        assertThat(stored).startsWith("v1:").doesNotContain(SECRET);
        JsonNode detail = call(get("/api/v1/integrations/" + i.path("id").asString()), admin, status().isOk());
        assertThat(detail.toString()).doesNotContain(SECRET);
        assertThat(detail.path("adapter").path("supportsSync").asBoolean()).isTrue();

        // adapter validation: BEARER without secret, wrong kind for the adapter, unknown adapter, duplicate name
        call(post("/api/v1/integrations").content("""
                {"kind":"LMS","adapter":"generic-rest","name":"LMS","config":{"baseUrl":"%s","authType":"BEARER"}}""".formatted(receiver.url(""))), admin,
                status().isBadRequest());
        call(post("/api/v1/integrations").content("{\"kind\":\"EMAIL\",\"adapter\":\"generic-rest\",\"name\":\"X\",\"config\":{}}"), admin, status().isBadRequest());
        call(post("/api/v1/integrations").content("{\"kind\":\"LMS\",\"adapter\":\"moodle\",\"name\":\"X\",\"config\":{}}"), admin, status().isBadRequest());
        call(post("/api/v1/integrations").content("""
                {"kind":"SIS_ERP","adapter":"generic-rest","name":"campus sis","config":{"baseUrl":"%s","authType":"NONE"}}""".formatted(receiver.url(""))), admin,
                status().isConflict());
        String trail = ownerJdbc().sql("SELECT string_agg(details::text, '|') FROM audit_event WHERE tenant_id = ?").param(org.tenant).query(String.class).single();
        assertThat(trail).contains("secretSet").doesNotContain(SECRET);
    }

    @Test
    void connectionTestsSyncRetriesAndHistory() throws Exception {
        String id = createRest("/health").path("id").asString();
        JsonNode run = call(post("/api/v1/integrations/" + id + "/test"), admin, status().isOk());
        assertThat(run.path("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(run.path("operation").asString()).isEqualTo("CONNECTION_TEST");
        assertThat(receiver.requests.getLast().header("Authorization")).isEqualTo("Bearer " + SECRET);
        JsonNode v = call(get("/api/v1/integrations/" + id), admin, status().isOk()).path("integration");
        assertThat(v.path("connectionStatus").asString()).isEqualTo("HEALTHY");
        assertThat(v.path("lastSuccessAt").isNull()).isFalse();

        // sync needs the integration enabled
        call(post("/api/v1/integrations/" + id + "/sync"), admin, status().isConflict());
        call(put("/api/v1/integrations/" + id + "/enabled").content("{\"enabled\":true}"), admin, status().isOk());
        JsonNode sync = call(post("/api/v1/integrations/" + id + "/sync"), admin, status().isOk());
        assertThat(sync.path("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(sync.path("message").asString()).contains("no data imported");
        assertThat(call(get("/api/v1/integrations/" + id), admin, status().isOk()).path("integration").path("lastSyncAt").isNull()).isFalse();

        // failing endpoint: each run retries (2 attempts), failures are counted, the threshold raises one event
        long version = call(get("/api/v1/integrations/" + id), admin, status().isOk()).path("integration").path("version").asLong();
        call(put("/api/v1/integrations/" + id).content("""
                {"config":{"baseUrl":"%s","healthPath":"/fail","authType":"BEARER"},"version":%d}""".formatted(receiver.url(""), version)), admin, status().isOk());
        JsonNode failed = call(post("/api/v1/integrations/" + id + "/test"), admin, status().isOk());
        assertThat(failed.path("status").asString()).isEqualTo("FAILED");
        assertThat(failed.path("attempts").asInt()).isEqualTo(2);
        assertThat(failed.path("message").asString()).contains("HTTP 500");
        call(post("/api/v1/integrations/" + id + "/test"), admin, status().isOk());
        JsonNode state = call(get("/api/v1/integrations/" + id), admin, status().isOk()).path("integration");
        assertThat(state.path("connectionStatus").asString()).isEqualTo("FAILING");
        assertThat(state.path("consecutiveFailures").asInt()).isEqualTo(2);
        assertThat(state.path("lastError").asString()).contains("HTTP 500");
        assertThat(ownerJdbc().sql("SELECT count(*) FROM outbox_event WHERE tenant_id = ? AND event_type = 'integration.failing'").param(org.tenant)
                .query(Long.class).single()).isEqualTo(1);

        // manual retry of a failed run is recorded as RETRY of it
        JsonNode retry = call(post("/api/v1/integrations/" + id + "/runs/" + failed.path("id").asString() + "/retry"), admin, status().isOk());
        assertThat(retry.path("trigger").asString()).isEqualTo("RETRY");
        assertThat(retry.path("retryOf").asString()).isEqualTo(failed.path("id").asString());
        call(post("/api/v1/integrations/" + id + "/runs/" + run.path("id").asString() + "/retry"), admin, status().isConflict());

        JsonNode history = call(get("/api/v1/integrations/" + id + "/runs?size=3"), admin, status().isOk());
        assertThat(history.path("totalElements").asLong()).isEqualTo(5);
        assertThat(history.path("items").size()).isEqualTo(3);

        // enabled integrations cannot be deleted; disabled ones can (with their history)
        call(delete("/api/v1/integrations/" + id), admin, status().isConflict());
        call(put("/api/v1/integrations/" + id + "/enabled").content("{\"enabled\":false}"), admin, status().isOk());
        call(delete("/api/v1/integrations/" + id), admin, status().isNoContent());
        assertThat(ownerJdbc().sql("SELECT count(*) FROM integration_run WHERE integration_id = ?").param(UUID.fromString(id)).query(Long.class).single()).isZero();
    }

    @Test
    void smtpAdapterChecksTheRelayWithoutSendingMail() throws Exception {
        try (ServerSocket smtp = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread t = Thread.ofVirtual().start(() -> {
                try (Socket s = smtp.accept()) {
                    OutputStream out = s.getOutputStream();
                    BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
                    out.write("220 test ESMTP\r\n".getBytes(StandardCharsets.US_ASCII));
                    in.readLine();
                    out.write("250-test\r\n250-STARTTLS\r\n250 OK\r\n".getBytes(StandardCharsets.US_ASCII));
                    in.readLine();
                } catch (Exception ignored) {
                    // test server
                }
            });
            String id = call(post("/api/v1/integrations").content("""
                    {"kind":"EMAIL","adapter":"smtp","name":"Mail relay","config":{"host":"127.0.0.1","port":"%d","security":"STARTTLS"}}""".formatted(smtp.getLocalPort())),
                    admin, status().isCreated()).path("id").asString();
            JsonNode run = call(post("/api/v1/integrations/" + id + "/test"), admin, status().isOk());
            assertThat(run.path("status").asString()).isEqualTo("SUCCEEDED");
            assertThat(run.path("message").asString()).contains("STARTTLS offered");
            t.join(2000);
        }
    }

    @Test
    void onlyIntegrationManagersCanSeeOrChangeIntegrations() throws Exception {
        call(get("/api/v1/integrations"), instAdmin, status().isForbidden());
        call(get("/api/v1/integrations/adapters"), instAdmin, status().isForbidden());
        call(post("/api/v1/integrations").content("{\"kind\":\"LMS\",\"adapter\":\"generic-rest\",\"name\":\"X\",\"config\":{}}"), instAdmin, status().isForbidden());
        JsonNode adapters = call(get("/api/v1/integrations/adapters"), admin, status().isOk());
        assertThat(java.util.stream.StreamSupport.stream(adapters.spliterator(), false).map(a -> a.path("key").asString())).containsExactly("generic-rest", "smtp");

        // another organization's integration does not exist for this one
        String id = createRest("/health").path("id").asString();
        TestOrg other = new TestOrg(ownerJdbc(), mvc, "int-b");
        TestOrg.Account otherAdmin = other.account("ORG_ADMIN", null);
        call(get("/api/v1/integrations/" + id), otherAdmin, status().isNotFound());
        call(post("/api/v1/integrations/" + id + "/test"), otherAdmin, status().isNotFound());
        assertThat(call(get("/api/v1/integrations"), otherAdmin, status().isOk()).size()).isZero();
    }
}
