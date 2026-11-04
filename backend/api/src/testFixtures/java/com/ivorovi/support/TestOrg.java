package com.ivorovi.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ivorovi.platform.outbox.IntegrationEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * A throw-away organization for one test class: units, accounts with system roles, students created through the API,
 * and access to the outbox events it produced (consumers are called directly in tests; the broker publisher is off).
 */
public final class TestOrg {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public final UUID tenant;
    private final JdbcClient owner;
    private final MockMvc mvc;
    private int students;

    public TestOrg(JdbcClient owner, MockMvc mvc, String label) {
        this.owner = owner;
        this.mvc = mvc;
        this.tenant = UUID.randomUUID();
        owner.sql("INSERT INTO tenant (id, code, name, status) VALUES (?, ?, ?, 'ACTIVE')").params(tenant, label + "-" + tenant.toString().substring(0, 8), label + " org").update();
    }

    public UUID unit(String type, UUID parent, String code) {
        UUID id = UUID.randomUUID();
        String l = id.toString().replace("-", "");
        String path = parent == null ? l : owner.sql("SELECT path::text FROM org_unit WHERE id = ?").param(parent).query(String.class).single() + "." + l;
        owner.sql("INSERT INTO org_unit (id, tenant_id, parent_id, unit_type, code, name, path) VALUES (?, ?, ?, ?, ?, ?, CAST(? AS ltree))")
                .params(id, tenant, parent, type, code, "Unit " + code, path).update();
        return id;
    }

    public record Account(UUID id, Fixture.Caller caller) {
        public String bearer() { return "Bearer " + TestTokens.forCaller(caller); }
    }

    /** Active account with a system role at the unit ({@code null} = organization-wide). */
    public Account account(String role, UUID unit) {
        UUID id = UUID.randomUUID();
        owner.sql("INSERT INTO user_account (id, tenant_id, external_subject, email, display_name, given_name) VALUES (?, ?, ?, ?, ?, 'User')")
                .params(id, tenant, "sub-" + id, id + "@org.test", role + " " + id.toString().substring(0, 4)).update();
        UUID roleId = owner.sql("SELECT id FROM role WHERE code = ? AND tenant_id IS NULL").param(role).query(UUID.class).single();
        owner.sql("INSERT INTO role_assignment (id, tenant_id, user_account_id, role_id, scope_type, scope_id) VALUES (?, ?, ?, ?, ?, ?)")
                .params(UUID.randomUUID(), tenant, id, roleId, unit == null ? "TENANT" : "ORG_UNIT", unit == null ? tenant : unit).update();
        return new Account(id, new Fixture.Caller("sub-" + id, tenant));
    }

    /** Creates a student through the API (so audit, outbox and events are real); returns its id. */
    public UUID student(Account by, UUID institution, String first, String last) throws Exception {
        String no = "S-" + (++students) + "-" + UUID.randomUUID().toString().substring(0, 4);
        String body = mvc.perform(post("/api/v1/students").header(HttpHeaders.AUTHORIZATION, by.bearer()).header("Idempotency-Key", "k-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"institutionId\":\"" + institution + "\",\"studentNo\":\"" + no + "\",\"firstName\":\"" + first + "\",\"lastName\":\"" + last + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JSON.readTree(body).path("student").path("id").asString());
    }

    /** Outbox events of this organization created at or after {@code since}, oldest first. */
    public List<IntegrationEvent> events(Instant since) {
        return owner.sql("""
                SELECT id, tenant_id, aggregate_type, aggregate_id, event_type, payload::text AS payload, actor_id, created_at FROM outbox_event
                WHERE tenant_id = ? AND created_at >= ? ORDER BY created_at, id""").params(tenant, java.sql.Timestamp.from(since))
                .query((rs, n) -> new IntegrationEvent(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getObject(7, UUID.class), rs.getTimestamp(8).toInstant())).list();
    }
}
