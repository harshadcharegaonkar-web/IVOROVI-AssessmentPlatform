package com.ivorovi.students;

import static com.ivorovi.support.Fixture.BELA_ORG_ADMIN_B;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ivorovi.platform.config.DeploymentProperties.Environment;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.students.internal.DocumentService;
import com.ivorovi.support.FakeMalwareScanner;
import com.ivorovi.support.Fixture;
import com.ivorovi.support.IntegrationTest;
import com.ivorovi.support.TestTokens;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.Year;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Increment 1.5: student lifecycle management. Fresh tenant per test:
 * <pre>
 *   University U ─ College A (code AIT) ─ Campus K ─ Department CSE (D1)
 *                │                      └ Department ME (D2)
 *                └ College B (code BCC) ─ Department DB
 * </pre>
 * Academic structure: B.Tech (A) with courses CSE and ME, B.Sc (B) with course PHY; organization-wide years Y1 (active,
 * term 1 active) and Y2 (later); batches CSE-A (capacity 2), CSE-B, ME-A and PHY-A.
 */
class StudentsIT extends IntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final byte[] PDF = "%PDF-1.4\n1 0 obj << >> endobj\ntrailer << >>\n%%EOF".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};

    @Autowired DocumentService documents;
    @Autowired FakeMalwareScanner scanner;

    private UUID tenant, university, collegeA, campusK, cse, me, collegeB, dB;
    private Fixture.Caller admin, collegeAdminA, headCse, facultyCse, officerA, collegeAdminB;
    private UUID btech, courseCse, courseMe, bsc, coursePhy, y1, y2, term1, batchCseA, batchCseB, batchMeA, batchPhy;

    @BeforeEach
    void freshTenant() throws Exception {
        scanner.available = true;
        tenant = UUID.randomUUID();
        ownerJdbc().sql("INSERT INTO tenant (id, code, name, status) VALUES (?, ?, 'Students test org', 'ACTIVE')")
                .params(tenant, "stu-" + tenant.toString().substring(0, 8)).update();
        university = unit("UNIVERSITY", null, "U");
        collegeA = unit("COLLEGE", university, "AIT");
        campusK = unit("CAMPUS", collegeA, "K");
        cse = unit("DEPARTMENT", campusK, "CSE");
        me = unit("DEPARTMENT", collegeA, "ME");
        collegeB = unit("COLLEGE", university, "BCC");
        dB = unit("DEPARTMENT", collegeB, "PHY");
        admin = account("ORG_ADMIN", null);
        collegeAdminA = account("INSTITUTION_ADMIN", collegeA);
        headCse = account("DEPARTMENT_HEAD", cse);
        facultyCse = account("FACULTY", cse);
        officerA = account("ADMISSION_OFFICER", collegeA);
        collegeAdminB = account("INSTITUTION_ADMIN", collegeB);
        btech = id(send(admin, post("/api/v1/academics/programs"), "{\"code\":\"BTECH\",\"name\":\"B.Tech\",\"qualificationLevel\":\"UNDERGRADUATE\",\"ownerOrgUnitId\":\"" + collegeA + "\",\"termSystem\":\"SEMESTER\",\"durationYears\":4}"));
        courseCse = id(send(admin, post("/api/v1/academics/courses"), "{\"code\":\"CSE\",\"name\":\"Computer Science\",\"programId\":\"" + btech + "\",\"departmentId\":\"" + cse + "\"}"));
        courseMe = id(send(admin, post("/api/v1/academics/courses"), "{\"code\":\"ME\",\"name\":\"Mechanical\",\"programId\":\"" + btech + "\",\"departmentId\":\"" + me + "\"}"));
        bsc = id(send(admin, post("/api/v1/academics/programs"), "{\"code\":\"BSC\",\"name\":\"B.Sc\",\"qualificationLevel\":\"UNDERGRADUATE\",\"ownerOrgUnitId\":\"" + collegeB + "\",\"termSystem\":\"SEMESTER\",\"durationYears\":3}"));
        coursePhy = id(send(admin, post("/api/v1/academics/courses"), "{\"code\":\"PHY\",\"name\":\"Physics\",\"programId\":\"" + bsc + "\",\"departmentId\":\"" + dB + "\"}"));
        int yr = Year.now().getValue();
        y1 = UUID.fromString(body(send(admin, post("/api/v1/academics/academic-years"), "{\"code\":\"Y1\",\"name\":\"Year one\",\"startDate\":\"" + (yr - 1) + "-07-01\",\"endDate\":\"" + yr + "-06-30\"}")).path("year").path("id").asString());
        y2 = UUID.fromString(body(send(admin, post("/api/v1/academics/academic-years"), "{\"code\":\"Y2\",\"name\":\"Year two\",\"startDate\":\"" + yr + "-07-01\",\"endDate\":\"" + (yr + 1) + "-06-30\"}")).path("year").path("id").asString());
        term1 = id(send(admin, post("/api/v1/academics/academic-years/" + y1 + "/terms"), "{\"sequence\":1,\"name\":\"Semester 1\",\"startDate\":\"" + (yr - 1) + "-07-15\",\"endDate\":\"" + (yr - 1) + "-12-15\"}"));
        batchCseA = batch(courseCse, "CSE-A", 2);
        batchCseB = batch(courseCse, "CSE-B", null);
        batchMeA = batch(courseMe, "ME-A", null);
        batchPhy = batch(coursePhy, "PHY-A", null);
    }

    @AfterEach
    void restoreScanner() {
        scanner.available = true;
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
                .params(id, tenant, subject, id + "@students.test", roleCode + " user").update();
        UUID role = ownerJdbc().sql("SELECT id FROM role WHERE code = ? AND tenant_id IS NULL").param(roleCode).query(UUID.class).single();
        ownerJdbc().sql("INSERT INTO role_assignment (id, tenant_id, user_account_id, role_id, scope_type, scope_id) VALUES (?, ?, ?, ?, ?, ?)")
                .params(UUID.randomUUID(), tenant, id, role, unit == null ? "TENANT" : "ORG_UNIT", unit == null ? tenant : unit).update();
        return new Fixture.Caller(subject, tenant);
    }

    private UUID batch(UUID course, String code, Integer capacity) throws Exception {
        return id(send(admin, post("/api/v1/academics/batches"), "{\"code\":\"" + code + "\",\"name\":\"Batch " + code + "\",\"courseId\":\"" + course
                + "\",\"academicYearId\":\"" + y1 + "\",\"admissionYear\":" + (Year.now().getValue() - 1) + (capacity == null ? "" : ",\"capacity\":" + capacity) + "}"));
    }

    private ResultActions as(Fixture.Caller c, MockHttpServletRequestBuilder r) throws Exception {
        return mvc.perform(r.header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.forCaller(c)));
    }

    private ResultActions send(Fixture.Caller c, MockHttpServletRequestBuilder r, String json) throws Exception {
        return as(c, r.contentType(MediaType.APPLICATION_JSON).content(json).header("Idempotency-Key", "k-" + UUID.randomUUID()));
    }

    private static JsonNode body(ResultActions r) throws Exception {
        return JSON.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private static UUID id(ResultActions r) throws Exception {
        JsonNode n = body(r.andExpect(status().isCreated()));
        JsonNode inner = n.has("id") ? n : n.path("student");
        return UUID.fromString(inner.path("id").asString());
    }

    private long version(UUID student) {
        return ownerJdbc().sql("SELECT version FROM student WHERE id = ?").param(student).query(Long.class).single();
    }

    private String studentJson(UUID institution, String no, String first, String last, boolean personal) {
        return "{\"institutionId\":\"" + institution + "\"" + (no == null ? "" : ",\"studentNo\":\"" + no + "\"") + ",\"firstName\":\"" + first + "\",\"lastName\":\"" + last + "\""
                + (personal ? ",\"personal\":{\"dateOfBirth\":\"2005-03-14\",\"gender\":\"FEMALE\",\"nationality\":\"Indian\",\"personalEmail\":\"" + first.toLowerCase()
                + ".private@example.test\",\"mobile\":\"+91 98765 43210\",\"currentAddress\":\"12 Lake Road, Pune\"}" : "") + "}";
    }

    private ResultActions createStudent(Fixture.Caller c, UUID institution, String no, String first) throws Exception {
        return send(c, post("/api/v1/students"), studentJson(institution, no, first, "Sharma", true));
    }

    private UUID student(String no, String first) throws Exception {
        return id(createStudent(admin, collegeA, no, first));
    }

    private ResultActions admit(Fixture.Caller c, UUID student, String no, UUID course, String type, int entryTerm, String extra) throws Exception {
        return send(c, post("/api/v1/students/" + student + "/admissions"), "{\"admissionNo\":\"" + no + "\",\"admissionDate\":\"" + LocalDate.now() + "\",\"admissionType\":\""
                + type + "\",\"entryAcademicYearId\":\"" + y1 + "\",\"programId\":\"" + (course.equals(coursePhy) ? bsc : btech) + "\",\"courseId\":\"" + course
                + "\",\"entryTermNumber\":" + entryTerm + extra + "}");
    }

    private ResultActions enrol(Fixture.Caller c, UUID student, String type, UUID batch, Integer term, UUID year, String reason) throws Exception {
        return send(c, post("/api/v1/students/" + student + "/enrolments"), "{\"type\":\"" + type + "\",\"batchId\":\"" + batch + "\""
                + (term == null ? "" : ",\"termNumber\":" + term) + (year == null ? "" : ",\"academicYearId\":\"" + year + "\"")
                + (reason == null ? "" : ",\"reason\":\"" + reason + "\"") + "}");
    }

    private ResultActions setStatus(Fixture.Caller c, UUID student, String to, String reason) throws Exception {
        return send(c, post("/api/v1/students/" + student + "/status"), "{\"status\":\"" + to + "\",\"version\":" + version(student)
                + (reason == null ? "" : ",\"reason\":\"" + reason + "\"") + "}");
    }

    /** Admitted + enrolled + active student in CSE-B. */
    private UUID activeStudent(String no) throws Exception {
        UUID s = student(no, "Asha" + no.replaceAll("[^0-9]", ""));
        admit(admin, s, "ADM-" + no, courseCse, "REGULAR", 1, "").andExpect(status().isOk());
        enrol(admin, s, "INITIAL", batchCseB, null, null, null).andExpect(status().isOk());
        setStatus(admin, s, "ACTIVE", "Classes started").andExpect(status().isOk());
        return s;
    }

    private ResultActions upload(Fixture.Caller c, UUID student, String category, String name, byte[] content) throws Exception {
        return mvc.perform(multipart("/api/v1/students/" + student + "/documents").file(new MockMultipartFile("file", name, "application/octet-stream", content))
                .param("category", category).header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.forCaller(c)));
    }

    private int scan() {
        return TenantContext.callInTenant(tenant, documents::scanPending);
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

    private long audits(String action) {
        return ownerJdbc().sql("SELECT count(*) FROM audit_event WHERE tenant_id = ? AND action = ?").params(tenant, action).query(Long.class).single();
    }

    // ------------------------------------------------------------------------------------------------ tests

    @Nested
    class ProfilesAndNumbering {

        @Test
        void profilesAreCreatedViewedAndEditedWithInstitutionSpecificNumbers() throws Exception {
            // no rule: manual entry with the safe default format
            createStudent(admin, collegeA, "x", "Bad").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_studentNo"));
            UUID s = id(createStudent(admin, collegeA, "AIT-0001", "Asha").andExpect(jsonPath("$.student.status").value("PROSPECTIVE"))
                    .andExpect(jsonPath("$.personal.mobile").value("+91 98765 43210")).andExpect(jsonPath("$.personalVisible").value(true)));
            createStudent(admin, collegeA, "ait-0001", "Dup").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("student_no_in_use"));
            // generated rule at college A: {INST}{YYYY}{SEQ:4}
            send(admin, put("/api/v1/student-settings/numbering-rules/" + collegeA), "{\"mode\":\"GENERATED\",\"template\":\"{INST}{YYYY}{SEQ}\"}")
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_template"));
            send(admin, put("/api/v1/student-settings/numbering-rules/" + collegeA), "{\"mode\":\"GENERATED\",\"template\":\"{INST}{YYYY}{SEQ:4}\",\"registrationPattern\":\"AAA/9999/*\"}")
                    .andExpect(status().isOk()).andExpect(jsonPath("$.example").value("AIT" + Year.now().getValue() + "0001"));
            String first = body(createStudent(admin, collegeA, null, "Ravi").andExpect(status().isCreated())).path("student").path("studentNo").asString();
            String second = body(createStudent(admin, collegeA, null, "Meena").andExpect(status().isCreated())).path("student").path("studentNo").asString();
            assertThat(first).isEqualTo("AIT" + Year.now().getValue() + "0001");
            assertThat(second).isEqualTo("AIT" + Year.now().getValue() + "0002");
            createStudent(admin, collegeA, "AIT-9", "X").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("student_no_generated"));
            // manual mask at college B; colleges do not share formats
            send(admin, put("/api/v1/student-settings/numbering-rules/" + collegeB), "{\"mode\":\"MANUAL\",\"manualPattern\":\"BCC-AA-9999\"}").andExpect(status().isOk());
            createStudent(admin, collegeB, "1234", "Kiran").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_studentNo"));
            createStudent(admin, collegeB, "XYZ-BX-1234", "Kiran").andExpect(status().isBadRequest());     // the literal prefix is required
            createStudent(admin, collegeB, "BCC-BX-1234", "Kiran").andExpect(status().isCreated());
            send(admin, put("/api/v1/student-settings/numbering-rules/" + collegeB), "{\"mode\":\"MANUAL\",\"manualPattern\":\"(a+)+$\"}")
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_pattern"));   // no raw regular expressions
            // edit (registration number validated by the institution's mask), optimistic version
            send(admin, patch("/api/v1/students/" + s), "{\"registrationNo\":\"12345\",\"firstName\":\"Asha\",\"lastName\":\"Sharma\",\"version\":" + version(s) + "}")
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_registrationNo"));
            send(admin, patch("/api/v1/students/" + s), "{\"registrationNo\":\"AIT/2026/77\",\"rollNo\":\"17\",\"firstName\":\"Asha\",\"middleName\":\"K\",\"lastName\":\"Sharma\",\"version\":" + version(s) + "}")
                    .andExpect(status().isOk()).andExpect(jsonPath("$.student.registrationNo").value("AIT/2026/77"))
                    .andExpect(jsonPath("$.personal.mobile").value("+91 98765 43210"));          // personal details untouched when omitted
            send(admin, patch("/api/v1/students/" + s), "{\"firstName\":\"Stale\",\"version\":0}").andExpect(status().isConflict());
            // search by name, number and registration number
            as(admin, get("/api/v1/students?q=asha")).andExpect(jsonPath("$.totalElements").value(1));
            as(admin, get("/api/v1/students?q=AIT/2026")).andExpect(jsonPath("$.items[0].fullName").value("Asha K Sharma"));
            as(admin, get("/api/v1/students?q=" + second)).andExpect(jsonPath("$.items[0].firstName").value("Meena"));
            assertThat(audits("student.created")).isEqualTo(4);
            assertThat(audits("student.numbering_rule.saved")).isEqualTo(2);
        }

        @Test
        void personalDetailsAreEncryptedRestrictedAndNeverLogged() throws Exception {
            UUID s = student("AIT-0100", "Priya");
            String stored = ownerJdbc().sql("SELECT date_of_birth_enc || '|' || mobile_enc || '|' || personal_email_enc || '|' || current_address_enc FROM student WHERE id = ?")
                    .param(s).query(String.class).single();
            assertThat(stored).startsWith("v1:").doesNotContain("2005").doesNotContain("98765").doesNotContain("private@").doesNotContain("Lake Road");
            // faculty (student.read only) sees the profile without personal details, and no guardians
            UUID enrolled = activeStudent("AIT-0101");
            as(facultyCse, get("/api/v1/students/" + enrolled)).andExpect(status().isOk()).andExpect(jsonPath("$.personalVisible").value(false))
                    .andExpect(jsonPath("$.personal").doesNotExist()).andExpect(jsonPath("$.can.manage").value(false));
            as(facultyCse, get("/api/v1/students/" + enrolled + "/guardians")).andExpect(status().isForbidden());
            as(facultyCse, get("/api/v1/students/" + enrolled + "/documents")).andExpect(status().isForbidden());
            send(facultyCse, patch("/api/v1/students/" + enrolled), "{\"firstName\":\"X\",\"version\":0}").andExpect(status().isForbidden());
            // the department head may read personal details of students in the department
            as(headCse, get("/api/v1/students/" + enrolled)).andExpect(jsonPath("$.personalVisible").value(true));
            // guardians: encrypted, one primary
            send(admin, post("/api/v1/students/" + enrolled + "/guardians"), "{\"name\":\"Ramesh Sharma\",\"relationship\":\"FATHER\",\"mobile\":\"+91 90000 11111\",\"email\":\"ramesh@example.test\",\"emergency\":true}")
                    .andExpect(status().isOk()).andExpect(jsonPath("$[0].primary").value(true));
            send(admin, post("/api/v1/students/" + enrolled + "/guardians"), "{\"name\":\"Sunita Sharma\",\"relationship\":\"MOTHER\",\"mobile\":\"+91 90000 22222\",\"primary\":true}")
                    .andExpect(status().isOk()).andExpect(jsonPath("$[0].name").value("Sunita Sharma")).andExpect(jsonPath("$[1].primary").value(false));
            send(admin, post("/api/v1/students/" + enrolled + "/guardians"), "{\"name\":\"No Contact\",\"relationship\":\"OTHER\"}").andExpect(status().isBadRequest());
            assertThat(ownerJdbc().sql("SELECT count(*) FROM guardian WHERE student_id = ? AND (mobile_enc LIKE '%90000%' OR email_enc LIKE '%ramesh%')").param(enrolled)
                    .query(Long.class).single()).isZero();
            // no personal value appears in the audit trail or the event log
            String trail = ownerJdbc().sql("SELECT string_agg(details::text, ' ') FROM audit_event WHERE tenant_id = ?").param(tenant).query(String.class).single();
            String events = ownerJdbc().sql("SELECT string_agg(summary, ' ') FROM student_event WHERE tenant_id = ?").param(tenant).query(String.class).single();
            for (String secret : List.of("2005-03-14", "98765", "private@example.test", "Lake Road", "90000", "ramesh@example.test")) {
                assertThat(trail).doesNotContain(secret);
                assertThat(events).doesNotContain(secret);
            }
            assertThat(audits("student.guardians.viewed")).isGreaterThanOrEqualTo(2);
        }
    }

    @Nested
    class AdmissionAndEnrolment {

        @Test
        void admissionEnrolmentProgressionPromotionAndTransfersPreserveHistory() throws Exception {
            UUID s = student("AIT-0200", "Rahul");
            enrol(admin, s, "INITIAL", batchCseA, null, null, null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("student_not_admitted"));
            admit(admin, s, "A-1", courseCse, "REGULAR", 2, "").andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("invalid_entry_term"));
            admit(admin, s, "A-1", coursePhy, "REGULAR", 1, "").andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("program_outside_institution"));
            admit(admin, s, "A-1", courseCse, "TRANSFER", 1, "").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_previousInstitutionName"));
            admit(admin, s, "A-1", courseCse, "REGULAR", 1, ",\"admissionCategory\":\"GENERAL\"").andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].status").value("CONFIRMED")).andExpect(jsonPath("$[0].admissionTypeLabel").value("Regular admission"));
            as(admin, get("/api/v1/students/" + s)).andExpect(jsonPath("$.student.status").value("ADMITTED"));
            admit(admin, s, "A-2", courseCse, "REGULAR", 1, "").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("admission_exists"));
            // the admission and enrolment are separate records
            enrol(admin, s, "INITIAL", batchMeA, null, null, null).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("course_mismatch"));
            enrol(admin, s, "INITIAL", batchCseA, null, null, null).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].changeType").value("INITIAL")).andExpect(jsonPath("$[0].termNumber").value(1));
            as(admin, get("/api/v1/students/" + s)).andExpect(jsonPath("$.student.status").value("ENROLLED"))
                    .andExpect(jsonPath("$.currentEnrolment.batchCode").value("CSE-A")).andExpect(jsonPath("$.student.departmentName").value("Unit CSE"));
            setStatus(admin, s, "ACTIVE", "Classes started").andExpect(status().isOk());
            // progression: same batch, later term
            enrol(admin, s, "PROGRESSION", batchCseA, 1, null, null).andExpect(status().isUnprocessableContent());
            enrol(admin, s, "PROGRESSION", batchCseA, 2, null, null).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[0].current").value(true)).andExpect(jsonPath("$[1].status").value("COMPLETED")).andExpect(jsonPath("$[1].current").value(false));
            // promotion: next academic year
            enrol(admin, s, "PROMOTION", batchCseA, 3, y1, null).andExpect(status().isUnprocessableContent());
            enrol(admin, s, "PROMOTION", batchCseA, 3, y2, null).andExpect(status().isOk()).andExpect(jsonPath("$[0].academicYearName").value("Year two"));
            // batch transfer within the course (reason required), then course transfer
            enrol(admin, s, "BATCH_TRANSFER", batchCseB, null, null, null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("reason_required"));
            enrol(admin, s, "BATCH_TRANSFER", batchMeA, null, null, "Section change").andExpect(status().isUnprocessableContent());
            enrol(admin, s, "BATCH_TRANSFER", batchCseB, null, null, "Section change").andExpect(status().isOk()).andExpect(jsonPath("$[1].status").value("TRANSFERRED"));
            enrol(admin, s, "COURSE_TRANSFER", batchPhy, null, null, "Moving college").andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("program_outside_institution"));
            enrol(admin, s, "COURSE_TRANSFER", batchMeA, 3, y2, "Changed branch").andExpect(status().isOk());
            JsonNode history = body(as(admin, get("/api/v1/students/" + s + "/enrolments")));
            assertThat(history.size()).isEqualTo(5);
            assertThat(history.findValuesAsString("changeType")).containsExactly("COURSE_TRANSFER", "BATCH_TRANSFER", "PROMOTION", "PROGRESSION", "INITIAL");
            assertThat(history.findValues("current").stream().filter(JsonNode::asBoolean).count()).isEqualTo(1);
            as(admin, get("/api/v1/students/" + s)).andExpect(jsonPath("$.student.departmentName").value("Unit ME"));
            assertThat(audits("student.enrolment.course_transfer")).isEqualTo(1);
            // lateral entry starts in a later term
            UUID lateral = student("AIT-0201", "Lata");
            admit(admin, lateral, "A-3", courseCse, "LATERAL_ENTRY", 3, ",\"previousInstitutionName\":\"Polytechnic\"").andExpect(status().isOk());
            enrol(admin, lateral, "INITIAL", batchCseB, null, null, null).andExpect(status().isOk()).andExpect(jsonPath("$[0].termNumber").value(3));
        }

        @Test
        void batchCapacityAndSimultaneousEnrolmentsAreEnforced() throws Exception {
            UUID a = student("AIT-0300", "Anil"), b = student("AIT-0301", "Bina"), c = student("AIT-0302", "Chetan");
            int n = 0;
            for (UUID s : List.of(a, b, c)) admit(admin, s, "C-" + (++n), courseCse, "REGULAR", 1, "").andExpect(status().isOk());
            enrol(admin, a, "INITIAL", batchCseA, null, null, null).andExpect(status().isOk());
            int[] codes = concurrently(() -> enrol(admin, b, "INITIAL", batchCseA, null, null, null).andReturn().getResponse().getStatus(),
                    () -> enrol(admin, c, "INITIAL", batchCseA, null, null, null).andReturn().getResponse().getStatus());
            // capacity 2: exactly one of the two simultaneous requests gets the last seat (the batch row serialises them)
            assertThat(codes).containsExactlyInAnyOrder(200, 409);
            long seats = ownerJdbc().sql("SELECT count(*) FROM enrolment WHERE batch_id = ? AND is_current").param(batchCseA).query(Long.class).single();
            assertThat(seats).isEqualTo(2);
            UUID late = ownerJdbc().sql("SELECT count(*) FROM enrolment WHERE student_id = ?").param(b).query(Long.class).single() == 0 ? b : c;
            enrol(admin, late, "INITIAL", batchCseA, null, null, null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("batch_full"));
        }
    }

    @Nested
    class Lifecycle {

        @Test
        void onlyValidTransitionsAreAllowedAndEveryChangeIsRecorded() throws Exception {
            UUID s = student("AIT-0400", "Divya");
            setStatus(admin, s, "ACTIVE", "x").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("invalid_status_transition"));
            setStatus(admin, s, "ADMITTED", "x").andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("status_requires_record"));
            UUID active = activeStudent("AIT-0401");
            setStatus(admin, active, "SUSPENDED", null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_reason"));
            setStatus(admin, active, "SUSPENDED", "Disciplinary hold").andExpect(status().isOk()).andExpect(jsonPath("$.currentEnrolment.status").value("SUSPENDED"));
            setStatus(admin, active, "GRADUATED", "x").andExpect(status().isConflict());
            setStatus(admin, active, "ACTIVE", "Hold lifted").andExpect(status().isOk()).andExpect(jsonPath("$.currentEnrolment.status").value("ACTIVE"));
            setStatus(admin, active, "WITHDRAWN", "Personal reasons").andExpect(status().isOk()).andExpect(jsonPath("$.currentEnrolment").doesNotExist());
            setStatus(admin, active, "ACTIVE", "Back").andExpect(status().isConflict());
            assertThat(ownerJdbc().sql("SELECT status FROM enrolment WHERE student_id = ? ORDER BY created_at DESC LIMIT 1").param(active).query(String.class).single())
                    .isEqualTo("WITHDRAWN");
            JsonNode history = body(as(admin, get("/api/v1/students/" + active + "/history")));
            List<String> statusRows = history.findValuesAsString("toStatus");
            assertThat(statusRows).containsSubsequence("WITHDRAWN", "ACTIVE", "SUSPENDED", "ACTIVE", "ENROLLED", "ADMITTED", "PROSPECTIVE");
            assertThat(history.get(0).path("reason").asString()).isEqualTo("Personal reasons");
            assertThat(history.get(0).path("actorName").asString()).isEqualTo("ORG_ADMIN user");
            assertThat(history.get(0).path("effectiveOn").asString()).isEqualTo(LocalDate.now().toString());
            // graduation and alumni
            UUID grad = activeStudent("AIT-0402");
            setStatus(admin, grad, "GRADUATED", "Completed the program").andExpect(status().isOk());
            setStatus(admin, grad, "ALUMNI", "Convocation held").andExpect(status().isOk()).andExpect(jsonPath("$.allowedTransitions.length()").value(0));
            // the status and event logs cannot be altered by the application role
            assertThat(ownerJdbc().sql("SELECT has_table_privilege('ivorovi_app', 'student_status_history', 'UPDATE') OR has_table_privilege('ivorovi_app', 'student_event', 'UPDATE')")
                    .query(Boolean.class).single()).isFalse();
            // physical deletion only for records created in error
            as(admin, delete("/api/v1/students/" + grad + "?version=" + version(grad))).andExpect(status().isConflict());
            setStatus(admin, s, "CANCELLED", "Created twice").andExpect(status().isOk());
            as(admin, delete("/api/v1/students/" + s + "?version=" + version(s))).andExpect(status().isNoContent());
            // deactivated profiles cannot be changed
            UUID other = student("AIT-0403", "Esha");
            send(admin, post("/api/v1/students/" + other + "/active"), "{\"active\":false,\"reason\":\"Duplicate entry\",\"version\":" + version(other) + "}").andExpect(status().isOk());
            send(admin, patch("/api/v1/students/" + other), "{\"firstName\":\"X\",\"version\":" + version(other) + "}").andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("student_inactive"));
        }

        @Test
        void simultaneousStatusChangesAreSerialised() throws Exception {
            UUID s = activeStudent("AIT-0450");
            long v = version(s);
            int[] codes = concurrently(
                    () -> send(admin, post("/api/v1/students/" + s + "/status"), "{\"status\":\"SUSPENDED\",\"reason\":\"a\",\"version\":" + v + "}").andReturn().getResponse().getStatus(),
                    () -> send(admin, post("/api/v1/students/" + s + "/status"), "{\"status\":\"WITHDRAWN\",\"reason\":\"b\",\"version\":" + v + "}").andReturn().getResponse().getStatus());
            assertThat(codes).containsExactlyInAnyOrder(200, 409);
        }
    }

    @Nested
    class ScopeAndIsolation {

        @Test
        void readersSeeOnlyTheirPartOfTheOrganization() throws Exception {
            UUID inCse = activeStudent("AIT-0500");
            UUID inMe = student("AIT-0501", "Mohan");
            admit(admin, inMe, "M-1", courseMe, "REGULAR", 1, "").andExpect(status().isOk());
            UUID inB = id(send(admin, post("/api/v1/students"), studentJson(collegeB, "BCC-0001", "Bela", "Roy", false)));
            as(headCse, get("/api/v1/students")).andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.items[0].id").value(inCse.toString()));
            as(headCse, get("/api/v1/students/" + inMe)).andExpect(status().isForbidden());
            as(collegeAdminA, get("/api/v1/students")).andExpect(jsonPath("$.totalElements").value(2));
            as(collegeAdminB, get("/api/v1/students")).andExpect(jsonPath("$.totalElements").value(1));
            as(collegeAdminB, get("/api/v1/students/" + inCse)).andExpect(status().isForbidden());
            send(collegeAdminB, post("/api/v1/students/" + inCse + "/status"), "{\"status\":\"SUSPENDED\",\"reason\":\"x\",\"version\":" + version(inCse) + "}").andExpect(status().isForbidden());
            send(collegeAdminA, post("/api/v1/students"), studentJson(collegeB, "BCC-0002", "No", "Way", false)).andExpect(status().isForbidden());
            send(facultyCse, post("/api/v1/students"), studentJson(collegeA, "AIT-0599", "No", "Way", false)).andExpect(status().isForbidden());
            // the admission officer of college A manages its students
            UUID byOfficer = id(send(officerA, post("/api/v1/students"), studentJson(collegeA, "AIT-0502", "Om", "Patil", true)));
            admit(officerA, byOfficer, "O-1", courseCse, "REGULAR", 1, "").andExpect(status().isOk());
            // other organizations see nothing
            as(BELA_ORG_ADMIN_B, get("/api/v1/students/" + inCse)).andExpect(status().isNotFound());
            JsonNode other = body(as(BELA_ORG_ADMIN_B, get("/api/v1/students?size=100")));
            assertThat(other.path("items").findValuesAsString("id")).doesNotContain(inCse.toString(), inB.toString());
            assertThat(ownerJdbc().sql("SELECT count(*) FROM pg_class WHERE relname IN ('student','student_option','student_number_rule','student_number_counter','student_status_history',"
                    + "'student_event','admission_record','enrolment','guardian','student_document') AND relrowsecurity").query(Long.class).single()).isEqualTo(10);
        }

        @Test
        void filtersAndDashboardUseAuthorizedDataAndRefresh() throws Exception {
            UUID a = activeStudent("AIT-0600");
            activeStudent("AIT-0601");
            UUID s = student("AIT-0602", "Neha");
            admit(admin, s, "N-1", courseMe, "REGULAR", 1, "").andExpect(status().isOk());
            enrol(admin, s, "INITIAL", batchMeA, null, null, null).andExpect(status().isOk());
            student("AIT-0603", "Pending");
            as(admin, get("/api/v1/students?batchId=" + batchCseB)).andExpect(jsonPath("$.totalElements").value(2));
            as(admin, get("/api/v1/students?courseId=" + courseMe)).andExpect(jsonPath("$.totalElements").value(1));
            as(admin, get("/api/v1/students?departmentId=" + cse)).andExpect(jsonPath("$.totalElements").value(2));
            as(admin, get("/api/v1/students?campusId=" + campusK)).andExpect(jsonPath("$.totalElements").value(2));
            as(admin, get("/api/v1/students?institutionId=" + collegeA)).andExpect(jsonPath("$.totalElements").value(4));
            as(admin, get("/api/v1/students?status=PROSPECTIVE")).andExpect(jsonPath("$.totalElements").value(1));
            as(admin, get("/api/v1/students?academicYearId=" + y1 + "&termNumber=1")).andExpect(jsonPath("$.totalElements").value(3));
            as(admin, get("/api/v1/students?programId=" + btech + "&sort=studentNo&direction=desc&size=2")).andExpect(jsonPath("$.items[0].studentNo").value("AIT-0602"))
                    .andExpect(jsonPath("$.totalPages").value(2));
            as(admin, get("/api/v1/students/overview")).andExpect(jsonPath("$.total").value(4)).andExpect(jsonPath("$.active").value(3))
                    .andExpect(jsonPath("$.prospective").value(1)).andExpect(jsonPath("$.newlyAdmitted").value(3))
                    .andExpect(jsonPath("$.byBatch[0].label").value("CSE-B")).andExpect(jsonPath("$.byBatch[0].count").value(2))
                    .andExpect(jsonPath("$.byProgram[0].count").value(3)).andExpect(jsonPath("$.byAcademicYear[0].label").value("Year one"));
            setStatus(admin, a, "SUSPENDED", "Hold").andExpect(status().isOk());
            as(admin, get("/api/v1/students/overview")).andExpect(jsonPath("$.suspended").value(1)).andExpect(jsonPath("$.active").value(2));
            as(headCse, get("/api/v1/students/overview")).andExpect(jsonPath("$.total").value(2));
            as(collegeAdminB, get("/api/v1/students/overview")).andExpect(jsonPath("$.total").value(0));
        }
    }

    @Nested
    class Documents {

        @Test
        void uploadsStayQuarantinedUntilScannedCleanAndOnlyCleanFilesAreServed() throws Exception {
            UUID s = activeStudent("AIT-0700");
            upload(admin, s, "IDENTITY_PROOF", "aadhaar.pdf", PDF).andExpect(status().isCreated()).andExpect(jsonPath("$[0].scanStatus").value("PENDING"))
                    .andExpect(jsonPath("$[0].downloadable").value(false));
            String docId = body(as(admin, get("/api/v1/students/" + s + "/documents"))).get(0).path("id").asString();
            String path = ownerJdbc().sql("SELECT object_path FROM student_document WHERE id = ?").param(UUID.fromString(docId)).query(String.class).single();
            assertThat(path).startsWith("quarantine/");
            as(admin, get("/api/v1/students/" + s + "/documents/" + docId + "/content")).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("document_not_available"));
            send(admin, post("/api/v1/students/" + s + "/documents/" + docId + "/verification"), "{\"status\":\"VERIFIED\"}").andExpect(status().isConflict());
            assertThat(scan()).isGreaterThanOrEqualTo(1);
            as(admin, get("/api/v1/students/" + s + "/documents")).andExpect(jsonPath("$[0].scanStatus").value("CLEAN")).andExpect(jsonPath("$[0].downloadable").value(true));
            byte[] got = as(admin, get("/api/v1/students/" + s + "/documents/" + docId + "/content")).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsByteArray();
            assertThat(got).isEqualTo(PDF);
            assertThat(ownerJdbc().sql("SELECT object_path FROM student_document WHERE id = ?").param(UUID.fromString(docId)).query(String.class).single()).startsWith("documents/");
            send(admin, post("/api/v1/students/" + s + "/documents/" + docId + "/verification"), "{\"status\":\"REJECTED\"}").andExpect(status().isBadRequest());
            send(admin, post("/api/v1/students/" + s + "/documents/" + docId + "/verification"), "{\"status\":\"VERIFIED\",\"note\":\"Matches original\"}")
                    .andExpect(status().isOk()).andExpect(jsonPath("$[0].verificationStatus").value("VERIFIED"));
            // infected files never become available
            upload(admin, s, "OTHER", "notes.pdf", ("%PDF-1.4\n" + FakeMalwareScanner.EICAR).getBytes(StandardCharsets.ISO_8859_1)).andExpect(status().isCreated());
            scan();
            JsonNode docs = body(as(admin, get("/api/v1/students/" + s + "/documents")));
            JsonNode infected = docs.get(0);
            assertThat(infected.path("scanStatus").asString()).isEqualTo("INFECTED");
            as(admin, get("/api/v1/students/" + s + "/documents/" + infected.path("id").asString() + "/content")).andExpect(status().isConflict());
            // scanner outage → ERROR, then a rescan when it is back
            scanner.available = false;
            upload(admin, s, "OTHER", "later.pdf", PDF).andExpect(status().isCreated());
            scan();
            String errored = body(as(admin, get("/api/v1/students/" + s + "/documents"))).get(0).path("id").asString();
            as(admin, get("/api/v1/students/" + s + "/documents")).andExpect(jsonPath("$[0].scanStatus").value("ERROR"));
            as(admin, get("/api/v1/students/" + s + "/documents/" + errored + "/content")).andExpect(status().isConflict());
            scanner.available = true;
            as(admin, post("/api/v1/students/" + s + "/documents/" + errored + "/rescan")).andExpect(status().isOk()).andExpect(jsonPath("$[0].scanStatus").value("PENDING"));
            scan();
            as(admin, get("/api/v1/students/" + s + "/documents")).andExpect(jsonPath("$[0].scanStatus").value("CLEAN"));
            // removal keeps the record; the file is purged after the retention period
            send(admin, post("/api/v1/students/" + s + "/documents/" + errored + "/remove"), "{\"reason\":\"Uploaded twice\"}").andExpect(status().isOk());
            as(admin, get("/api/v1/students/" + s + "/documents/" + errored + "/content")).andExpect(status().isConflict());
            as(admin, get("/api/v1/students/" + s + "/documents?includeRemoved=true")).andExpect(jsonPath("$.length()").value(3));
            assertThat(audits("student.document.uploaded")).isEqualTo(3);
            assertThat(audits("student.document.downloaded")).isEqualTo(1);
            assertThat(audits("student.document.download_denied")).isGreaterThanOrEqualTo(3);
            assertThat(audits("student.document.scanned")).isEqualTo(4);
        }

        @Test
        void fileTypeAndSizeChecksRejectUnexpectedFiles() throws Exception {
            UUID s = activeStudent("AIT-0710");
            upload(admin, s, "IDENTITY_PROOF", "virus.pdf", "MZ\u0090\u0000 not a pdf".getBytes(StandardCharsets.ISO_8859_1)).andExpect(status().isUnsupportedMediaType());
            upload(admin, s, "PHOTOGRAPH", "photo.pdf", PDF).andExpect(status().isUnsupportedMediaType());
            upload(admin, s, "PHOTOGRAPH", "photo.png", PNG).andExpect(status().isCreated());
            byte[] big = new byte[5 * 1024 * 1024 + 1];
            System.arraycopy(PDF, 0, big, 0, PDF.length);
            upload(admin, s, "OTHER", "big.pdf", big).andExpect(status().is(413));
            upload(admin, s, "UNKNOWN_CATEGORY", "a.pdf", PDF).andExpect(status().isBadRequest());
            upload(facultyCse, s, "OTHER", "a.pdf", PDF).andExpect(status().isForbidden());
            as(collegeAdminB, get("/api/v1/students/" + s + "/documents")).andExpect(status().isForbidden());
            // fail closed: outside development/test, uploads need a scanner
            assertThat(DocumentService.uploadsAllowed(Environment.PRODUCTION, false)).isFalse();
            assertThat(DocumentService.uploadsAllowed(Environment.STAGING, false)).isFalse();
            assertThat(DocumentService.uploadsAllowed(Environment.PRODUCTION, true)).isTrue();
            assertThat(DocumentService.uploadsAllowed(Environment.DEVELOPMENT, false)).isTrue();
        }
    }

    @Nested
    class Accounts {

        @Test
        void accountsAreCreatedOnDemandWithTheStudentRoleOnly() throws Exception {
            UUID prospective = student("AIT-0800", "Pia");
            send(admin, post("/api/v1/students/" + prospective + "/account"), "{\"email\":\"pia@college.test\"}").andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("student_not_eligible"));
            UUID s = activeStudent("AIT-0801");
            assertThat(ownerJdbc().sql("SELECT count(*) FROM user_account WHERE tenant_id = ?").param(tenant).query(Long.class).single()).isEqualTo(6);   // nothing automatic
            send(facultyCse, post("/api/v1/students/" + s + "/account"), "{\"email\":\"asha@college.test\"}").andExpect(status().isForbidden());
            send(officerA, post("/api/v1/students/" + s + "/account"), "{\"email\":\"asha@college.test\"}").andExpect(status().isForbidden());   // separately authorized
            send(admin, post("/api/v1/students/" + s + "/account"), "{\"email\":\"asha@college.test\"}").andExpect(status().isOk())
                    .andExpect(jsonPath("$.account.status").value("INVITED")).andExpect(jsonPath("$.account.invitationStatus").value("PENDING"));
            send(admin, post("/api/v1/students/" + s + "/account"), "{\"email\":\"other@college.test\"}").andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("account_exists"));
            UUID user = ownerJdbc().sql("SELECT user_account_id FROM student WHERE id = ?").param(s).query(UUID.class).single();
            List<String> roles = ownerJdbc().sql("SELECT r.code FROM role_assignment a JOIN role r ON r.id = a.role_id WHERE a.user_account_id = ?").param(user).query(String.class).list();
            assertThat(roles).containsExactly("STUDENT");
            UUID orgAdmin = ownerJdbc().sql("SELECT id FROM role WHERE code = 'ORG_ADMIN' AND tenant_id IS NULL").query(UUID.class).single();
            send(admin, post("/api/v1/users/" + user + "/role-assignments"), "{\"roleId\":\"" + orgAdmin + "\"}").andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("student_account_role_restricted"));
            UUID other = activeStudent("AIT-0802");
            send(admin, post("/api/v1/students/" + other + "/account"), "{\"email\":\"asha@college.test\"}").andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("email_in_use"));
            assertThat(audits("student.account.created")).isEqualTo(1);
            assertThat(audits("user.invitation.sent")).isEqualTo(1);
        }
    }

    @Test
    void migrationGrantsStudentPermissions() {
        List<String> admin = ownerJdbc().sql("SELECT unnest(permissions) FROM role WHERE code = 'INSTITUTION_ADMIN' AND tenant_id IS NULL").query(String.class).list();
        assertThat(admin).contains("student.read", "student.personal.read", "student.manage", "student.document.read", "student.document.manage", "student.account.manage");
        List<String> officer = ownerJdbc().sql("SELECT unnest(permissions) FROM role WHERE code = 'ADMISSION_OFFICER' AND tenant_id IS NULL").query(String.class).list();
        assertThat(officer).contains("student.manage").doesNotContain("student.account.manage");
        List<String> faculty = ownerJdbc().sql("SELECT unnest(permissions) FROM role WHERE code = 'FACULTY' AND tenant_id IS NULL").query(String.class).list();
        assertThat(faculty).contains("student.read").doesNotContain("student.personal.read", "student.document.read");
        assertThat(ownerJdbc().sql("SELECT count(*) FROM role WHERE code = 'STUDENT' AND tenant_id IS NULL AND permissions && ARRAY['student.read', 'student.manage']")
                .query(Long.class).single()).isZero();
    }
}
