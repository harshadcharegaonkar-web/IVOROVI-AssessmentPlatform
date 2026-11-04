package com.ivorovi.insights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ivorovi.support.IntegrationTest;
import com.ivorovi.support.TestOrg;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Increment 1.7 dashboards and reports: permission scoping, null sections, filters, pagination, export and audit. */
class InsightsIT extends IntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private TestOrg org;
    private UUID college1, college2;
    private TestOrg.Account admin, instAdmin, faculty, management;

    @BeforeEach
    void setUp() throws Exception {
        org = new TestOrg(ownerJdbc(), mvc, "ins");
        UUID uni = org.unit("UNIVERSITY", null, "U");
        college1 = org.unit("COLLEGE", uni, "C1");
        college2 = org.unit("COLLEGE", uni, "C2");
        org.unit("DEPARTMENT", college1, "D1");
        admin = org.account("ORG_ADMIN", null);
        instAdmin = org.account("INSTITUTION_ADMIN", college1);
        faculty = org.account("FACULTY", college1);
        management = org.account("MANAGEMENT", null);
        for (int i = 0; i < 3; i++) org.student(admin, college1, "Asha" + i, "Rao");
        for (int i = 0; i < 2; i++) org.student(admin, college2, "Ravi" + i, "Iyer");
    }

    private JsonNode json(MockHttpServletRequestBuilder r, TestOrg.Account who) throws Exception {
        return JSON.readTree(mvc.perform(r.header(HttpHeaders.AUTHORIZATION, who.bearer())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void executiveDashboardIsScopedAndOmitsSectionsWithoutPermission() throws Exception {
        JsonNode a = json(get("/api/v1/insights/executive"), admin);
        assertThat(a.path("students").path("total").asLong()).isEqualTo(5);
        assertThat(a.path("organization").path("colleges").asLong()).isEqualTo(2);
        assertThat(a.path("organization").path("departments").asLong()).isEqualTo(1);
        assertThat(a.path("imports").isObject()).isTrue();

        JsonNode i = json(get("/api/v1/insights/executive"), instAdmin);
        assertThat(i.path("students").path("total").asLong()).isEqualTo(3);   // only college 1
        assertThat(i.path("organization").path("colleges").asLong()).isEqualTo(1);

        JsonNode f = json(get("/api/v1/insights/executive"), faculty);
        assertThat(f.path("students").path("total").asLong()).isEqualTo(3);
        assertThat(f.path("imports").isObject()).as("faculty cannot import: section omitted, not zero").isFalse();
        assertThat(f.path("people").isObject()).isFalse();
    }

    @Test
    void studentDashboardHonoursScopeAndSubtreeFilter() throws Exception {
        assertThat(json(get("/api/v1/insights/students"), admin).path("total").asLong()).isEqualTo(5);
        assertThat(json(get("/api/v1/insights/students").param("orgUnitId", college2.toString()), admin).path("total").asLong()).isEqualTo(2);
        assertThat(json(get("/api/v1/insights/students"), instAdmin).path("total").asLong()).isEqualTo(3);
        // a subtree outside the caller's scope yields nothing (never other units' figures)
        assertThat(json(get("/api/v1/insights/students").param("orgUnitId", college2.toString()), instAdmin).path("total").asLong()).isZero();
        JsonNode trend = json(get("/api/v1/insights/students"), admin).path("admissionsByMonth");
        assertThat(trend.size()).isEqualTo(12);
    }

    @Test
    void sectionDashboardsRequireTheirPermission() throws Exception {
        mvc.perform(get("/api/v1/insights/imports").header(HttpHeaders.AUTHORIZATION, faculty.bearer())).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/insights/staff").header(HttpHeaders.AUTHORIZATION, faculty.bearer())).andExpect(status().isForbidden());
        json(get("/api/v1/insights/academic"), faculty);
        json(get("/api/v1/insights/staff"), admin);
        JsonNode imports = json(get("/api/v1/insights/imports"), admin);
        assertThat(imports.path("totalJobs").asLong()).isZero();
        mvc.perform(get("/api/v1/insights/executive")).andExpect(status().isUnauthorized());
    }

    @Test
    void reportsAreScopedPaginatedFilteredAndNeedReportPermission() throws Exception {
        mvc.perform(get("/api/v1/reports").header(HttpHeaders.AUTHORIZATION, faculty.bearer())).andExpect(status().isForbidden());
        JsonNode cat = json(get("/api/v1/reports"), instAdmin);
        assertThat(cat.findValuesAsString("key")).contains("students", "enrolments", "staff", "batches", "imports");

        JsonNode page = json(get("/api/v1/reports/students").param("size", "2").param("sort", "studentNo"), admin);
        assertThat(page.path("totalElements").asLong()).isEqualTo(5);
        assertThat(page.path("totalPages").asInt()).isEqualTo(3);
        assertThat(page.path("rows").size()).isEqualTo(2);
        assertThat(page.path("personalIncluded").asBoolean()).isFalse();
        assertThat(page.path("columns").findValuesAsString("key")).doesNotContain("dateOfBirth", "personalEmail", "mobile");

        assertThat(json(get("/api/v1/reports/students"), instAdmin).path("totalElements").asLong()).isEqualTo(3);
        assertThat(json(get("/api/v1/reports/students").param("orgUnitId", college2.toString()), admin).path("totalElements").asLong()).isEqualTo(2);
        assertThat(json(get("/api/v1/reports/students").param("status", "GRADUATED"), admin).path("totalElements").asLong()).isZero();

        mvc.perform(get("/api/v1/reports/students").param("status", "NOPE").header(HttpHeaders.AUTHORIZATION, admin.bearer())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/reports/students").param("from", "2026-05-01").param("to", "2026-01-01").header(HttpHeaders.AUTHORIZATION, admin.bearer()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/reports/unknown").header(HttpHeaders.AUTHORIZATION, admin.bearer())).andExpect(status().isNotFound());
        // management holds report.read but not student.import: the imports report is refused
        mvc.perform(get("/api/v1/reports/imports").header(HttpHeaders.AUTHORIZATION, management.bearer())).andExpect(status().isForbidden());
    }

    @Test
    void personalColumnsNeedPermissionAndAreAudited() throws Exception {
        // MANAGEMENT may read students but not their personal details
        mvc.perform(get("/api/v1/reports/students").param("includePersonal", "true").header(HttpHeaders.AUTHORIZATION, management.bearer()))
                .andExpect(status().isForbidden());
        JsonNode r = json(get("/api/v1/reports/students").param("includePersonal", "true"), admin);
        assertThat(r.path("personalIncluded").asBoolean()).isTrue();
        assertThat(r.path("columns").findValuesAsString("key")).contains("dateOfBirth", "mobile");
        assertThat(count("report.personal_viewed")).isEqualTo(1);
        // the staff report has no personal columns at all
        mvc.perform(get("/api/v1/reports/staff").param("includePersonal", "true").header(HttpHeaders.AUTHORIZATION, admin.bearer())).andExpect(status().isBadRequest());
    }

    @Test
    void exportsAreScopedAndAudited() throws Exception {
        org.student(admin, college1, "Meera", "Rao");
        var res = mvc.perform(get("/api/v1/reports/students/export").param("format", "CSV").header(HttpHeaders.AUTHORIZATION, instAdmin.bearer()))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store")).andReturn().getResponse();
        String csv = new String(res.getContentAsByteArray(), StandardCharsets.UTF_8);
        List<String> lines = csv.lines().filter(l -> !l.isBlank()).toList();
        assertThat(lines).hasSize(1 + 4);                       // header + the 4 college-1 students only
        assertThat(csv).doesNotContain("Ravi").contains("Meera");   // (formula neutralisation: TabularFileTest)
        byte[] xlsx = mvc.perform(get("/api/v1/reports/students/export").param("format", "XLSX").header(HttpHeaders.AUTHORIZATION, admin.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(xlsx, 0, 2, StandardCharsets.ISO_8859_1)).isEqualTo("PK");
        assertThat(count("report.exported")).isEqualTo(2);
        String details = ownerJdbc().sql("SELECT string_agg(details::text, '|') FROM audit_event WHERE tenant_id = ? AND action = 'report.exported'")
                .param(org.tenant).query(String.class).single();
        assertThat(details).contains("\"rows\"").doesNotContain("Asha").doesNotContain("Rao");
        mvc.perform(get("/api/v1/reports/students/export").param("format", "PDF").header(HttpHeaders.AUTHORIZATION, admin.bearer())).andExpect(status().isBadRequest());
    }

    @Test
    void apiResponsesExposeNoOtherOrganizationsData() throws Exception {
        TestOrg other = new TestOrg(ownerJdbc(), mvc, "ins-b");
        UUID c = other.unit("COLLEGE", null, "X");
        TestOrg.Account otherAdmin = other.account("ORG_ADMIN", null);
        other.student(otherAdmin, c, "Zed", "Other");
        assertThat(json(get("/api/v1/insights/executive"), otherAdmin).path("students").path("total").asLong()).isEqualTo(1);
        assertThat(json(get("/api/v1/reports/students"), otherAdmin).path("totalElements").asLong()).isEqualTo(1);
        // a unit id of another organization is simply invisible
        assertThat(json(get("/api/v1/insights/students").param("orgUnitId", college1.toString()), otherAdmin).path("total").asLong()).isZero();
        mvc.perform(get("/api/v1/insights/students").header(HttpHeaders.AUTHORIZATION, admin.bearer())).andExpect(jsonPath("$.total").value(5));
    }

    private long count(String action) {
        return ownerJdbc().sql("SELECT count(*) FROM audit_event WHERE tenant_id = ? AND action = ?").params(org.tenant, action).query(Long.class).single();
    }
}
