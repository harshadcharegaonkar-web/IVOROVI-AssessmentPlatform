package com.ivorovi.integrations;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ivorovi.integrations.internal.IntegrationLifecycle;
import com.ivorovi.integrations.internal.ServiceIdentityService;
import com.ivorovi.platform.outbox.IntegrationEvent;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.support.IntegrationTest;
import com.ivorovi.support.TestOrg;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * KI-59: API clients never keep a permission their authority has lost. Covers permission removal, role-assignment
 * revocation and expiry, owner suspension/deactivation (live refusal + lifecycle suspension, no automatic resumption),
 * reassignment, service identities (custodians, independently approved envelope, role ceilings, 30-day custodian rule),
 * concurrent authorization changes, organization isolation and audit.
 */
class ApiClientAuthorizationIT extends IntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    IntegrationLifecycle lifecycle;
    @Autowired
    ServiceIdentityService identities;

    private TestOrg org;
    private UUID college1, college2;
    private TestOrg.Account admin, admin2, owner, outsider;
    private UUID ownerRole, ownerAssignment;

    @BeforeEach
    void setUp() throws Exception {
        org = new TestOrg(ownerJdbc(), mvc, "ki59");
        UUID uni = org.unit("UNIVERSITY", null, "U");
        college1 = org.unit("COLLEGE", uni, "C1");
        college2 = org.unit("COLLEGE", uni, "C2");
        admin = org.account("ORG_ADMIN", null);
        admin2 = org.account("ORG_ADMIN", null);
        owner = org.account("STUDENT", null);          // a role without permissions: every grant comes from the custom role below
        outsider = org.account("FACULTY", college1);
        org.student(admin, college1, "Asha", "Rao");
        org.student(admin, college2, "Ravi", "Iyer");
        // custom role for the owner: reads students and units, manages integrations (organization-wide)
        JsonNode role = call(post("/api/v1/roles").header("Idempotency-Key", "k-" + UUID.randomUUID()).content("""
                {"name":"Integration owner %s","permissions":["student.read","org_unit.read","integration.read","integration.manage"]}"""
                .formatted(UUID.randomUUID().toString().substring(0, 6))), admin, status().isCreated());
        ownerRole = UUID.fromString(role.path("role").path("id").asString());
        JsonNode a = call(post("/api/v1/users/" + owner.id() + "/role-assignments").content("{\"roleId\":\"" + ownerRole + "\"}"), admin, status().is2xxSuccessful());
        ownerAssignment = UUID.fromString(a.path("id").asString());
    }

    private JsonNode call(MockHttpServletRequestBuilder r, TestOrg.Account who, ResultMatcher expect) throws Exception {
        String body = mvc.perform(r.header(HttpHeaders.AUTHORIZATION, who.bearer()).contentType(MediaType.APPLICATION_JSON)).andExpect(expect)
                .andReturn().getResponse().getContentAsString();
        return body.isEmpty() ? null : JSON.readTree(body);
    }

    private int keyStatus(String key, String path) throws Exception {
        return mvc.perform(get(path).header("X-Api-Key", key)).andReturn().getResponse().getStatus();
    }

    private String keyCode(String key, String path) throws Exception {
        String body = mvc.perform(get(path).header("X-Api-Key", key)).andReturn().getResponse().getContentAsString();
        return body.isEmpty() ? "" : JSON.readTree(body).path("code").asString();
    }

    private JsonNode delegatedClient(TestOrg.Account by, String scopes) throws Exception {
        return call(post("/api/v1/api-clients").content("{\"name\":\"Client " + UUID.randomUUID().toString().substring(0, 6) + "\",\"scopes\":[" + scopes + "]}"),
                by, status().isCreated());
    }

    private long roleVersion(UUID role) throws Exception {
        return call(get("/api/v1/roles/" + role), admin, status().isOk()).path("role").path("version").asLong();
    }

    private long userVersion(UUID user) {
        return ownerJdbc().sql("SELECT version FROM user_account WHERE id = ?").param(user).query(Long.class).single();
    }

    private void userStatus(TestOrg.Account target, String action) throws Exception {
        userStatus(admin, target, action);
    }

    private void userStatus(TestOrg.Account actor, TestOrg.Account target, String action) throws Exception {
        call(post("/api/v1/users/" + target.id() + "/status").content("{\"action\":\"" + action + "\",\"version\":" + userVersion(target.id())
                + ",\"reason\":\"KI-59 test\"}"), actor, status().is2xxSuccessful());
    }

    private void lifecycleEvent(String type, UUID user) {
        lifecycle.handle(new IntegrationEvent(UUID.randomUUID(), org.tenant, "user_account", user.toString(), type, "{}", admin.id(), Instant.now()));
    }

    private long audits(String action) {
        return ownerJdbc().sql("SELECT count(*) FROM audit_event WHERE tenant_id = ? AND action = ?").params(org.tenant, action).query(Long.class).single();
    }

    // ============================================================================================ delegated clients

    @Test
    void permissionRemovedFromTheOwnersRoleIsLostOnTheNextRequest() throws Exception {
        String key = delegatedClient(owner, "\"student.read\",\"org_unit.read\"").path("apiKey").asString();
        assertThat(keyStatus(key, "/api/v1/students")).isEqualTo(200);
        assertThat(keyStatus(key, "/api/v1/org-units")).isEqualTo(200);
        call(patch("/api/v1/roles/" + ownerRole).content("{\"version\":" + roleVersion(ownerRole)
                + ",\"permissions\":[\"org_unit.read\",\"integration.read\",\"integration.manage\"]}"), admin, status().isOk());
        assertThat(keyStatus(key, "/api/v1/students")).as("student.read removed from the owner's role").isEqualTo(403);
        assertThat(keyStatus(key, "/api/v1/org-units")).as("other scopes keep working").isEqualTo(200);
        assertThat(audits("api_client.scope_lost")).isEqualTo(1);
        keyStatus(key, "/api/v1/students");
        assertThat(audits("api_client.scope_lost")).as("one record per client, scope and day").isEqualTo(1);
        // putting the permission back restores it (scopes are the registered maximum, authority is live)
        call(patch("/api/v1/roles/" + ownerRole).content("{\"version\":" + roleVersion(ownerRole)
                + ",\"permissions\":[\"student.read\",\"org_unit.read\",\"integration.read\",\"integration.manage\"]}"), admin, status().isOk());
        assertThat(keyStatus(key, "/api/v1/students")).isEqualTo(200);
    }

    @Test
    void revokingOrExpiringTheOwnersRoleAssignmentStopsTheClient() throws Exception {
        String key = delegatedClient(owner, "\"student.read\"").path("apiKey").asString();
        call(post("/api/v1/users/" + owner.id() + "/role-assignments/" + ownerAssignment + "/revoke").content("{\"reason\":\"moved\"}"), admin, status().is2xxSuccessful());
        assertThat(keyStatus(key, "/api/v1/students")).isEqualTo(403);

        // an assignment that expires: the client works until the boundary, then stops without any further action
        call(post("/api/v1/users/" + owner.id() + "/role-assignments").content("{\"roleId\":\"" + ownerRole + "\",\"validTo\":\""
                + Instant.now().plusSeconds(3) + "\"}"), admin, status().is2xxSuccessful());
        assertThat(keyStatus(key, "/api/v1/students")).isEqualTo(200);
        Thread.sleep(4_000);
        assertThat(keyStatus(key, "/api/v1/students")).as("role assignment expired").isEqualTo(403);
    }

    @Test
    void scopesAreNarrowedToTheOwnersCurrentUnits() throws Exception {
        String key = delegatedClient(owner, "\"student.read\"").path("apiKey").asString();
        mvc.perform(get("/api/v1/students").header("X-Api-Key", key)).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2));
        // the owner moves from an organization-wide grant to college 1 only: the client follows
        UUID collegeReader = readRole("\"student.read\"");
        call(post("/api/v1/users/" + owner.id() + "/role-assignments").content("{\"roleId\":\"" + collegeReader + "\",\"orgUnitId\":\"" + college1 + "\"}"),
                admin, status().is2xxSuccessful());
        call(post("/api/v1/users/" + owner.id() + "/role-assignments/" + ownerAssignment + "/revoke").content("{\"reason\":\"moved to college 1\"}"), admin,
                status().is2xxSuccessful());
        mvc.perform(get("/api/v1/students").header("X-Api-Key", key)).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void ownerSuspensionBlocksAtOnceSuspendsTheClientAndNeverResumesAutomatically() throws Exception {
        JsonNode c = delegatedClient(owner, "\"student.read\"");
        String key = c.path("apiKey").asString();
        String id = c.path("client").path("id").asString();
        userStatus(owner, "SUSPEND");
        assertThat(keyStatus(key, "/api/v1/students")).isEqualTo(401);
        assertThat(keyCode(key, "/api/v1/students")).as("live check, before the lifecycle event").isEqualTo("api_client_owner_inactive");

        lifecycleEvent("user.suspended", owner.id());
        lifecycleEvent("user.suspended", owner.id());   // redelivery is harmless
        JsonNode v = call(get("/api/v1/api-clients/" + id), admin, status().isOk()).path("client");
        assertThat(v.path("status").asString()).isEqualTo("SUSPENDED");
        assertThat(v.path("suspendedReason").asString()).isEqualTo("owner_inactive");
        assertThat(audits("api_client.suspended")).isEqualTo(1);

        userStatus(owner, "REACTIVATE");
        lifecycleEvent("user.reactivated", owner.id());
        assertThat(keyCode(key, "/api/v1/students")).as("no automatic resumption").isEqualTo("api_client_suspended");
        call(post("/api/v1/api-clients/" + id + "/resume"), admin, status().isOk());
        assertThat(keyStatus(key, "/api/v1/students")).isEqualTo(200);
        assertThat(audits("api_client.resumed")).isEqualTo(1);

        userStatus(owner, "DEACTIVATE");
        lifecycleEvent("user.deactivated", owner.id());
        assertThat(keyCode(key, "/api/v1/students")).isEqualTo("api_client_suspended");
        call(post("/api/v1/api-clients/" + id + "/resume"), admin, status().isConflict());   // owner inactive: reassign instead
    }

    @Test
    void reassignmentRevalidatesTheNewOwnersCurrentPermissions() throws Exception {
        JsonNode c = delegatedClient(owner, "\"student.read\",\"org_unit.read\"");
        String id = c.path("client").path("id").asString();
        String key = c.path("apiKey").asString();
        userStatus(owner, "DEACTIVATE");
        lifecycleEvent("user.deactivated", owner.id());
        // faculty without the integration role lacks org_unit.read tenant-wide: refused
        call(put("/api/v1/api-clients/" + id + "/owner").content("{\"userId\":\"" + outsider.id() + "\"}"), admin, status().isConflict());
        JsonNode moved = call(put("/api/v1/api-clients/" + id + "/owner").content("{\"userId\":\"" + admin2.id() + "\"}"), admin, status().isOk());
        assertThat(moved.path("ownerUserId").asString()).isEqualTo(admin2.id().toString());
        assertThat(moved.path("status").asString()).isEqualTo("ACTIVE");
        assertThat(keyStatus(key, "/api/v1/students")).isEqualTo(200);
        String details = ownerJdbc().sql("SELECT details::text FROM audit_event WHERE tenant_id = ? AND action = 'api_client.owner_changed'")
                .param(org.tenant).query(String.class).single();
        assertThat(details).contains(owner.id().toString()).contains(admin2.id().toString());
        // detail shows what works now
        JsonNode d = call(get("/api/v1/api-clients/" + id), admin, status().isOk());
        assertThat(d.path("effectiveScopes").size()).isEqualTo(2);
        assertThat(d.path("lostScopes").size()).isZero();
        // narrowing scopes only
        long version = d.path("client").path("version").asLong();
        call(put("/api/v1/api-clients/" + id + "/scopes").content("{\"scopes\":[\"student.read\",\"user.read\"],\"version\":" + version + "}"), admin,
                status().isBadRequest());
        call(put("/api/v1/api-clients/" + id + "/scopes").content("{\"scopes\":[\"org_unit.read\"],\"version\":" + version + "}"), admin, status().isOk());
        assertThat(keyStatus(key, "/api/v1/students")).isEqualTo(403);
    }

    @Test
    void concurrentAuthorizationChangeTakesEffectForEveryLaterRequest() throws Exception {
        String key = delegatedClient(owner, "\"student.read\"").path("apiKey").asString();
        record Call(long startNanos, int status) {}
        List<Call> calls = Collections.synchronizedList(new ArrayList<>());
        AtomicBoolean stop = new AtomicBoolean();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        for (int i = 0; i < 6; i++) {
            pool.submit(() -> {
                while (!stop.get()) {
                    long t = System.nanoTime();
                    try {
                        calls.add(new Call(t, keyStatus(key, "/api/v1/students")));
                    } catch (Exception e) {
                        calls.add(new Call(t, -1));
                    }
                }
                return null;
            });
        }
        Thread.sleep(500);
        call(post("/api/v1/users/" + owner.id() + "/role-assignments/" + ownerAssignment + "/revoke").content("{\"reason\":\"concurrency\"}"), admin,
                status().is2xxSuccessful());
        long revokedAt = System.nanoTime();
        Thread.sleep(800);
        stop.set(true);
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        List<Call> before = calls.stream().filter(c -> c.startNanos() < revokedAt).toList();
        List<Call> after = calls.stream().filter(c -> c.startNanos() > revokedAt).toList();
        assertThat(before).isNotEmpty();
        assertThat(after).as("requests started after the revocation committed").isNotEmpty();
        assertThat(after).allSatisfy(c -> assertThat(c.status()).isEqualTo(403));
        assertThat(calls).noneMatch(c -> c.status() == -1 || c.status() >= 500);
    }

    // ============================================================================================ service identities

    private JsonNode identity(String envelope) throws Exception {
        return call(post("/api/v1/service-identities").content("""
                {"name":"SIS sync %s","purpose":"Nightly roster export to the SIS","businessCustodianId":"%s","technicalCustodianId":"%s","envelope":[%s]}"""
                .formatted(UUID.randomUUID().toString().substring(0, 6), admin.id(), admin2.id(), envelope)), admin, status().isCreated());
    }

    private long identityVersion(String id) throws Exception {
        return call(get("/api/v1/service-identities/" + id), admin, status().isOk()).path("identity").path("version").asLong();
    }

    private UUID readRole(String permissions) throws Exception {
        JsonNode role = call(post("/api/v1/roles").header("Idempotency-Key", "k-" + UUID.randomUUID()).content("""
                {"name":"Svc reader %s","permissions":[%s]}""".formatted(UUID.randomUUID().toString().substring(0, 6), permissions)), admin, status().isCreated());
        return UUID.fromString(role.path("role").path("id").asString());
    }

    @Test
    void serviceIdentityNeedsIndependentApprovalAndStaysInsideItsEnvelope() throws Exception {
        call(post("/api/v1/service-identities").content("""
                {"name":"Bad","purpose":"x","businessCustodianId":"%s","technicalCustodianId":"%s","envelope":["student.personal.read"]}"""
                .formatted(admin.id(), admin2.id())), admin, status().isBadRequest());
        call(post("/api/v1/service-identities").content("""
                {"name":"Bad2","purpose":"x","businessCustodianId":"%s","envelope":["student.read"]}""".formatted(admin.id())), admin, status().isBadRequest());
        // Q-01: one person cannot be both custodians — refused by the service …
        call(post("/api/v1/service-identities").content("""
                {"name":"Same","purpose":"x","businessCustodianId":"%s","technicalCustodianId":"%s","envelope":["student.read"]}"""
                .formatted(admin.id(), admin.id())), admin, status().isBadRequest());
        JsonNode created = identity("\"student.read\",\"org_unit.read\"");
        String id = created.path("id").asString();
        assertThat(created.path("status").asString()).isEqualTo("PENDING_APPROVAL");
        assertThat(created.path("scopeEnvelope").size()).isZero();
        // the proposer cannot approve
        call(post("/api/v1/service-identities/" + id + "/envelope/approve").content("{}"), admin, status().isConflict());
        assertThat(audits("service_identity.envelope_approval_denied")).isEqualTo(1);
        // a client cannot be registered before approval
        call(post("/api/v1/api-clients").content("{\"name\":\"Svc early\",\"scopes\":[\"student.read\"],\"serviceIdentityId\":\"" + id + "\"}"), admin,
                status().isConflict());
        JsonNode approved = call(post("/api/v1/service-identities/" + id + "/envelope/approve").content("{}"), admin2, status().isOk());
        assertThat(approved.path("status").asString()).isEqualTo("ACTIVE");
        assertThat(approved.path("approvedBy").asString()).isEqualTo(admin2.id().toString());

        // roles must fit the envelope (ceiling rule as well)
        UUID wide = readRole("\"student.read\",\"user.read\"");
        call(post("/api/v1/service-identities/" + id + "/roles").content("{\"roleId\":\"" + wide + "\"}"), admin, status().isBadRequest());
        UUID reader = readRole("\"student.read\"");
        JsonNode assignment = call(post("/api/v1/service-identities/" + id + "/roles").content("{\"roleId\":\"" + reader + "\"}"), admin, status().isCreated());

        call(post("/api/v1/api-clients").content("{\"name\":\"Svc bad\",\"scopes\":[\"user.read\"],\"serviceIdentityId\":\"" + id + "\"}"), admin,
                status().isBadRequest());
        JsonNode c = call(post("/api/v1/api-clients").content("{\"name\":\"Svc " + UUID.randomUUID().toString().substring(0, 4)
                + "\",\"scopes\":[\"student.read\",\"org_unit.read\"],\"serviceIdentityId\":\"" + id + "\"}"), admin, status().isCreated());
        String key = c.path("apiKey").asString();
        assertThat(c.path("client").path("kind").asString()).isEqualTo("SERVICE");
        assertThat(keyStatus(key, "/api/v1/students")).isEqualTo(200);
        assertThat(keyStatus(key, "/api/v1/org-units")).as("in envelope but no role grants it").isEqualTo(403);
        assertThat(keyStatus(key, "/api/v1/users")).isEqualTo(403);
        // the identity's grants do not depend on the people who created it
        userStatus(admin, admin2, "SUSPEND");     // the approver
        assertThat(keyStatus(key, "/api/v1/students")).isEqualTo(200);
        userStatus(admin, admin2, "REACTIVATE");

        // narrowing the envelope applies at once
        call(put("/api/v1/service-identities/" + id + "/envelope").content("{\"envelope\":[\"org_unit.read\"],\"version\":" + identityVersion(id) + "}"),
                admin, status().isOk());
        assertThat(keyStatus(key, "/api/v1/students")).isEqualTo(403);
        // widening again waits for another person's approval
        JsonNode proposed = call(put("/api/v1/service-identities/" + id + "/envelope").content("{\"envelope\":[\"org_unit.read\",\"student.read\"],\"version\":"
                + identityVersion(id) + "}"), admin, status().isOk());
        assertThat(proposed.path("proposedEnvelope").size()).isEqualTo(2);
        assertThat(keyStatus(key, "/api/v1/students")).isEqualTo(403);
        call(post("/api/v1/service-identities/" + id + "/envelope/approve").content("{}"), admin2, status().isOk());
        assertThat(keyStatus(key, "/api/v1/students")).isEqualTo(200);
        // revoking the role removes the permission on the next request
        call(delete("/api/v1/service-identities/" + id + "/roles/" + assignment.path("id").asString()), admin, status().isNoContent());
        assertThat(keyStatus(key, "/api/v1/students")).isEqualTo(403);
        // service identities are read-only like every API client
        assertThat(mvc.perform(post("/api/v1/students").header("X-Api-Key", key).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void identityWithoutValidCustodianFor30DaysIsSuspended() throws Exception {
        String id = identity("\"student.read\"").path("id").asString();
        call(post("/api/v1/service-identities/" + id + "/envelope/approve").content("{}"), admin2, status().isOk());
        UUID reader = readRole("\"student.read\"");
        call(post("/api/v1/service-identities/" + id + "/roles").content("{\"roleId\":\"" + reader + "\"}"), admin, status().isCreated());
        String key = call(post("/api/v1/api-clients").content("{\"name\":\"Svc c\",\"scopes\":[\"student.read\"],\"serviceIdentityId\":\"" + id + "\"}"), admin,
                status().isCreated()).path("apiKey").asString();

        TestOrg.Account custodian = admin2;
        userStatus(custodian, "DEACTIVATE");
        lifecycleEvent("user.deactivated", custodian.id());
        JsonNode v = call(get("/api/v1/service-identities/" + id), admin, status().isOk()).path("identity");
        assertThat(v.path("custodianInvalidSince").isNull()).isFalse();
        assertThat(keyStatus(key, "/api/v1/students")).as("still inside the 30-day grace period").isEqualTo(200);

        TenantContext.runInTenant(org.tenant, () -> identities.checkCustodians(Instant.now().plus(Duration.ofDays(29))));
        assertThat(keyStatus(key, "/api/v1/students")).isEqualTo(200);
        int suspended = TenantContext.callInTenant(org.tenant, () -> identities.checkCustodians(Instant.now().plus(Duration.ofDays(31))));
        assertThat(suspended).isEqualTo(1);
        assertThat(keyCode(key, "/api/v1/students")).isEqualTo("api_client_identity_inactive");
        assertThat(audits("service_identity.suspended")).isEqualTo(1);
        call(post("/api/v1/service-identities/" + id + "/resume"), admin, status().isConflict());   // no valid custodian yet
        call(put("/api/v1/service-identities/" + id + "/custodians").content("{\"businessCustodianId\":\"" + admin.id() + "\",\"technicalCustodianId\":\""
                + owner.id() + "\",\"version\":" + identityVersion(id) + "}"), admin, status().isOk());
        call(post("/api/v1/service-identities/" + id + "/resume"), admin, status().isOk());
        assertThat(keyStatus(key, "/api/v1/students")).isEqualTo(200);
    }

    @Test
    void serviceIdentitiesAreIsolatedPerOrganizationAndAudited() throws Exception {
        String id = identity("\"student.read\"").path("id").asString();
        TestOrg other = new TestOrg(ownerJdbc(), mvc, "ki59-b");
        TestOrg.Account otherAdmin = other.account("ORG_ADMIN", null);
        call(get("/api/v1/service-identities/" + id), otherAdmin, status().isNotFound());
        call(post("/api/v1/service-identities/" + id + "/envelope/approve").content("{}"), otherAdmin, status().isNotFound());
        assertThat(call(get("/api/v1/service-identities"), otherAdmin, status().isOk()).size()).isZero();
        // a role of another organization cannot be assigned
        UUID foreignRole = UUID.fromString(call(post("/api/v1/roles").header("Idempotency-Key", "k-" + UUID.randomUUID())
                .content("{\"name\":\"Foreign\",\"permissions\":[\"student.read\"]}"), otherAdmin, status().isCreated()).path("role").path("id").asString());
        call(post("/api/v1/service-identities/" + id + "/envelope/approve").content("{}"), admin2, status().isOk());
        call(post("/api/v1/service-identities/" + id + "/roles").content("{\"roleId\":\"" + foreignRole + "\"}"), admin, status().isNotFound());
        // only integration managers
        call(get("/api/v1/service-identities"), owner, status().isOk());   // owner's custom role holds integration.read/manage
        call(get("/api/v1/service-identities"), outsider, status().isForbidden());
        assertThat(audits("service_identity.created")).isEqualTo(1);
        assertThat(audits("service_identity.envelope_approved")).isEqualTo(1);
        String trail = ownerJdbc().sql("SELECT string_agg(details::text, '|') FROM audit_event WHERE tenant_id = ?").param(org.tenant).query(String.class).single();
        assertThat(trail).doesNotContain("ivk_");
        // … and by the database, even for a direct update
        assertThatThrownBy(() -> ownerJdbc().sql("UPDATE service_identity SET technical_custodian_id = business_custodian_id WHERE id = ?")
                .param(UUID.fromString(id)).update()).hasMessageContaining("service_identity_distinct_custodians");
    }
}
