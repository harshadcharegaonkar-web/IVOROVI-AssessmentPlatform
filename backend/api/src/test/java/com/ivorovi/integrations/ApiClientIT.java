package com.ivorovi.integrations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ivorovi.support.IntegrationTest;
import com.ivorovi.support.TestOrg;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** API clients: scoped read-only keys, shown once, hashed, rotation with grace, revocation, IP rules, expiry, usage, isolation. */
class ApiClientIT extends IntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @org.springframework.beans.factory.annotation.Autowired
    com.ivorovi.platform.tenancy.TenantDirectory tenants;
    @org.springframework.beans.factory.annotation.Autowired
    com.ivorovi.integrations.internal.ApiClientUsage usage;

    private TestOrg org;
    private UUID college1, college2;
    private TestOrg.Account admin, instAdmin;

    @BeforeEach
    void setUp() throws Exception {
        org = new TestOrg(ownerJdbc(), mvc, "api");
        college1 = org.unit("COLLEGE", null, "C1");
        college2 = org.unit("COLLEGE", null, "C2");
        admin = org.account("ORG_ADMIN", null);
        instAdmin = org.account("INSTITUTION_ADMIN", college1);
        org.student(admin, college1, "Asha", "Rao");
        org.student(admin, college2, "Ravi", "Iyer");
    }

    private JsonNode call(MockHttpServletRequestBuilder r, TestOrg.Account who, ResultMatcher expect) throws Exception {
        String body = mvc.perform(r.header(HttpHeaders.AUTHORIZATION, who.bearer()).contentType(MediaType.APPLICATION_JSON)).andExpect(expect)
                .andReturn().getResponse().getContentAsString();
        return body.isEmpty() ? null : JSON.readTree(body);
    }

    private JsonNode create(String scopes, UUID unit, String ips) throws Exception {
        return call(post("/api/v1/api-clients").content("{\"name\":\"Client " + UUID.randomUUID().toString().substring(0, 6) + "\",\"scopes\":[" + scopes + "]"
                + (unit == null ? "" : ",\"orgUnitId\":\"" + unit + "\"") + (ips == null ? "" : ",\"allowedIps\":[" + ips + "]") + "}"), admin, status().isCreated());
    }

    private static MockHttpServletRequestBuilder withKey(MockHttpServletRequestBuilder r, String key) {
        return r.header("X-Api-Key", key);
    }

    @Test
    void keyIsShownOnceStoredHashedAndGrantsOnlyItsScopes() throws Exception {
        JsonNode created = create("\"student.read\"", null, null);
        String key = created.path("apiKey").asString();
        assertThat(key).startsWith("ivk_").hasSizeGreaterThan(60);
        String id = created.path("client").path("id").asString();
        String secret = key.substring(key.indexOf('_', 4) + 1);
        JsonNode detail = call(get("/api/v1/api-clients/" + id), admin, status().isOk());
        assertThat(detail.toString()).doesNotContain(secret).doesNotContain(key);
        assertThat(detail.path("keys").get(0).path("hint").asString()).isEqualTo(secret.substring(secret.length() - 4));
        String hash = ownerJdbc().sql("SELECT secret_hash FROM api_client_secret WHERE client_id = ?").param(UUID.fromString(id)).query(String.class).single();
        assertThat(hash).hasSize(64).isNotEqualTo(secret);
        String trail = ownerJdbc().sql("SELECT string_agg(details::text, '|') FROM audit_event WHERE tenant_id = ?").param(org.tenant).query(String.class).single();
        assertThat(trail).contains(created.path("keyId").asString()).doesNotContain(secret);

        mvc.perform(withKey(get("/api/v1/students"), key)).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/api/v1/students").header(HttpHeaders.AUTHORIZATION, "ApiKey " + key)).andExpect(status().isOk());
        mvc.perform(withKey(get("/api/v1/insights/students"), key)).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2));
        // outside the scopes
        mvc.perform(withKey(get("/api/v1/users"), key)).andExpect(status().isForbidden());
        mvc.perform(withKey(get("/api/v1/integrations"), key)).andExpect(status().isForbidden());
        mvc.perform(withKey(get("/api/v1/api-clients"), key)).andExpect(status().isForbidden());
        // read-only, whatever the scopes
        mvc.perform(withKey(post("/api/v1/students").contentType(MediaType.APPLICATION_JSON).content("{}"), key))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("api_key_read_only"));
        // both a key and a token is ambiguous
        mvc.perform(withKey(get("/api/v1/students"), key).header(HttpHeaders.AUTHORIZATION, admin.bearer())).andExpect(status().isBadRequest());
        // wrong secret, unknown key id, malformed
        mvc.perform(withKey(get("/api/v1/students"), key.substring(0, key.length() - 2) + "xx")).andExpect(status().isUnauthorized());
        mvc.perform(withKey(get("/api/v1/students"), "ivk_000000000000000000000000_" + secret)).andExpect(status().isUnauthorized());
        mvc.perform(withKey(get("/api/v1/students"), "not-a-key")).andExpect(status().isUnauthorized());

        usage.flush();   // counters are written in batches
        JsonNode usage = call(get("/api/v1/api-clients/" + id), admin, status().isOk()).path("usage");
        assertThat(usage.get(0).path("requests").asLong()).isGreaterThanOrEqualTo(7);
        assertThat(usage.get(0).path("denied").asLong()).isGreaterThanOrEqualTo(4);
        assertThat(call(get("/api/v1/api-clients/" + id), admin, status().isOk()).path("client").path("lastUsedAt").isNull()).isFalse();
    }

    @Test
    void scopesAreLimitedAndCannotEscalate() throws Exception {
        for (String forbidden : new String[] {"student.personal.read", "audit.read", "integration.read", "student.manage", "no.such"}) {
            call(post("/api/v1/api-clients").content("{\"name\":\"Bad " + forbidden + "\",\"scopes\":[\"" + forbidden + "\"]}"), admin, status().isBadRequest());
        }
        call(post("/api/v1/api-clients").content("{\"name\":\"None\",\"scopes\":[]}"), admin, status().isBadRequest());
        // only integration managers register clients
        call(post("/api/v1/api-clients").content("{\"name\":\"Inst\",\"scopes\":[\"student.read\"]}"), instAdmin, status().isForbidden());
        JsonNode scopes = call(get("/api/v1/api-clients/scopes"), admin, status().isOk());
        assertThat(scopes.findValuesAsString("key")).contains("student.read", "academics.read").doesNotContain("student.personal.read", "audit.read",
                "integration.read", "student.manage");
    }

    @Test
    void unitRestrictedClientSeesOnlyItsUnit() throws Exception {
        String key = create("\"student.read\"", college1, null).path("apiKey").asString();
        mvc.perform(withKey(get("/api/v1/students"), key)).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(withKey(get("/api/v1/insights/students").param("orgUnitId", college2.toString()), key)).andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void ipAllowListExpiryRotationAndRevocation() throws Exception {
        JsonNode c = create("\"student.read\"", null, "\"10.0.0.0/8\", \"2001:db8::/32\"");
        String id = c.path("client").path("id").asString();
        String key = c.path("apiKey").asString();
        mvc.perform(withKey(get("/api/v1/students"), key)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("api_key_ip_not_allowed"));
        mvc.perform(withKey(get("/api/v1/students"), key).with(r -> { r.setRemoteAddr("10.20.30.40"); return r; })).andExpect(status().isOk());
        call(post("/api/v1/api-clients").content("{\"name\":\"Bad ip\",\"scopes\":[\"student.read\"],\"allowedIps\":[\"example.org\"]}"), admin, status().isBadRequest());
        long version = call(get("/api/v1/api-clients/" + id), admin, status().isOk()).path("client").path("version").asLong();
        call(put("/api/v1/api-clients/" + id).content("{\"allowedIps\":[],\"version\":" + version + "}"), admin, status().isOk());
        mvc.perform(withKey(get("/api/v1/students"), key)).andExpect(status().isOk());

        // rotation with a grace period: old and new keys both work
        String key2 = call(post("/api/v1/api-clients/" + id + "/rotate").content("{\"graceHours\":24}"), admin, status().isOk()).path("apiKey").asString();
        mvc.perform(withKey(get("/api/v1/students"), key)).andExpect(status().isOk());
        mvc.perform(withKey(get("/api/v1/students"), key2)).andExpect(status().isOk());
        // immediate rotation: only the newest key works
        String key3 = call(post("/api/v1/api-clients/" + id + "/rotate").content("{\"graceHours\":0}"), admin, status().isOk()).path("apiKey").asString();
        mvc.perform(withKey(get("/api/v1/students"), key2)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("api_key_revoked"));
        mvc.perform(withKey(get("/api/v1/students"), key3)).andExpect(status().isOk());
        call(post("/api/v1/api-clients/" + id + "/rotate").content("{\"graceHours\":500}"), admin, status().isBadRequest());

        // an expired key
        ownerJdbc().sql("UPDATE api_client_secret SET expires_at = now() - interval '1 minute' WHERE key_id = ?").param(key3.substring(4, 28)).update();
        mvc.perform(withKey(get("/api/v1/students"), key3)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("api_key_expired"));

        // revoking the client stops every key and is permanent
        String key4 = call(post("/api/v1/api-clients/" + id + "/rotate").content("{\"graceHours\":0}"), admin, status().isOk()).path("apiKey").asString();
        call(post("/api/v1/api-clients/" + id + "/revoke").content("{}"), admin, status().isBadRequest());
        JsonNode revoked = call(post("/api/v1/api-clients/" + id + "/revoke").content("{\"reason\":\"Vendor contract ended\"}"), admin, status().isOk());
        assertThat(revoked.path("status").asString()).isEqualTo("REVOKED");
        mvc.perform(withKey(get("/api/v1/students"), key4)).andExpect(status().isUnauthorized());
        call(post("/api/v1/api-clients/" + id + "/rotate").content("{\"graceHours\":0}"), admin, status().isConflict());
        assertThat(ownerJdbc().sql("SELECT count(*) FROM audit_event WHERE tenant_id = ? AND action LIKE 'api_client.%'").param(org.tenant).query(Long.class).single())
                .isGreaterThanOrEqualTo(6);
    }

    @Test
    void keysNeverReachAnotherOrganization() throws Exception {
        String key = create("\"student.read\"", null, null).path("apiKey").asString();
        TestOrg other = new TestOrg(ownerJdbc(), mvc, "api-b");
        UUID c = other.unit("COLLEGE", null, "X");
        TestOrg.Account otherAdmin = other.account("ORG_ADMIN", null);
        UUID foreign = other.student(otherAdmin, c, "Zed", "Other");
        mvc.perform(withKey(get("/api/v1/students"), key)).andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(withKey(get("/api/v1/students/" + foreign), key)).andExpect(status().isNotFound());
        // a suspended organization's keys stop working
        ownerJdbc().sql("UPDATE tenant SET status = 'SUSPENDED' WHERE id = ?").param(org.tenant).update();
        tenants.evict(org.tenant);   // as the platform's suspension does
        try {
            mvc.perform(withKey(get("/api/v1/students"), key)).andExpect(status().isUnauthorized());
        } finally {
            ownerJdbc().sql("UPDATE tenant SET status = 'ACTIVE' WHERE id = ?").param(org.tenant).update();
            tenants.evict(org.tenant);
        }
    }
}
