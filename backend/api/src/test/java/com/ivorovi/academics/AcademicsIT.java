package com.ivorovi.academics;

import static com.ivorovi.support.Fixture.BELA_ORG_ADMIN_B;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ivorovi.support.Fixture;
import com.ivorovi.support.IntegrationTest;
import com.ivorovi.support.TestTokens;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Increment 1.4: academic structure. Fresh tenant per test:
 * <pre>
 *   University U ─ College A ─ Campus K ─ Department D1 (CSE)
 *                │           └ Department D2 (MECH)
 *                └ College B ─ Department DB
 * </pre>
 * with an organization administrator, a college-A administrator, a department head of D1, faculty of D1 and a
 * college-B administrator, plus staff profiles (teaching and non-teaching) in both colleges.
 */
class AcademicsIT extends IntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private UUID tenant;
    private UUID university, collegeA, campusK, d1, d2, collegeB, dB;
    private Fixture.Caller admin, collegeAdminA, headD1, facultyD1, collegeAdminB;
    private UUID teacherA, teacherA2, clerkA, teacherB;

    @BeforeEach
    void freshTenant() {
        tenant = UUID.randomUUID();
        ownerJdbc().sql("INSERT INTO tenant (id, code, name, status) VALUES (?, ?, 'Academics test org', 'ACTIVE')")
                .params(tenant, "acad-" + tenant.toString().substring(0, 8)).update();
        university = unit("UNIVERSITY", null, "U");
        collegeA = unit("COLLEGE", university, "A");
        campusK = unit("CAMPUS", collegeA, "K");
        d1 = unit("DEPARTMENT", campusK, "CSE");
        d2 = unit("DEPARTMENT", collegeA, "MECH");
        collegeB = unit("COLLEGE", university, "B");
        dB = unit("DEPARTMENT", collegeB, "DB");
        admin = account("ORG_ADMIN", null);
        collegeAdminA = account("INSTITUTION_ADMIN", collegeA);
        headD1 = account("DEPARTMENT_HEAD", d1);
        facultyD1 = account("FACULTY", d1);
        collegeAdminB = account("INSTITUTION_ADMIN", collegeB);
        teacherA = staff("T-A1", "FACULTY", "ACTIVE", d1);
        teacherA2 = staff("T-A2", "FACULTY", "ACTIVE", d2);
        clerkA = staff("C-A1", "ADMINISTRATIVE_STAFF", "ACTIVE", d1);
        teacherB = staff("T-B1", "FACULTY", "ACTIVE", dB);
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private UUID unit(String type, UUID parent, String code) {
        UUID id = UUID.randomUUID();
        String label = id.toString().replace("-", "");
        String path = parent == null ? label
                : ownerJdbc().sql("SELECT path::text FROM org_unit WHERE id = ?").param(parent).query(String.class).single() + "." + label;
        ownerJdbc().sql("INSERT INTO org_unit (id, tenant_id, parent_id, unit_type, code, name, path) VALUES (?, ?, ?, ?, ?, ?, CAST(? AS ltree))")
                .params(id, tenant, parent, type, code, "Unit " + code, path).update();
        return id;
    }

    private Fixture.Caller account(String roleCode, UUID unit) {
        UUID id = UUID.randomUUID();
        String subject = "sub-" + id;
        ownerJdbc().sql("INSERT INTO user_account (id, tenant_id, external_subject, email, display_name, given_name) VALUES (?, ?, ?, ?, ?, 'User')")
                .params(id, tenant, subject, id + "@academics.test", roleCode + " user").update();
        UUID role = ownerJdbc().sql("SELECT id FROM role WHERE code = ? AND tenant_id IS NULL").param(roleCode).query(UUID.class).single();
        ownerJdbc().sql("INSERT INTO role_assignment (id, tenant_id, user_account_id, role_id, scope_type, scope_id) VALUES (?, ?, ?, ?, ?, ?)")
                .params(UUID.randomUUID(), tenant, id, role, unit == null ? "TENANT" : "ORG_UNIT", unit == null ? tenant : unit).update();
        return new Fixture.Caller(subject, tenant);
    }

    private UUID staff(String no, String category, String employment, UUID unit) {
        UUID id = UUID.randomUUID();
        ownerJdbc().sql("INSERT INTO staff_profile (id, tenant_id, staff_no, given_name, family_name, category, employment_status) VALUES (?, ?, ?, ?, 'Teacher', ?, ?)")
                .params(id, tenant, no, "Staff " + no, category, employment).update();
        ownerJdbc().sql("INSERT INTO org_assignment (id, tenant_id, staff_profile_id, org_unit_id, is_primary) VALUES (?, ?, ?, ?, true)")
                .params(UUID.randomUUID(), tenant, id, unit).update();
        return id;
    }

    private ResultActions as(Fixture.Caller c, MockHttpServletRequestBuilder r) throws Exception {
        return mvc.perform(r.header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.forCaller(c)));
    }

    private ResultActions send(Fixture.Caller c, MockHttpServletRequestBuilder r, String body) throws Exception {
        return as(c, r.contentType(MediaType.APPLICATION_JSON).content(body).header("Idempotency-Key", "k-" + UUID.randomUUID()));
    }

    private static UUID id(ResultActions r) throws Exception {
        JsonNode n = JSON.readTree(r.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        JsonNode inner = n.has("id") ? n : n.has("subject") ? n.path("subject") : n.path("year");
        return UUID.fromString(inner.path("id").asString());
    }

    private static JsonNode body(ResultActions r) throws Exception {
        return JSON.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private long version(String table, UUID id) {
        return ownerJdbc().sql("SELECT version FROM " + table + " WHERE id = ?").param(id).query(Long.class).single();
    }

    private ResultActions program(Fixture.Caller c, String code, UUID owner) throws Exception {
        return send(c, post("/api/v1/academics/programs"), """
                {"code":"%s","name":"Bachelor of Technology %s","qualificationLevel":"UNDERGRADUATE","awardedQualification":"B.Tech",
                 "ownerOrgUnitId":"%s","termSystem":"SEMESTER","durationYears":4}""".formatted(code, code, owner));
    }

    private ResultActions course(Fixture.Caller c, UUID program, String code, UUID dept) throws Exception {
        return send(c, post("/api/v1/academics/courses"), """
                {"code":"%s","name":"Computer Science %s","programId":"%s","departmentId":"%s","specialization":"AI"}""".formatted(code, code, program, dept));
    }

    private ResultActions subject(Fixture.Caller c, String code, UUID owner) throws Exception {
        return send(c, post("/api/v1/academics/subjects"), """
                {"code":"%s","name":"Subject %s","credits":4,"delivery":"THEORY","category":"CORE","ownerOrgUnitId":"%s"}""".formatted(code, code, owner));
    }

    private ResultActions curriculum(Fixture.Caller c, UUID course, UUID subject, int term) throws Exception {
        return send(c, post("/api/v1/academics/courses/" + course + "/curriculum"), "{\"subjectId\":\"" + subject + "\",\"termNumber\":" + term + "}");
    }

    private ResultActions year(Fixture.Caller c, String code, UUID owner, String start, String end) throws Exception {
        return send(c, post("/api/v1/academics/academic-years"), """
                {"code":"%s","name":"Academic year %s",%s"startDate":"%s","endDate":"%s"}"""
                .formatted(code, code, owner == null ? "" : "\"ownerOrgUnitId\":\"" + owner + "\",", start, end));
    }

    private ResultActions term(Fixture.Caller c, UUID year, int seq, String start, String end, String extra) throws Exception {
        return send(c, post("/api/v1/academics/academic-years/" + year + "/terms"), """
                {"sequence":%d,"name":"Semester %d","startDate":"%s","endDate":"%s"%s}""".formatted(seq, seq, start, end, extra));
    }

    private ResultActions batch(Fixture.Caller c, UUID course, UUID year, String code, String section) throws Exception {
        return send(c, post("/api/v1/academics/batches"), """
                {"code":"%s","name":"Batch %s","courseId":"%s","academicYearId":"%s","admissionYear":2026,%s"capacity":60}"""
                .formatted(code, code, course, year, section == null ? "" : "\"section\":\"" + section + "\","));
    }

    private ResultActions offering(Fixture.Caller c, UUID subject, UUID batch, UUID term, int termNumber) throws Exception {
        return send(c, post("/api/v1/academics/subject-offerings"), """
                {"subjectId":"%s","batchId":"%s","termId":"%s","termNumber":%d}""".formatted(subject, batch, term, termNumber));
    }

    private ResultActions assign(Fixture.Caller c, UUID offering, UUID staff, Boolean primary) throws Exception {
        return send(c, post("/api/v1/academics/subject-offerings/" + offering + "/faculty"),
                "{\"staffProfileId\":\"" + staff + "\"" + (primary == null ? "" : ",\"primary\":" + primary) + "}");
    }

    private ResultActions setStatus(Fixture.Caller c, String path, String table, UUID id, String status, String reason) throws Exception {
        return send(c, post(path + "/" + id + "/status"), "{\"status\":\"" + status + "\",\"version\":" + version(table, id)
                + (reason == null ? "" : ",\"reason\":\"" + reason + "\"") + "}");
    }

    private long audits(String action) {
        return ownerJdbc().sql("SELECT count(*) FROM audit_event WHERE tenant_id = ? AND action = ?").params(tenant, action).query(Long.class).single();
    }

    /** A complete chain at college A: program → course (D1) → subject in term 1 → active year and term → batch → offering. */
    private record Chain(UUID program, UUID course, UUID subject, UUID year, UUID term, UUID batch, UUID offering) {}

    private Chain chain() throws Exception {
        UUID program = id(program(admin, "BTECH", collegeA));
        UUID course = id(course(admin, program, "CSE", d1));
        UUID subject = id(subject(admin, "CS101", collegeA));
        curriculum(admin, course, subject, 1).andExpect(status().isOk());
        UUID year = id(year(admin, "AY2026", null, "2026-07-01", "2027-06-30"));
        UUID term = id(term(admin, year, 1, "2026-07-15", "2026-12-15", ""));
        setStatus(admin, "/api/v1/academics/academic-years", "academic_year", year, "ACTIVE", null).andExpect(status().isOk());
        setStatus(admin, "/api/v1/academics/academic-terms", "academic_term", term, "ACTIVE", null).andExpect(status().isOk());
        UUID batch = id(batch(admin, course, year, "CSE-2026-A", "A"));
        UUID offering = id(offering(admin, subject, batch, term, 1));
        return new Chain(program, course, subject, year, term, batch, offering);
    }

    private int[] concurrently(Callable<Integer> a, Callable<Integer> b) throws Exception {
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var fa = pool.submit(() -> { start.await(); return a.call(); });
            var fb = pool.submit(() -> { start.await(); return b.call(); });
            start.countDown();
            return new int[] {fa.get(30, TimeUnit.SECONDS), fb.get(30, TimeUnit.SECONDS)};
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------------------------------------ tests

    @Nested
    class Structure {

        @Test
        void programsCoursesAndSubjectsHaveAFullLifecycle() throws Exception {
            UUID p = id(program(admin, "BTECH", collegeA).andExpect(jsonPath("$.termsPerYear").value(2)).andExpect(jsonPath("$.totalTerms").value(8))
                    .andExpect(jsonPath("$.ownerType").value("COLLEGE")));
            UUID c = id(course(admin, p, "CSE", d1).andExpect(jsonPath("$.effectiveTerms").value(8)).andExpect(jsonPath("$.departmentName").value("Unit CSE")));
            UUID s = id(subject(admin, "CS101", collegeA));
            UUID s2 = id(subject(admin, "CS201", collegeA));
            curriculum(admin, c, s, 1).andExpect(status().isOk()).andExpect(jsonPath("$[0].subjectCode").value("CS101"));
            curriculum(admin, c, s2, 3).andExpect(status().isOk());
            // the same subject master is reused by a second course, not copied
            UUID c2 = id(course(admin, p, "MEC", d2));
            curriculum(admin, c2, s, 2).andExpect(status().isOk());
            as(admin, get("/api/v1/academics/subjects/" + s)).andExpect(jsonPath("$.subject.courseCount").value(2))
                    .andExpect(jsonPath("$.curriculum.length()").value(2));
            assertThat(ownerJdbc().sql("SELECT count(*) FROM subject WHERE tenant_id = ?").param(tenant).query(Long.class).single()).isEqualTo(2);
            // prerequisites, cycles refused
            send(admin, post("/api/v1/academics/subjects/" + s2 + "/prerequisites"), "{\"prerequisiteId\":\"" + s + "\"}").andExpect(status().isOk())
                    .andExpect(jsonPath("$.prerequisites[0].code").value("CS101"));
            send(admin, post("/api/v1/academics/subjects/" + s + "/prerequisites"), "{\"prerequisiteId\":\"" + s2 + "\"}")
                    .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("prerequisite_cycle"));
            // edit with optimistic version
            send(admin, patch("/api/v1/academics/programs/" + p), """
                    {"version":%d,"code":"BTECH","name":"B.Tech (Hons)","qualificationLevel":"UNDERGRADUATE","termSystem":"SEMESTER","durationYears":4}"""
                    .formatted(version("program", p))).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("B.Tech (Hons)"));
            send(admin, patch("/api/v1/academics/programs/" + p), """
                    {"version":0,"code":"BTECH","name":"Stale","qualificationLevel":"UNDERGRADUATE","termSystem":"SEMESTER","durationYears":4}""")
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("concurrent_modification"));
            // cannot shrink below the curriculum
            send(admin, patch("/api/v1/academics/programs/" + p), """
                    {"version":%d,"code":"BTECH","name":"Short","qualificationLevel":"UNDERGRADUATE","termSystem":"SEMESTER","durationYears":1}"""
                    .formatted(version("program", p))).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("terms_in_use"));
            // deactivate and reactivate (records kept); inactive programs take no new courses
            setStatus(admin, "/api/v1/academics/programs", "program", p, "INACTIVE", "Programme withdrawn").andExpect(jsonPath("$.status").value("INACTIVE"));
            course(admin, p, "EEE", d1).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("program_inactive"));
            setStatus(admin, "/api/v1/academics/programs", "program", p, "ACTIVE", null).andExpect(jsonPath("$.status").value("ACTIVE"));
            // listing, search, filters, sort, pagination
            as(admin, get("/api/v1/academics/programs?q=hons&sort=code&direction=desc&size=5")).andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.items[0].courseCount").value(2));
            as(admin, get("/api/v1/academics/courses?programId=" + p + "&sort=code")).andExpect(jsonPath("$.items[0].code").value("CSE"));
            as(admin, get("/api/v1/academics/subjects?courseId=" + c2)).andExpect(jsonPath("$.totalElements").value(1));
            assertThat(audits("academics.program.created")).isEqualTo(1);
            assertThat(audits("academics.program.deactivated")).isEqualTo(1);
            assertThat(audits("academics.curriculum.added")).isEqualTo(3);
            assertThat(audits("academics.subject.prerequisite_added")).isEqualTo(1);
        }

        @Test
        void invalidStructureIsRefused() throws Exception {
            program(admin, "X", d1).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("invalid_owner"));
            program(admin, "bad code!", collegeA).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_code"));
            send(admin, post("/api/v1/academics/programs"), """
                    {"code":"Q","name":"Quarterly","qualificationLevel":"DIPLOMA","ownerOrgUnitId":"%s","termSystem":"QUARTER","termsPerYear":2,"durationYears":1}"""
                    .formatted(collegeA)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_terms_per_year"));
            UUID trimester = id(send(admin, post("/api/v1/academics/programs"), """
                    {"code":"TRI","name":"Trimester MBA","qualificationLevel":"POSTGRADUATE","ownerOrgUnitId":"%s","termSystem":"TRIMESTER","durationYears":2}"""
                    .formatted(collegeA)).andExpect(jsonPath("$.totalTerms").value(6)));
            UUID p = id(program(admin, "BTECH", collegeA));
            program(admin, "btech", collegeA).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("code_in_use"));
            program(admin, "BTECH", collegeB).andExpect(status().isCreated());            // codes are per institution
            course(admin, p, "CSE", dB).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("department_outside_institution"));
            send(admin, post("/api/v1/academics/courses"), """
                    {"code":"LONG","name":"Too long","programId":"%s","departmentId":"%s","totalTerms":9}""".formatted(p, d1))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_total_terms"));
            UUID c = id(course(admin, trimester, "MBA", d2));
            UUID foreign = id(subject(admin, "B101", collegeB));
            curriculum(admin, c, foreign, 1).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("subject_outside_institution"));
            UUID shared = id(subject(admin, "UNI101", university));                   // university subjects are shared by its colleges
            curriculum(admin, c, shared, 1).andExpect(status().isOk());
            curriculum(admin, c, shared, 7).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("invalid_term_number"));
            curriculum(admin, c, shared, 1).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("curriculum_entry_exists"));
            send(admin, post("/api/v1/academics/subjects"), """
                    {"code":"N","name":"Negative","credits":-1,"delivery":"THEORY","category":"CORE","ownerOrgUnitId":"%s"}""".formatted(collegeA))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_credits"));
        }
    }

    @Nested
    class Calendar {

        @Test
        void yearsAndTermsFollowDateAndStatusRules() throws Exception {
            UUID y = id(year(admin, "AY2026", null, "2026-07-01", "2027-06-30"));
            year(admin, "AY2026B", null, "2027-01-01", "2027-12-31").andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("year_overlap"));
            year(admin, "AYCA", collegeA, "2026-06-01", "2027-05-31").andExpect(status().isCreated());   // another owner may overlap
            year(admin, "BAD", null, "2028-07-01", "2028-06-30").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_dates"));
            year(admin, "DEPT", d1, "2030-07-01", "2031-06-30").andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("invalid_owner"));

            // any number of terms; inside the year; never overlapping; windows checked
            UUID t1 = id(term(admin, y, 1, "2026-07-01", "2026-10-31", ",\"registrationStart\":\"2026-06-15\",\"registrationEnd\":\"2026-07-10\""));
            id(term(admin, y, 2, "2026-11-01", "2027-02-28", ",\"examStart\":\"2027-02-10\",\"examEnd\":\"2027-02-25\""));
            id(term(admin, y, 3, "2027-03-01", "2027-06-30", ""));
            term(admin, y, 4, "2027-06-01", "2027-07-15", "").andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("term_outside_year"));
            term(admin, y, 4, "2027-02-01", "2027-03-15", "").andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("term_overlap"));
            UUID y2 = id(year(admin, "AY2028", null, "2028-07-01", "2029-06-30"));
            term(admin, y2, 1, "2028-07-01", "2028-12-31", ",\"examStart\":\"2029-01-05\",\"examEnd\":\"2029-01-10\"")
                    .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("invalid_exam_window"));
            term(admin, y2, 1, "2028-07-01", "2028-12-31", ",\"registrationStart\":\"2028-07-01\",\"registrationEnd\":\"2029-01-10\"")
                    .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("invalid_registration_window"));
            term(admin, y2, 1, "2028-07-01", "2028-12-31", ",\"registrationStart\":\"2028-07-01\"")
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_registration_window"));
            id(term(admin, y2, 1, "2028-07-01", "2028-12-31", ""));
            term(admin, y2, 1, "2029-01-01", "2029-03-31", "").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("sequence_in_use"));
            as(admin, get("/api/v1/academics/academic-years/" + y)).andExpect(jsonPath("$.terms.length()").value(3))
                    .andExpect(jsonPath("$.terms[2].name").value("Semester 3"));

            // status: a term activates only in an active year; a year completes only without active terms
            setStatus(admin, "/api/v1/academics/academic-terms", "academic_term", t1, "ACTIVE", null)
                    .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("year_not_active"));
            setStatus(admin, "/api/v1/academics/academic-years", "academic_year", y, "COMPLETED", null)
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("invalid_status_transition"));
            setStatus(admin, "/api/v1/academics/academic-years", "academic_year", y, "ACTIVE", null).andExpect(status().isOk());
            setStatus(admin, "/api/v1/academics/academic-terms", "academic_term", t1, "ACTIVE", null).andExpect(status().isOk());
            setStatus(admin, "/api/v1/academics/academic-years", "academic_year", y, "COMPLETED", null)
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("terms_active"));
            setStatus(admin, "/api/v1/academics/academic-terms", "academic_term", t1, "COMPLETED", null).andExpect(status().isOk());
            setStatus(admin, "/api/v1/academics/academic-years", "academic_year", y, "COMPLETED", null).andExpect(status().isOk());
            term(admin, y, 4, "2027-06-29", "2027-06-30", "").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("year_completed"));
            setStatus(admin, "/api/v1/academics/academic-years", "academic_year", y, "ACTIVE", null)
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("reason_required"));
            setStatus(admin, "/api/v1/academics/academic-years", "academic_year", y, "ACTIVE", "Results revised").andExpect(status().isOk());
            assertThat(audits("academics.year.completed")).isEqualTo(1);
            assertThat(audits("academics.term.created")).isEqualTo(4);
        }

        @Test
        void overlappingYearsCanBeAllowedInSystemSettings() throws Exception {
            year(admin, "AY1", null, "2026-07-01", "2027-06-30").andExpect(status().isCreated());
            year(admin, "AY2", null, "2027-01-01", "2027-12-31").andExpect(status().isUnprocessableContent());
            as(admin, put("/api/v1/tenant/settings").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"version\":-1,\"allowOverlappingAcademicYears\":true,\"labels\":{\"TERM\":{\"singular\":\"Trimester\",\"plural\":\"Trimesters\"}}}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.allowOverlappingAcademicYears").value(true))
                    .andExpect(jsonPath("$.labels.TERM.plural").value("Trimesters")).andExpect(jsonPath("$.labels.PROGRAM.singular").value("Program"));
            year(admin, "AY2", null, "2027-01-01", "2027-12-31").andExpect(status().isCreated());
        }
    }

    @Nested
    class Delivery {

        @Test
        void batchesOfferingsAndFacultyAllocationWithHistory() throws Exception {
            Chain ch = chain();
            as(admin, get("/api/v1/academics/batches/" + ch.batch())).andExpect(jsonPath("$.expectedGraduationYear").value(2030))
                    .andExpect(jsonPath("$.offeringCount").value(1));
            id(batch(admin, ch.course(), ch.year(), "CSE-2026-B", "B"));                          // several batches per course
            batch(admin, ch.course(), ch.year(), "cse-2026-a", null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("code_in_use"));
            send(admin, post("/api/v1/academics/batches"), """
                    {"code":"X","name":"X","courseId":"%s","academicYearId":"%s","admissionYear":2026,"expectedGraduationYear":2025}"""
                    .formatted(ch.course(), ch.year())).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_graduation_year"));
            UUID yearB = id(year(admin, "AYB", collegeB, "2026-07-01", "2027-06-30"));
            batch(admin, ch.course(), yearB, "WRONG", null).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("year_not_applicable"));

            // offerings: the section defaults to the batch's, credits come from the subject master; duplicates refused
            as(admin, get("/api/v1/academics/subject-offerings/" + ch.offering())).andExpect(jsonPath("$.section").value("A"))
                    .andExpect(jsonPath("$.credits").value(4.0));
            offering(admin, ch.subject(), ch.batch(), ch.term(), 1).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("offering_exists"));
            offering(admin, ch.subject(), ch.batch(), ch.term(), 2).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("not_in_curriculum"));
            UUID elsewhere = id(subject(admin, "MA101", collegeA));
            offering(admin, elsewhere, ch.batch(), ch.term(), 1).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("not_in_curriculum"));
            assertThat(ownerJdbc().sql("SELECT count(*) FROM subject WHERE tenant_id = ?").param(tenant).query(Long.class).single()).isEqualTo(2);

            // faculty: first assignment becomes primary; a new primary replaces it; duplicates and non-teaching staff refused
            String o = "/api/v1/academics/subject-offerings/" + ch.offering();
            assign(admin, ch.offering(), teacherA, null).andExpect(status().isOk()).andExpect(jsonPath("$[0].primary").value(true));
            assign(admin, ch.offering(), teacherA, null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("faculty_already_assigned"));
            assign(admin, ch.offering(), clerkA, null).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("not_teaching_staff"));
            assign(admin, ch.offering(), teacherB, null).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("staff_outside_institution"));
            JsonNode list = body(assign(admin, ch.offering(), teacherA2, true).andExpect(status().isOk()));
            assertThat(list.findValues("primary").stream().filter(JsonNode::asBoolean).count()).isEqualTo(1);
            as(admin, get(o)).andExpect(jsonPath("$.primaryFaculty").value("Staff T-A2 Teacher")).andExpect(jsonPath("$.facultyCount").value(2));
            as(admin, get(o + "/faculty-candidates")).andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[?(@.staffNo=='T-A1')].alreadyAssigned").value(true));
            UUID first = ownerJdbc().sql("SELECT id FROM faculty_assignment WHERE staff_profile_id = ?").param(teacherA).query(UUID.class).single();
            send(admin, post(o + "/faculty/" + first + "/end"), "{\"reason\":\"\"}").andExpect(status().isBadRequest());
            send(admin, post(o + "/faculty/" + first + "/end"), "{\"reason\":\"Moved to another section\"}").andExpect(status().isOk());
            as(admin, get(o + "/faculty")).andExpect(jsonPath("$.length()").value(1));
            as(admin, get(o + "/faculty?includeHistory=true")).andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[1].endReason").value("Moved to another section")).andExpect(jsonPath("$[1].current").value(false));
            assign(admin, ch.offering(), teacherA, false).andExpect(status().isOk());              // re-assignment after history is allowed
            as(admin, get("/api/v1/academics/staff/" + teacherA + "/teaching?includeHistory=true")).andExpect(jsonPath("$.length()").value(2));

            // cancelling an offering ends its open assignments; history is kept
            setStatus(admin, "/api/v1/academics/subject-offerings", "subject_offering", ch.offering(), "CANCELLED", null).andExpect(status().isBadRequest());
            setStatus(admin, "/api/v1/academics/subject-offerings", "subject_offering", ch.offering(), "CANCELLED", "Timetable clash").andExpect(status().isOk());
            assertThat(ownerJdbc().sql("SELECT count(*) FROM faculty_assignment WHERE offering_id = ? AND ended_at IS NULL").param(ch.offering())
                    .query(Long.class).single()).isZero();
            assertThat(ownerJdbc().sql("SELECT count(*) FROM faculty_assignment WHERE offering_id = ?").param(ch.offering()).query(Long.class).single()).isEqualTo(3);
            assign(admin, ch.offering(), teacherA, null).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("offering_closed"));
            id(offering(admin, ch.subject(), ch.batch(), ch.term(), 1));                           // cancelled offerings do not block a new one
            send(admin, delete("/api/v1/academics/courses/" + ch.course() + "/curriculum/"
                    + ownerJdbc().sql("SELECT id FROM curriculum_entry WHERE course_id = ?").param(ch.course()).query(UUID.class).single()), "")
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("curriculum_entry_in_use"));
            assertThat(audits("academics.faculty.assigned")).isEqualTo(3);
            assertThat(audits("academics.faculty.ended")).isEqualTo(1);
            assertThat(audits("academics.offering.status_changed")).isEqualTo(1);
        }
    }

    @Nested
    class ScopeAndRbac {

        @Test
        void collegeAdministratorsManageOnlyTheirCollege() throws Exception {
            UUID pa = id(program(collegeAdminA, "BTECH", collegeA));
            program(collegeAdminA, "UNI", university).andExpect(status().isForbidden());
            program(collegeAdminA, "BX", collegeB).andExpect(status().isForbidden());
            UUID pb = id(program(collegeAdminB, "BSC", collegeB));
            as(collegeAdminA, get("/api/v1/academics/programs")).andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.items[0].code").value("BTECH"));
            as(collegeAdminA, get("/api/v1/academics/programs/" + pb)).andExpect(status().isForbidden());
            setStatus(collegeAdminA, "/api/v1/academics/programs", "program", pb, "INACTIVE", "x").andExpect(status().isForbidden());
            year(collegeAdminA, "ORG", null, "2026-07-01", "2027-06-30").andExpect(status().isForbidden());   // organization-wide calendar
            id(year(collegeAdminA, "AYA", collegeA, "2026-07-01", "2027-06-30"));
            id(course(collegeAdminA, pa, "CSE", d1));
            as(collegeAdminA, get("/api/v1/academics/programs/" + pa)).andExpect(jsonPath("$.canManage").value(true));
            assertThat(ownerJdbc().sql("SELECT count(*) FROM audit_event WHERE tenant_id = ? AND outcome = 'DENIED'").param(tenant).query(Long.class).single())
                    .isGreaterThanOrEqualTo(3);
        }

        @Test
        void departmentHeadsAndFacultyAreLimitedToTheirDepartment() throws Exception {
            Chain ch = chain();
            UUID mech = id(course(admin, ch.program(), "MECH", d2));
            // department head: batches, offerings and faculty in D1 only; never programs, subjects or the calendar
            id(batch(headD1, ch.course(), ch.year(), "CSE-2026-C", "C"));
            batch(headD1, mech, ch.year(), "MECH-2026", null).andExpect(status().isForbidden());
            program(headD1, "P", collegeA).andExpect(status().isForbidden());
            subject(headD1, "S", d1).andExpect(status().isForbidden());
            year(headD1, "Y", collegeA, "2030-07-01", "2031-06-30").andExpect(status().isForbidden());
            assign(headD1, ch.offering(), teacherA, null).andExpect(status().isOk());
            // reads go up the hierarchy (the program of their course) but not sideways (other departments' courses)
            as(headD1, get("/api/v1/academics/programs/" + ch.program())).andExpect(status().isOk()).andExpect(jsonPath("$.canManage").value(false));
            as(headD1, get("/api/v1/academics/courses")).andExpect(jsonPath("$.totalElements").value(1));
            as(headD1, get("/api/v1/academics/courses/" + mech)).andExpect(status().isForbidden());
            as(headD1, get("/api/v1/academics/academic-years/" + ch.year())).andExpect(status().isOk());   // organization-wide year
            // faculty: read only
            as(facultyD1, get("/api/v1/academics/subject-offerings")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.items[0].canManage").value(false));
            batch(facultyD1, ch.course(), ch.year(), "F", null).andExpect(status().isForbidden());
            assign(facultyD1, ch.offering(), teacherA2, null).andExpect(status().isForbidden());
            as(facultyD1, get("/api/v1/academics/subject-offerings/" + ch.offering() + "/faculty-candidates")).andExpect(status().isForbidden());
            // college B sees none of college A's delivery
            as(collegeAdminB, get("/api/v1/academics/batches")).andExpect(jsonPath("$.totalElements").value(0));
            as(collegeAdminB, get("/api/v1/academics/subject-offerings/" + ch.offering())).andExpect(status().isForbidden());
            assign(collegeAdminB, ch.offering(), teacherB, null).andExpect(status().isForbidden());
        }

        @Test
        void organizationsAreIsolated() throws Exception {
            Chain ch = chain();
            as(BELA_ORG_ADMIN_B, get("/api/v1/academics/programs/" + ch.program())).andExpect(status().isNotFound());
            as(BELA_ORG_ADMIN_B, get("/api/v1/academics/subject-offerings/" + ch.offering())).andExpect(status().isNotFound());
            JsonNode programs = body(as(BELA_ORG_ADMIN_B, get("/api/v1/academics/programs?size=100")));
            assertThat(programs.path("items").findValues("id").stream().map(JsonNode::asString)).doesNotContain(ch.program().toString());
            course(BELA_ORG_ADMIN_B, ch.program(), "X", d1).andExpect(status().isNotFound());
            as(BELA_ORG_ADMIN_B, get("/api/v1/academics/overview")).andExpect(status().isOk());
        }
    }

    @Nested
    class OverviewAndIntegrity {

        @Test
        void kpisAndTreeComeFromAuthorizedDataAndRefreshAfterChanges() throws Exception {
            as(admin, get("/api/v1/academics/overview")).andExpect(jsonPath("$.programs").value(0)).andExpect(jsonPath("$.offerings").value(0));
            Chain ch = chain();
            as(admin, get("/api/v1/academics/overview")).andExpect(jsonPath("$.programs").value(1)).andExpect(jsonPath("$.courses").value(1))
                    .andExpect(jsonPath("$.subjects").value(1)).andExpect(jsonPath("$.activeAcademicYears").value(1))
                    .andExpect(jsonPath("$.activeTerms").value(1)).andExpect(jsonPath("$.activeBatches").value(1))
                    .andExpect(jsonPath("$.offerings").value(1)).andExpect(jsonPath("$.offeringsWithoutFaculty").value(1))
                    .andExpect(jsonPath("$.facultyAssignments").value(0));
            assign(admin, ch.offering(), teacherA, null).andExpect(status().isOk());
            as(admin, get("/api/v1/academics/overview")).andExpect(jsonPath("$.facultyAssignments").value(1)).andExpect(jsonPath("$.offeringsWithoutFaculty").value(0));
            // scope-filtered: college B sees only organization-wide data (the year and its term)
            as(collegeAdminB, get("/api/v1/academics/overview")).andExpect(jsonPath("$.programs").value(0)).andExpect(jsonPath("$.activeAcademicYears").value(1))
                    .andExpect(jsonPath("$.offerings").value(0));
            as(admin, get("/api/v1/academics/tree")).andExpect(jsonPath("$.programs[0].code").value("BTECH"))
                    .andExpect(jsonPath("$.programs[0].courses[0].code").value("CSE")).andExpect(jsonPath("$.programs[0].courses[0].subjects").value(1))
                    .andExpect(jsonPath("$.programs[0].courses[0].batches[0].code").value("CSE-2026-A"));
            as(collegeAdminB, get("/api/v1/academics/tree")).andExpect(jsonPath("$.programs.length()").value(0));
            as(BELA_ORG_ADMIN_B, get("/api/v1/academics/overview")).andExpect(jsonPath("$.programs").value(0));
        }

        @Test
        void unitsWithAcademicRecordsCannotBeDeletedOrMoved() throws Exception {
            Chain ch = chain();
            as(admin, delete("/api/v1/org-units/" + d1 + "?version=" + version("org_unit", d1)))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("org_unit_has_dependents"));
            send(admin, post("/api/v1/org-units/" + d1 + "/move"), "{\"newParentId\":\"" + collegeB + "\",\"version\":" + version("org_unit", d1)
                    + ",\"reason\":\"Restructure\"}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("org_unit_has_academic_records"));
            as(admin, get("/api/v1/org-units/" + d1)).andExpect(jsonPath("$.dependents.academicRecords").value(1));
            assertThat(ch.course()).isNotNull();
        }

        @Test
        void concurrentDuplicatesAndOverlapsAreSerialised() throws Exception {
            Chain ch = chain();
            UUID other = id(subject(admin, "CS102", collegeA));
            curriculum(admin, ch.course(), other, 1).andExpect(status().isOk());
            for (int round = 0; round < 3; round++) {
                int[] codes = concurrently(
                        () -> offering(admin, other, ch.batch(), ch.term(), 1).andReturn().getResponse().getStatus(),
                        () -> offering(admin, other, ch.batch(), ch.term(), 1).andReturn().getResponse().getStatus());
                assertThat(codes).containsExactlyInAnyOrder(round == 0 ? 201 : 409, 409);
                String y = "C" + round;
                String s1 = "203" + round, s2 = "203" + (round + 1);
                int[] years = concurrently(
                        () -> year(admin, y + "a", collegeA, s1 + "-07-01", s2 + "-06-30").andReturn().getResponse().getStatus(),
                        () -> year(admin, y + "b", collegeA, s1 + "-08-01", s2 + "-05-31").andReturn().getResponse().getStatus());
                assertThat(years).containsExactlyInAnyOrder(201, 422);
                int[] primaries = concurrently(
                        () -> assign(admin, ch.offering(), teacherA, true).andReturn().getResponse().getStatus(),
                        () -> assign(admin, ch.offering(), teacherA2, true).andReturn().getResponse().getStatus());
                assertThat(primaries).containsOnly(200);
                assertThat(ownerJdbc().sql("SELECT count(*) FROM faculty_assignment WHERE offering_id = ? AND ended_at IS NULL AND is_primary")
                        .param(ch.offering()).query(Long.class).single()).isEqualTo(1);
                ownerJdbc().sql("DELETE FROM faculty_assignment WHERE offering_id = ?").param(ch.offering()).update();
            }
        }

        @Test
        void migrationGrantsAcademicPermissionsToSystemRoles() {
            List<String> admin = ownerJdbc().sql("SELECT unnest(permissions) FROM role WHERE code = 'INSTITUTION_ADMIN' AND tenant_id IS NULL")
                    .query(String.class).list();
            assertThat(admin).contains("academics.read", "program.manage", "subject.manage", "academic_calendar.manage", "batch.manage", "faculty.assign");
            List<String> head = ownerJdbc().sql("SELECT unnest(permissions) FROM role WHERE code = 'DEPARTMENT_HEAD' AND tenant_id IS NULL")
                    .query(String.class).list();
            assertThat(head).contains("academics.read", "batch.manage", "faculty.assign").doesNotContain("program.manage", "subject.manage");
            assertThat(ownerJdbc().sql("SELECT count(*) FROM pg_class WHERE relname IN ('program','course','subject','curriculum_entry','subject_prerequisite',"
                    + "'academic_year','academic_term','batch','subject_offering','faculty_assignment') AND relrowsecurity").query(Long.class).single()).isEqualTo(10);
            assertThat(ownerJdbc().sql("SELECT count(*) FROM role WHERE 'academics.read' = ANY(permissions) AND code = 'STUDENT'").query(Long.class).single()).isZero();
        }
    }
}
