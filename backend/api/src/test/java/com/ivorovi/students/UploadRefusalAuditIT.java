package com.ivorovi.students;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ivorovi.support.Fixture;
import com.ivorovi.support.IntegrationTest;
import com.ivorovi.support.TestTokens;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * KI-53: refused document and import uploads are recorded even though the request's transaction rolls back —
 * authenticated refusals in the tenant's audit trail (actor, tenant, institution, time, reason, outcome, correlation ID),
 * unauthenticated ones in the platform security-event log. File contents and personal values are never recorded.
 */
class UploadRefusalAuditIT extends IntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** Marker inside every uploaded file: must never appear in any audit or security record. */
    private static final String SECRET_CONTENT = "CONFIDENTIAL-CONTENT-7f3a";

    private UUID tenant, college, student;
    private Fixture.Caller admin, faculty;

    @BeforeEach
    void setUp() throws Exception {
        tenant = UUID.randomUUID();
        ownerJdbc().sql("INSERT INTO tenant (id, code, name, status) VALUES (?, ?, 'Upload audit org', 'ACTIVE')").params(tenant, "upa-" + tenant.toString().substring(0, 8)).update();
        UUID uni = unit("UNIVERSITY", null, "U");
        college = unit("COLLEGE", uni, "C");
        admin = account("ORG_ADMIN", null);
        faculty = account("FACULTY", college);
        String body = mvc.perform(post("/api/v1/students").header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.forCaller(admin))
                        .header("Idempotency-Key", "k-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"institutionId\":\"" + college + "\",\"studentNo\":\"UPA-001\",\"firstName\":\"Asha\",\"lastName\":\"Rao\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        student = UUID.fromString(JSON.readTree(body).path("student").path("id").asString());
    }

    private UUID unit(String type, UUID parent, String code) {
        UUID id = UUID.randomUUID();
        String label = id.toString().replace("-", "");
        String path = parent == null ? label : ownerJdbc().sql("SELECT path::text FROM org_unit WHERE id = ?").param(parent).query(String.class).single() + "." + label;
        ownerJdbc().sql("INSERT INTO org_unit (id, tenant_id, parent_id, unit_type, code, name, path) VALUES (?, ?, ?, ?, ?, ?, CAST(? AS ltree))")
                .params(id, tenant, parent, type, code, "Unit " + code, path).update();
        return id;
    }

    private Fixture.Caller account(String role, UUID unit) {
        UUID id = UUID.randomUUID();
        ownerJdbc().sql("INSERT INTO user_account (id, tenant_id, external_subject, email, display_name, given_name) VALUES (?, ?, ?, ?, ?, 'User')")
                .params(id, tenant, "sub-" + id, id + "@upa.test", role + " user").update();
        UUID roleId = ownerJdbc().sql("SELECT id FROM role WHERE code = ? AND tenant_id IS NULL").param(role).query(UUID.class).single();
        ownerJdbc().sql("INSERT INTO role_assignment (id, tenant_id, user_account_id, role_id, scope_type, scope_id) VALUES (?, ?, ?, ?, ?, ?)")
                .params(UUID.randomUUID(), tenant, id, roleId, unit == null ? "TENANT" : "ORG_UNIT", unit == null ? tenant : unit).update();
        return new Fixture.Caller("sub-" + id, tenant);
    }

    private MockMultipartHttpServletRequestBuilder document(String name, byte[] content) {
        MockMultipartHttpServletRequestBuilder r = multipart("/api/v1/students/" + student + "/documents")
                .file(new MockMultipartFile("file", name, "application/octet-stream", content));
        r.param("category", "IDENTITY_PROOF");
        return r;
    }

    private static byte[] file(String header) {
        return (header + "\n" + SECRET_CONTENT + "\n").getBytes(StandardCharsets.ISO_8859_1);
    }

    private List<Map<String, Object>> audits(String action) {
        return ownerJdbc().sql("SELECT actor_id, actor_subject, outcome, details::text AS details, trace_id, occurred_at FROM audit_event WHERE tenant_id = ? AND action = ? ORDER BY seq")
                .params(tenant, action).query().listOfRows();
    }

    private static JsonNode details(Map<String, Object> row) {
        return JSON.readTree(String.valueOf(row.get("details")));
    }

    @Test
    void authenticatedRefusalsAreAuditedWithInstitutionReasonAndCorrelation() throws Exception {
        // unsupported file type (content check), refused by the document service
        String trace = mvc.perform(document("id.txt", file("plain text")).header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.forCaller(admin)))
                .andExpect(status().isUnsupportedMediaType()).andReturn().getResponse().getHeader("X-Trace-Id");
        List<Map<String, Object>> rows = audits("student.document.upload_refused");
        assertThat(rows).hasSize(1);
        Map<String, Object> r = rows.getFirst();
        assertThat(r.get("outcome")).isEqualTo("FAILURE");
        assertThat(r.get("actor_id")).isNotNull();
        assertThat(r.get("occurred_at")).isNotNull();
        assertThat(r.get("trace_id")).isNotNull();
        if (trace != null) assertThat(r.get("trace_id")).isEqualTo(trace);
        JsonNode d = details(r);
        assertThat(d.path("reason").asString()).isEqualTo("unsupported_file_type");
        assertThat(d.path("institutionId").asString()).isEqualTo(college.toString());
        assertThat(d.path("studentNo").asString()).isEqualTo("UPA-001");
        assertThat(d.path("status").asString()).isEqualTo("415");

        // permission denied (faculty may not manage documents): recorded as DENIED
        mvc.perform(document("id.pdf", file("%PDF-1.4")).header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.forCaller(faculty)))
                .andExpect(status().isForbidden());
        List<Map<String, Object>> denied = ownerJdbc().sql("SELECT outcome, details::text AS details FROM audit_event WHERE tenant_id = ? "
                + "AND action IN ('student.document.upload_refused', 'upload.refused') AND outcome = 'DENIED'").param(tenant).query().listOfRows();
        assertThat(denied).hasSize(1);
        assertThat(details(denied.getFirst()).path("reason").asString()).isEqualTo("forbidden");

        // missing request part, refused by the framework before the service runs
        mvc.perform(multipart("/api/v1/students/" + student + "/documents").file(new MockMultipartFile("other", "x.pdf", "application/pdf", file("%PDF-1.4")))
                .param("category", "IDENTITY_PROOF").header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.forCaller(admin))).andExpect(status().is4xxClientError());
        assertThat(audits("upload.refused")).hasSize(2);
        JsonNode framework = details(audits("upload.refused").getLast());
        assertThat(framework.path("path").asString()).isEqualTo("/api/v1/students/" + student + "/documents");
        assertThat(framework.path("reason").asString()).isEqualTo("malformed_request");

        // refused import uploads keep their specific record and are not recorded twice
        mvc.perform(multipart("/api/v1/student-imports").file(new MockMultipartFile("file", "list.xlsm", "application/octet-stream", new byte[] {'P', 'K', 3, 4}))
                .param("institutionId", college.toString()).header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.forCaller(admin)))
                .andExpect(status().isUnsupportedMediaType());
        assertThat(audits("student.import.rejected")).hasSize(1);
        assertThat(audits("upload.refused")).hasSize(2);

        // no file contents or names anywhere in the trail
        String trail = String.join("\n", ownerJdbc().sql("SELECT details::text FROM audit_event WHERE tenant_id = ?").param(tenant).query(String.class).list());
        assertThat(trail).doesNotContain(SECRET_CONTENT).doesNotContain("id.txt").doesNotContain("list.xlsm");
    }

    @Test
    void unauthenticatedRefusalsAreRecordedAsSecurityEvents() throws Exception {
        long before = count();
        mvc.perform(document("id.pdf", file("%PDF-1.4"))).andExpect(status().isUnauthorized());
        mvc.perform(document("id.pdf", file("%PDF-1.4")).header(HttpHeaders.AUTHORIZATION, "Bearer not-a-valid-token")).andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/v1/student-imports").file(new MockMultipartFile("file", "list.csv", "text/csv", file("A,B")))
                .param("institutionId", college.toString())).andExpect(status().isUnauthorized());
        assertThat(count()).isEqualTo(before + 3);
        List<Map<String, Object>> rows = ownerJdbc().sql("SELECT method, path, status, reason, trace_id, client_ip, details::text AS details FROM security_event "
                + "WHERE path LIKE ? OR path = '/api/v1/student-imports' ORDER BY occurred_at DESC LIMIT 3").param("%" + student + "%").query().listOfRows();
        assertThat(rows).extracting(m -> m.get("reason")).contains("missing_token", "invalid_token");
        for (Map<String, Object> r : rows) {
            assertThat(r.get("method")).isEqualTo("POST");
            assertThat(r.get("status")).isEqualTo(401);
            assertThat(r.get("trace_id")).isNotNull();
            assertThat(String.valueOf(r.get("details"))).doesNotContain(SECRET_CONTENT).doesNotContain("id.pdf");
        }
        // the application role cannot change security events
        assertThat(rawAppJdbc().sql("SELECT count(*) FROM security_event").query(Long.class).single()).isZero();   // invisible outside platform context
    }

    private long count() {
        return ownerJdbc().sql("SELECT count(*) FROM security_event").query(Long.class).single();
    }
}
