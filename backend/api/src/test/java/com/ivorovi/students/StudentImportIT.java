package com.ivorovi.students;

import static com.ivorovi.support.Fixture.BELA_ORG_ADMIN_B;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ivorovi.platform.tabular.TabularFile;
import com.ivorovi.platform.tabular.TabularLimits;
import com.ivorovi.platform.tabular.TabularWriter;
import com.ivorovi.students.internal.StudentImportWorker;
import com.ivorovi.support.FakeMalwareScanner;
import com.ivorovi.support.Fixture;
import com.ivorovi.support.IntegrationTest;
import com.ivorovi.support.TestTokens;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.Year;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Increment 1.6: student bulk import. Fresh tenant per test:
 * <pre>
 *   University U ─ College A (AIT) ─ Department CSE ─ courses: B.Tech CSE (batches CSE-A capacity 2, CSE-B)
 *                │                 └ Department ME  ─ course ME (batch ME-A)
 *                └ College B (BCC) ─ Department PHY ─ B.Sc PHY (batch PHY-A)
 * </pre>
 * Jobs run when the test calls {@link StudentImportWorker#runPending} (the background listener and sweeper are disabled
 * in tests), so every state can be asserted deterministically.
 */
class StudentImportIT extends IntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String HEADER = "Student ID,First name,Last name,Date of birth,Gender,Personal email,Mobile,Admission number,Admission date,Batch,"
            + "Semester,Student status,Guardian 1 name,Guardian 1 relationship,Guardian 1 mobile";

    @Autowired StudentImportWorker worker;
    @Autowired FakeMalwareScanner scanner;

    private UUID tenant, university, collegeA, cse, me, collegeB, phy;
    private Fixture.Caller admin, collegeAdminA, officerA, headCse, collegeAdminB;
    private UUID btech, courseCse, courseMe, bsc, coursePhy, y1, batchCseA, batchCseB, batchMeA, batchPhy;
    private final String today = LocalDate.now().toString();

    @BeforeEach
    void freshTenant() throws Exception {
        scanner.available = true;
        tenant = UUID.randomUUID();
        ownerJdbc().sql("INSERT INTO tenant (id, code, name, status) VALUES (?, ?, 'Import test org', 'ACTIVE')")
                .params(tenant, "imp-" + tenant.toString().substring(0, 8)).update();
        university = unit("UNIVERSITY", null, "U");
        collegeA = unit("COLLEGE", university, "AIT");
        cse = unit("DEPARTMENT", collegeA, "CSE");
        me = unit("DEPARTMENT", collegeA, "MED");
        collegeB = unit("COLLEGE", university, "BCC");
        phy = unit("DEPARTMENT", collegeB, "PHY");
        admin = account("ORG_ADMIN", null);
        collegeAdminA = account("INSTITUTION_ADMIN", collegeA);
        officerA = account("ADMISSION_OFFICER", collegeA);
        headCse = account("DEPARTMENT_HEAD", cse);
        collegeAdminB = account("INSTITUTION_ADMIN", collegeB);
        btech = id(send(admin, post("/api/v1/academics/programs"), "{\"code\":\"BTECH\",\"name\":\"B.Tech\",\"qualificationLevel\":\"UNDERGRADUATE\",\"ownerOrgUnitId\":\"" + collegeA + "\",\"termSystem\":\"SEMESTER\",\"durationYears\":4}"));
        courseCse = id(send(admin, post("/api/v1/academics/courses"), "{\"code\":\"CSE\",\"name\":\"Computer Science\",\"programId\":\"" + btech + "\",\"departmentId\":\"" + cse + "\"}"));
        courseMe = id(send(admin, post("/api/v1/academics/courses"), "{\"code\":\"ME\",\"name\":\"Mechanical\",\"programId\":\"" + btech + "\",\"departmentId\":\"" + me + "\"}"));
        bsc = id(send(admin, post("/api/v1/academics/programs"), "{\"code\":\"BSC\",\"name\":\"B.Sc\",\"qualificationLevel\":\"UNDERGRADUATE\",\"ownerOrgUnitId\":\"" + collegeB + "\",\"termSystem\":\"SEMESTER\",\"durationYears\":3}"));
        coursePhy = id(send(admin, post("/api/v1/academics/courses"), "{\"code\":\"PHY\",\"name\":\"Physics\",\"programId\":\"" + bsc + "\",\"departmentId\":\"" + phy + "\"}"));
        int yr = Year.now().getValue();
        y1 = UUID.fromString(body(send(admin, post("/api/v1/academics/academic-years"), "{\"code\":\"Y1\",\"name\":\"Year one\",\"startDate\":\"" + (yr - 1) + "-07-01\",\"endDate\":\"" + yr + "-06-30\"}")).path("year").path("id").asString());
        batchCseA = batch(courseCse, "CSE-A", 2);
        batchCseB = batch(courseCse, "CSE-B", null);
        batchMeA = batch(courseMe, "ME-A", null);
        batchPhy = batch(coursePhy, "PHY-A", null);
    }

    // ------------------------------------------------------------------------------------------------ fixture helpers

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
                .params(id, tenant, subject, id + "@imports.test", roleCode + " user").update();
        UUID role = ownerJdbc().sql("SELECT id FROM role WHERE code = ? AND tenant_id IS NULL").param(roleCode).query(UUID.class).single();
        ownerJdbc().sql("INSERT INTO role_assignment (id, tenant_id, user_account_id, role_id, scope_type, scope_id) VALUES (?, ?, ?, ?, ?, ?)")
                .params(UUID.randomUUID(), tenant, id, role, unit == null ? "TENANT" : "ORG_UNIT", unit == null ? tenant : unit).update();
        return new Fixture.Caller(subject, tenant);
    }

    private UUID batch(UUID course, String code, Integer capacity) throws Exception {
        return id(send(admin, post("/api/v1/academics/batches"), "{\"code\":\"" + code + "\",\"name\":\"Batch " + code + "\",\"courseId\":\"" + course
                + "\",\"academicYearId\":\"" + y1 + "\",\"admissionYear\":" + (Year.now().getValue() - 1) + (capacity == null ? "" : ",\"capacity\":" + capacity) + "}"));
    }

    private ResultActions as(Fixture.Caller c, AbstractMockHttpServletRequestBuilder<?> r) throws Exception {
        r.header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.forCaller(c));
        return mvc.perform(r);
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

    // ------------------------------------------------------------------------------------------------ import helpers

    /** One CSV line for the standard header: id, first, last, dob, admission no (null = no academic data), batch. */
    private static String line(String id, String first, String last, String dob, String email, String admissionNo, String batch, String status) {
        return String.join(",", nz(id), first, last, nz(dob), "Female", nz(email), "+91 98450 " + (10000 + Math.abs((id + first).hashCode()) % 89999),
                nz(admissionNo), admissionNo == null ? "" : LocalDate.now().minusDays(30).toString(), nz(batch), batch == null ? "" : "1", nz(status),
                "Parent of " + first, "Mother", "+91 90000 " + (10000 + Math.abs(first.hashCode()) % 89999));
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static boolean absent(JsonNode n) { return n.isNull() || n.isMissingNode(); }

    /** Letters-only synthetic names (digits are not valid in names). */
    private static String letters(int n) {
        StringBuilder b = new StringBuilder();
        do { b.append((char) ('a' + n % 26)); n /= 26; } while (n > 0);
        return Character.toUpperCase(b.charAt(0)) + b.substring(1);
    }

    private static byte[] csv(String... lines) {
        return (HEADER + "\n" + String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private ResultActions uploadRaw(Fixture.Caller c, UUID institution, String name, byte[] content, String importKey) throws Exception {
        MockMultipartHttpServletRequestBuilder r = multipart("/api/v1/student-imports").file(new MockMultipartFile("file", name, "application/octet-stream", content))
                .param("institutionId", institution.toString());
        if (importKey != null) r.param("importKey", importKey);
        return as(c, r);
    }

    private JsonNode upload(Fixture.Caller c, UUID institution, String name, byte[] content) throws Exception {
        return body(uploadRaw(c, institution, name, content, null).andExpect(status().isCreated()));
    }

    private JsonNode job(Fixture.Caller c, String id) throws Exception {
        return body(as(c, get("/api/v1/student-imports/" + id)).andExpect(status().isOk()));
    }

    /** Applies the suggested mapping and runs validation. */
    private JsonNode mapAndValidate(Fixture.Caller c, JsonNode job) throws Exception {
        StringBuilder cols = new StringBuilder("[");
        for (JsonNode m : job.path("mapping")) {
            if (absent(m.path("field"))) continue;
            if (cols.length() > 1) cols.append(',');
            cols.append("{\"column\":").append(m.path("column").asInt()).append(",\"field\":\"").append(m.path("field").asString()).append("\"}");
        }
        cols.append(']');
        String id = job.path("id").asString();
        send(c, put("/api/v1/student-imports/" + id + "/mapping"), "{\"columns\":" + cols + ",\"version\":" + job.path("version").asLong() + "}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("QUEUED"));
        worker.runPending(tenant);
        return job(c, id);
    }

    private ResultActions confirmRaw(Fixture.Caller c, JsonNode job, String mode) throws Exception {
        int expected = job.path("counts").path("toCreate").asInt() + job.path("counts").path("toUpdate").asInt();
        return send(c, post("/api/v1/student-imports/" + job.path("id").asString() + "/confirm"),
                "{\"mode\":\"" + mode + "\",\"version\":" + job.path("version").asLong() + ",\"expectedRows\":" + expected + ",\"acknowledged\":true}");
    }

    private JsonNode confirmAndRun(Fixture.Caller c, JsonNode job, String mode) throws Exception {
        confirmRaw(c, job, mode).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("QUEUED"));
        worker.runPending(tenant);
        return job(c, job.path("id").asString());
    }

    private JsonNode rows(Fixture.Caller c, String id, String filter) throws Exception {
        return body(as(c, get("/api/v1/student-imports/" + id + "/rows").param("filter", filter).param("size", "100")).andExpect(status().isOk())).path("items");
    }

    private JsonNode row(Fixture.Caller c, String id, int rowNo) throws Exception {
        for (JsonNode r : rows(c, id, "ALL")) if (r.path("rowNo").asInt() == rowNo) return r;
        throw new AssertionError("row " + rowNo + " missing");
    }

    private long students() {
        return ownerJdbc().sql("SELECT count(*) FROM student WHERE tenant_id = ?").param(tenant).query(Long.class).single();
    }

    private long audits(String action) {
        return ownerJdbc().sql("SELECT count(*) FROM audit_event WHERE tenant_id = ? AND action = ?").params(tenant, action).query(Long.class).single();
    }

    private String report(Fixture.Caller c, String id, String kind) throws Exception {
        return as(c, get("/api/v1/student-imports/" + id + "/reports/" + kind).param("format", "CSV")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    // ================================================================================================ 1–5: formats, templates, mapping

    @Nested
    class FilesTemplatesAndMapping {

        @Test
        void standardTemplatesDownloadAsXlsxAndCsv(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
            byte[] xlsx = as(admin, get("/api/v1/student-imports/template").param("institutionId", collegeA.toString()).param("format", "XLSX"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
            Path f = Files.write(dir.resolve("t.xlsx"), xlsx);
            TabularFile.Description d = TabularFile.open(TabularFile.Format.XLSX, f, TabularLimits.of(10)).describe();
            assertThat(d.headers()).contains("First name", "Student ID", "Admission number", "Batch", "Guardian 1 name", "Guardian 2 relationship");
            String csv = as(admin, get("/api/v1/student-imports/template").param("format", "CSV").param("sections", "PERSONAL", "GUARDIAN"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(csv).startsWith("﻿First name,Middle name,Last name,Date of birth").contains("Guardian 1 name").doesNotContain("Batch");
            as(headCse, get("/api/v1/student-imports/template")).andExpect(status().isForbidden());
            assertThat(audits("student.import.template_downloaded")).isEqualTo(2);
        }

        @Test
        void csvAndXlsxUploadsAreDescribedAndAutomaticallyMapped() throws Exception {
            JsonNode j = upload(admin, collegeA, "students.csv", csv(line("AIT-1001", "Asha", "Rao", "2006-04-12", "asha@example.test", null, null, null)));
            assertThat(j.path("status").asString()).isEqualTo("UPLOADED");
            assertThat(j.path("fileFormat").asString()).isEqualTo("CSV");
            assertThat(j.path("counts").path("total").asInt()).isEqualTo(1);
            assertThat(j.path("scanStatus").asString()).isEqualTo("CLEAN");
            assertThat(j.path("mapping").get(0).path("field").asString()).isEqualTo("student_no");
            assertThat(j.path("mapping").get(0).path("source").asString()).isEqualTo("AUTO");
            assertThat(j.path("unmappedColumns")).isEmpty();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (TabularWriter w = TabularWriter.of(TabularFile.Format.XLSX, out)) {
                w.header(List.of("Student No", "Given name", "Surname", "DOB", "Notes"));
                w.row(List.of("AIT-2001", "Ravi", "Kumar", "14/08/2006", "transferred"));
            }
            JsonNode x = upload(admin, collegeA, "legacy.xlsx", out.toByteArray());
            assertThat(x.path("fileFormat").asString()).isEqualTo("XLSX");
            assertThat(x.path("sheetName").asString()).isEqualTo("Sheet1");
            List<String> fields = new ArrayList<>();
            for (JsonNode m : x.path("mapping")) fields.add(absent(m.path("field")) ? null : m.path("field").asString());
            assertThat(fields).containsExactly("student_no", "first_name", "last_name", "date_of_birth", null);
            assertThat(x.path("unmappedColumns").get(0).asString()).isEqualTo("Notes");
            JsonNode validated = mapAndValidate(admin, x);
            assertThat(validated.path("status").asString()).isEqualTo("READY");
            assertThat(validated.path("counts").path("valid").asInt()).isEqualTo(1);
        }

        @Test
        void ambiguousColumnsAreNeverMappedSilently() throws Exception {
            byte[] content = "First name,Email,E-mail,Phone\nAsha,a@x.test,b@x.test,9845012345\n".getBytes(StandardCharsets.UTF_8);
            JsonNode j = upload(admin, collegeA, "ambiguous.csv", content);
            JsonNode m = j.path("mapping");
            assertThat(absent(m.get(1).path("field"))).isTrue();
            assertThat(m.get(1).path("note").asString()).contains("several columns could be Personal email");
            assertThat(absent(m.get(2).path("field"))).isTrue();
            assertThat(m.get(3).path("field").asString()).isEqualTo("mobile");
            // mapping a field twice is refused; missing required fields are named
            String id = j.path("id").asString();
            send(admin, put("/api/v1/student-imports/" + id + "/mapping"), "{\"columns\":[{\"column\":0,\"field\":\"first_name\"},{\"column\":1,\"field\":\"personal_email\"},"
                    + "{\"column\":2,\"field\":\"personal_email\"}],\"version\":" + j.path("version").asLong() + "}")
                    .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("duplicate_field_mapping"));
            send(admin, put("/api/v1/student-imports/" + id + "/mapping"), "{\"columns\":[{\"column\":1,\"field\":\"personal_email\"}],\"version\":" + j.path("version").asLong() + "}")
                    .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("First name")));
        }

        @Test
        void unsupportedMalformedAndEmptyFilesAreRejected() throws Exception {
            uploadRaw(admin, collegeA, "macros.xlsm", new byte[] {'P', 'K', 3, 4, 1, 2}, null).andExpect(status().isUnsupportedMediaType())
                    .andExpect(jsonPath("$.code").value("macros_not_allowed"));
            uploadRaw(admin, collegeA, "notes.pdf", "x".getBytes(StandardCharsets.UTF_8), null).andExpect(status().isUnsupportedMediaType());
            uploadRaw(admin, collegeA, "fake.xlsx", "a,b\n1,2\n".getBytes(StandardCharsets.UTF_8), null).andExpect(status().isUnsupportedMediaType());
            uploadRaw(admin, collegeA, "only-header.csv", (HEADER + "\n").getBytes(StandardCharsets.UTF_8), null).andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("no_rows"));
            uploadRaw(admin, collegeA, "broken.csv", "A,B\n1,\"open\n".getBytes(StandardCharsets.UTF_8), null).andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("malformed_file"));
            scanner.available = true;
            assertThat(audits("student.import.rejected")).isEqualTo(5);
        }

        @Test
        void savedMappingsAreReusedForLaterFiles() throws Exception {
            byte[] content = "Roll,Given,Family,Code\n1,Asha,Rao,AIT-3001\n".getBytes(StandardCharsets.UTF_8);
            JsonNode j = upload(admin, collegeA, "custom.csv", content);
            send(admin, put("/api/v1/student-imports/" + j.path("id").asString() + "/mapping"), "{\"columns\":[{\"column\":0,\"field\":\"roll_no\"},"
                    + "{\"column\":1,\"field\":\"first_name\"},{\"column\":2,\"field\":\"last_name\"},{\"column\":3,\"field\":\"student_no\"}],\"version\":"
                    + j.path("version").asLong() + ",\"saveAsName\":\"Legacy ERP export\"}").andExpect(status().isOk());
            JsonNode saved = body(as(admin, get("/api/v1/student-imports/mappings").param("institutionId", collegeA.toString())).andExpect(status().isOk()));
            assertThat(saved).hasSize(1);
            assertThat(saved.get(0).path("name").asString()).isEqualTo("Legacy ERP export");
            String templateId = saved.get(0).path("id").asString();
            // same headers in another order: the saved mapping is applied by header name
            byte[] next = "Code,Family,Given,Roll\nAIT-3002,Iyer,Meera,2\n".getBytes(StandardCharsets.UTF_8);
            JsonNode k = body(as(admin, multipart("/api/v1/student-imports").file(new MockMultipartFile("file", "next.csv", "text/csv", next))
                    .param("institutionId", collegeA.toString()).param("mappingTemplateId", templateId)).andExpect(status().isCreated()));
            assertThat(k.path("mapping").get(0).path("field").asString()).isEqualTo("student_no");
            assertThat(k.path("mapping").get(0).path("source").asString()).isEqualTo("SAVED");
            assertThat(k.path("mapping").get(3).path("field").asString()).isEqualTo("roll_no");
            assertThat(audits("student.import.mapping_saved")).isEqualTo(1);
            // college B's administrator cannot use or see college A's mapping
            as(collegeAdminB, multipart("/api/v1/student-imports").file(new MockMultipartFile("file", "x.csv", "text/csv", next))
                    .param("institutionId", collegeB.toString()).param("mappingTemplateId", templateId)).andExpect(status().isForbidden());
            assertThat(body(as(collegeAdminB, get("/api/v1/student-imports/mappings"))).size()).isZero();
        }
    }

    // ================================================================================================ 6–7, 10–11, 15–16, 18–19

    @Nested
    class ValidationAndValidRowsOnly {

        @Test
        void rowLevelAndReferenceValidationThenValidRowsOnlyImport() throws Exception {
            byte[] file = csv(
                    line("AIT-4001", "Asha", "Rao", "2006-04-12", "asha.rao@example.test", "ADM-4001", "CSE-B", "Active"),
                    line("AIT-4002", "Ravi", "Kumar", "14/08/2006", "not-an-email", "ADM-4002", "CSE-B", null),
                    line("AIT-4003", "Meera", "Iyer", "2006-01-01", "meera@example.test", "ADM-4003", "PHY-A", null),
                    line("AIT-4004", "John", "Mathew", "2030-01-01", "john@example.test", null, null, null),
                    line("AIT-4005", "Sara", "Das", "", "sara@example.test", "ADM-4005", "ME-A", "Enrolled"));
            JsonNode j = mapAndValidate(officerA, upload(officerA, collegeA, "intake.csv", file));
            String id = j.path("id").asString();
            assertThat(j.path("status").asString()).isEqualTo("READY");
            JsonNode counts = j.path("counts");
            assertThat(counts.path("total").asInt()).isEqualTo(5);
            assertThat(counts.path("valid").asInt()).isEqualTo(2);
            assertThat(counts.path("invalid").asInt()).isEqualTo(3);
            assertThat(counts.path("toCreate").asInt()).isEqualTo(2);

            JsonNode r2 = row(officerA, id, 3);
            assertThat(r2.path("status").asString()).isEqualTo("INVALID");
            assertThat(r2.path("issues").get(0).path("column").asString()).isEqualTo("Personal email");
            assertThat(r2.path("issues").get(0).path("columnNo").asInt()).isEqualTo(6);
            assertThat(r2.path("issues").get(0).path("hint").asString()).contains("name@example.com");
            // batch of another institution is not a reference of College A
            JsonNode r3 = row(officerA, id, 4);
            assertThat(r3.path("issues").get(0).path("code").asString()).isEqualTo("unknown_batch");
            assertThat(r3.path("issues").get(0).path("category").asString()).isEqualTo("REFERENCE");
            assertThat(row(officerA, id, 5).path("issues").get(0).path("code").asString()).isEqualTo("invalid_date_of_birth");

            String errors = report(officerA, id, "errors");
            assertThat(errors).contains("Row,Column,Field,Severity,Error category,Error description,Recommended correction");
            assertThat(errors).contains("3,Personal email,personal_email,ERROR,Format,Not a valid email address.");
            assertThat(errors).doesNotContain("not-an-email");
            String corrections = report(officerA, id, "corrections");
            assertThat(corrections).contains("not-an-email").contains("Row 3: Personal email: Not a valid email address.");

            confirmRaw(officerA, j, "ALL_OR_NOTHING").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("all_or_nothing_blocked"));
            send(officerA, post("/api/v1/student-imports/" + id + "/confirm"), "{\"mode\":\"VALID_ROWS_ONLY\",\"version\":" + j.path("version").asLong()
                    + ",\"expectedRows\":3,\"acknowledged\":true}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("concurrent_modification"));
            send(officerA, post("/api/v1/student-imports/" + id + "/confirm"), "{\"mode\":\"VALID_ROWS_ONLY\",\"version\":" + j.path("version").asLong()
                    + ",\"expectedRows\":2,\"acknowledged\":false}").andExpect(status().isBadRequest());

            JsonNode done = confirmAndRun(officerA, j, "VALID_ROWS_ONLY");
            assertThat(done.path("status").asString()).isEqualTo("PARTIALLY_COMPLETED");
            assertThat(done.path("progress").path("imported").asInt()).isEqualTo(2);
            assertThat(done.path("progress").path("skipped").asInt()).isEqualTo(3);
            assertThat(done.path("progress").path("failed").asInt()).isZero();
            assertThat(done.path("progress").path("percent").asInt()).isEqualTo(100);

            Map<String, Object> asha = ownerJdbc().sql("SELECT s.status, s.active, e.batch_id, a.admission_no, (SELECT count(*) FROM guardian g WHERE g.student_id = s.id) AS guardians "
                    + "FROM student s JOIN enrolment e ON e.student_id = s.id AND e.is_current JOIN admission_record a ON a.student_id = s.id WHERE s.student_no = 'AIT-4001' AND s.tenant_id = ?")
                    .param(tenant).query().singleRow();
            assertThat(asha.get("status")).isEqualTo("ACTIVE");
            assertThat(asha.get("active")).isEqualTo(true);
            assertThat(asha.get("batch_id")).isEqualTo(batchCseB);
            assertThat(((Number) asha.get("guardians")).intValue()).isEqualTo(1);
            assertThat(ownerJdbc().sql("SELECT status FROM student WHERE student_no = 'AIT-4005' AND tenant_id = ?").param(tenant).query(String.class).single()).isEqualTo("ENROLLED");
            assertThat(students()).isEqualTo(2);
            // historical admission dates are preserved (the profile's first status date is the admission date)
            assertThat(ownerJdbc().sql("SELECT min(effective_on) FROM student_status_history h JOIN student s ON s.id = h.student_id WHERE s.student_no = 'AIT-4001' AND s.tenant_id = ?")
                    .param(tenant).query(LocalDate.class).single()).isEqualTo(LocalDate.now().minusDays(30));

            String result = report(officerA, id, "result");
            assertThat(result).contains("2,IMPORTED,Asha Rao,AIT-4001").contains("3,SKIPPED,Ravi Kumar,AIT-4002,invalid");

            // history and dashboard
            JsonNode history = body(as(officerA, get("/api/v1/student-imports"))).path("items");
            assertThat(history.get(0).path("id").asString()).isEqualTo(id);
            assertThat(history.get(0).path("succeededRows").asInt()).isEqualTo(2);
            assertThat(history.get(0).path("skippedRows").asInt()).isEqualTo(3);
            assertThat(body(as(officerA, get("/api/v1/student-imports/overview"))).path("studentsImportedLast30Days").asLong()).isEqualTo(2);

            // audited without personal values
            for (String a : List.of("student.import.uploaded", "student.import.mapped", "student.import.validated", "student.import.confirmed",
                    "student.import.processing", "student.import.completed")) {
                assertThat(audits(a)).as(a).isEqualTo(1);
            }
            assertThat(audits("student.imported")).isEqualTo(2);
            assertThat(audits("student.import.report_downloaded")).isEqualTo(3);
            assertThat(audits("student.import.values_viewed")).isPositive();
            String trail = String.join("\n", ownerJdbc().sql("SELECT details::text FROM audit_event WHERE tenant_id = ?").param(tenant).query(String.class).list());
            assertThat(trail).doesNotContain("asha.rao@example.test").doesNotContain("+91 98450").doesNotContain("2006-04-12").doesNotContain("Parent of");
            // raw values are stored encrypted
            assertThat(String.join("", ownerJdbc().sql("SELECT cells_enc FROM student_import_row WHERE tenant_id = ?").param(tenant).query(String.class).list()))
                    .startsWith("v1:").doesNotContain("asha");
        }

        @Test
        void formulaCellsAreRejectedAndNeverEvaluated() throws Exception {
            String types = "<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>";
            String wb = "<?xml version=\"1.0\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"r\"><sheets><sheet name=\"S\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>";
            String rels = "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Target=\"worksheets/sheet1.xml\"/></Relationships>";
            String sheet = "<?xml version=\"1.0\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>"
                    + "<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>Student ID</t></is></c><c r=\"B1\" t=\"inlineStr\"><is><t>First name</t></is></c></row>"
                    + "<row r=\"2\"><c r=\"A2\" t=\"inlineStr\"><is><t>AIT-5001</t></is></c><c r=\"B2\" t=\"str\"><f>HYPERLINK(\"http://evil\",\"Asha\")</f><v>Asha</v></c></row>"
                    + "</sheetData></worksheet>";
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(out)) {
                for (Map.Entry<String, String> e : Map.of("[Content_Types].xml", types, "xl/workbook.xml", wb, "xl/_rels/workbook.xml.rels", rels, "xl/worksheets/sheet1.xml", sheet).entrySet()) {
                    z.putNextEntry(new java.util.zip.ZipEntry(e.getKey()));
                    z.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                    z.closeEntry();
                }
            }
            JsonNode j = mapAndValidate(admin, upload(admin, collegeA, "formula.xlsx", out.toByteArray()));
            JsonNode r = row(admin, j.path("id").asString(), 2);
            assertThat(r.path("status").asString()).isEqualTo("INVALID");
            assertThat(r.path("issues").get(0).path("code").asString()).isEqualTo("formula_not_allowed");
            assertThat(r.path("values").path("first_name").asString()).isEqualTo("=(formula)");
        }

        @Test
        void guardiansNeedAMobileOrAnEmail() throws Exception {
            byte[] file = "Student ID,First name,Guardian 1 name,Guardian 1 relationship\nAIT-5101,Asha,Lata Rao,Mother\n".getBytes(StandardCharsets.UTF_8);
            JsonNode j = mapAndValidate(admin, upload(admin, collegeA, "guardian.csv", file));
            JsonNode r = row(admin, j.path("id").asString(), 2);
            assertThat(r.path("status").asString()).isEqualTo("INVALID");
            assertThat(r.path("issues").get(0).path("code").asString()).isEqualTo("guardian_contact_required");
        }
    }

    // ================================================================================================ 9: all-or-nothing

    @Nested
    class AllOrNothing {

        @Test
        void completesAtomicallyAndPublishesStudentsOnlyAtTheEnd() throws Exception {
            JsonNode j = mapAndValidate(admin, upload(admin, collegeA, "aon.csv", csv(
                    line("AIT-6001", "Asha", "Rao", "2006-04-12", null, "ADM-6001", "CSE-B", "Active"),
                    line("AIT-6002", "Ravi", "Kumar", "2006-05-12", null, "ADM-6002", "CSE-B", "Active"))));
            JsonNode done = confirmAndRun(admin, j, "ALL_OR_NOTHING");
            assertThat(done.path("status").asString()).isEqualTo("COMPLETED");
            assertThat(done.path("rolledBack").asBoolean()).isFalse();
            assertThat(ownerJdbc().sql("SELECT count(*) FROM student WHERE tenant_id = ? AND active AND status = 'ACTIVE'").param(tenant).query(Long.class).single()).isEqualTo(2);
        }

        @Test
        void aFailingRowRollsBackEveryCommittedRow() throws Exception {
            // CSE-A has capacity 2: the third enrolment fails during processing (capacity is enforced by the enrolment service)
            UUID existing = id(send(admin, post("/api/v1/students"), "{\"institutionId\":\"" + collegeA + "\",\"studentNo\":\"AIT-6100\",\"firstName\":\"Nila\",\"lastName\":\"Shah\"}"));
            JsonNode j = mapAndValidate(admin, upload(admin, collegeA, "aon-fail.csv", csv(
                    line("AIT-6100", "Nila", "Shah", null, null, "ADM-6100", "CSE-A", null),
                    line("AIT-6101", "Asha", "Rao", "2006-04-12", null, "ADM-6101", "CSE-A", null),
                    line("AIT-6102", "Ravi", "Kumar", "2006-05-12", null, "ADM-6102", "CSE-A", null))));
            String id = j.path("id").asString();
            assertThat(row(admin, id, 2).path("matchCategory").asString()).isEqualTo("EXISTING_NEW_ENROLMENT");
            JsonNode decided = body(send(admin, post("/api/v1/student-imports/" + id + "/decisions"), "{\"rows\":[2],\"decision\":\"ADD_TO_EXISTING\",\"version\":"
                    + j.path("version").asLong() + "}").andExpect(status().isOk()));
            long before = students();
            JsonNode done = confirmAndRun(admin, decided, "ALL_OR_NOTHING");
            assertThat(done.path("status").asString()).isEqualTo("FAILED");
            assertThat(done.path("failureKind").asString()).isEqualTo("DATA");
            assertThat(done.path("rolledBack").asBoolean()).isTrue();
            assertThat(done.path("failureMessage").asString()).contains("Row 4").contains("full");
            assertThat(students()).isEqualTo(before);
            // the matched student is restored exactly: no admission, no enrolment, prospective and active
            Map<String, Object> s = ownerJdbc().sql("SELECT status, active, (SELECT count(*) FROM admission_record a WHERE a.student_id = s.id) AS admissions, "
                    + "(SELECT count(*) FROM enrolment e WHERE e.student_id = s.id) AS enrolments, (SELECT count(*) FROM guardian g WHERE g.student_id = s.id) AS guardians FROM student s WHERE id = ?")
                    .param(existing).query().singleRow();
            assertThat(s.get("status")).isEqualTo("PROSPECTIVE");
            assertThat(s.get("active")).isEqualTo(true);
            assertThat(((Number) s.get("admissions")).intValue() + ((Number) s.get("enrolments")).intValue() + ((Number) s.get("guardians")).intValue()).isZero();
            assertThat(ownerJdbc().sql("SELECT count(*) FROM enrolment WHERE tenant_id = ?").param(tenant).query(Long.class).single()).isZero();
            JsonNode outcomes = rows(admin, id, "ALL");
            assertThat(outcomes.get(0).path("outcome").asString()).isEqualTo("ROLLED_BACK");
            assertThat(outcomes.get(1).path("outcome").asString()).isEqualTo("ROLLED_BACK");
            assertThat(outcomes.get(2).path("outcome").asString()).isEqualTo("FAILED");
            assertThat(outcomes.get(2).path("outcomeCode").asString()).isEqualTo("batch_full");
            assertThat(audits("student.import.rolled_back")).isEqualTo(1);
            // a data failure is not retried automatically; the file must be corrected
            as(admin, post("/api/v1/student-imports/" + id + "/retry")).andExpect(status().isConflict());
        }
    }

    // ================================================================================================ 8: duplicates

    @Nested
    class Duplicates {

        @Test
        void withinTheFileAndAgainstExistingStudentsWithAuthorizedDecisions() throws Exception {
            UUID asha = id(send(admin, post("/api/v1/students"), "{\"institutionId\":\"" + collegeA + "\",\"studentNo\":\"AIT-7001\",\"registrationNo\":\"REG-7001\","
                    + "\"firstName\":\"Asha\",\"lastName\":\"Rao\",\"personal\":{\"dateOfBirth\":\"2006-04-12\"}}"));
            UUID other = id(send(admin, post("/api/v1/students"), "{\"institutionId\":\"" + collegeB + "\",\"studentNo\":\"BCC-7001\",\"firstName\":\"Bina\",\"lastName\":\"Roy\"}"));
            String header = "Student ID,Registration number,First name,Last name,Date of birth,Admission number,Admission date,Batch";
            String d = LocalDate.now().minusDays(10).toString();
            byte[] file = (header + "\n"
                    + "AIT-7001,REG-7001,Asha,Rao,2006-04-12,ADM-7001," + d + ",CSE-B\n"       // 2: existing student, new enrolment
                    + "AIT-7002,,Ravi,Kumar,2006-05-01,ADM-7002," + d + ",CSE-B\n"              // 3: new
                    + "AIT-7002,,Ravi,Kumar,2006-05-01,ADM-7002," + d + ",CSE-B\n"              // 4: exact duplicate of row 3
                    + "AIT-7003,,Asha,Rao,2006-04-12,,,\n"                                       // 5: possible duplicate of Asha (name + DOB)
                    + "AIT-7004,REG-7001,Someone,Else,,,,\n"                                     // 6: registration belongs to Asha → conflicting identity
                    + "AIT-7005,,Ravi,Kumar,2006-05-01,,,\n"                                     // 7: possibly the same person as row 3
                    + "BCC-7001,,Bina,Roy,,,,\n"                                                 // 8: ID used in another institution
                    + "AIT-7002,,Ravindra,Kumar,2006-05-01,,,\n").getBytes(StandardCharsets.UTF_8); // 9: same ID as row 3 with other details
            JsonNode j = mapAndValidate(officerA, upload(officerA, collegeA, "dupes.csv", file));
            String id = j.path("id").asString();
            assertThat(row(officerA, id, 2).path("matchCategory").asString()).isEqualTo("EXISTING_NEW_ENROLMENT");
            assertThat(row(officerA, id, 2).path("matchStudentId").asString()).isEqualTo(asha.toString());
            assertThat(row(officerA, id, 2).path("allowedDecisions").toString()).contains("ADD_TO_EXISTING").contains("SKIP").doesNotContain("CREATE_NEW");
            assertThat(row(officerA, id, 3).path("status").asString()).isEqualTo("VALID");
            assertThat(row(officerA, id, 4).path("status").asString()).isEqualTo("DUPLICATE");
            assertThat(row(officerA, id, 4).path("matchCategory").asString()).isEqualTo("IN_FILE_DUPLICATE");
            assertThat(row(officerA, id, 4).path("matchRowNo").asInt()).isEqualTo(3);
            assertThat(row(officerA, id, 5).path("matchCategory").asString()).isEqualTo("POTENTIAL_DUPLICATE");
            assertThat(row(officerA, id, 5).path("matchReason").asString()).contains("AIT-7001").contains("name and date of birth");
            assertThat(row(officerA, id, 6).path("status").asString()).isEqualTo("CONFLICT");
            assertThat(row(officerA, id, 6).path("matchCategory").asString()).isEqualTo("CONFLICTING_IDENTITY");
            assertThat(row(officerA, id, 7).path("matchCategory").asString()).isEqualTo("IN_FILE_POTENTIAL");
            assertThat(row(officerA, id, 8).path("matchReason").asString()).contains("another institution").doesNotContain("Bina");
            assertThat(row(officerA, id, 9).path("status").asString()).isEqualTo("CONFLICT");
            assertThat(row(officerA, id, 9).path("matchCategory").asString()).isEqualTo("IN_FILE_DUPLICATE");
            JsonNode counts = j.path("counts");
            assertThat(counts.path("duplicates").asInt()).isEqualTo(1);
            assertThat(counts.path("potentialDuplicates").asInt()).isEqualTo(3);
            assertThat(counts.path("conflicts").asInt()).isEqualTo(3);
            assertThat(counts.path("unresolved").asInt()).isEqualTo(3);

            // ambiguous matches need an authorized decision; no automatic merge
            confirmRaw(officerA, j, "VALID_ROWS_ONLY").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("decisions_pending"));
            send(officerA, post("/api/v1/student-imports/" + id + "/decisions"), "{\"rows\":[5],\"decision\":\"ADD_TO_EXISTING\",\"version\":" + j.path("version").asLong() + "}")
                    .andExpect(status().isUnprocessableContent());
            send(headCse, post("/api/v1/student-imports/" + id + "/decisions"), "{\"rows\":[5],\"decision\":\"SKIP\",\"version\":" + j.path("version").asLong() + "}")
                    .andExpect(status().isForbidden());
            JsonNode v1 = body(send(officerA, post("/api/v1/student-imports/" + id + "/decisions"), "{\"rows\":[2],\"decision\":\"ADD_TO_EXISTING\",\"version\":"
                    + j.path("version").asLong() + "}").andExpect(status().isOk()));
            JsonNode v2 = body(send(officerA, post("/api/v1/student-imports/" + id + "/decisions"), "{\"category\":\"POTENTIAL_DUPLICATE\",\"decision\":\"SKIP\",\"version\":"
                    + v1.path("version").asLong() + "}").andExpect(status().isOk()));
            JsonNode v3 = body(send(officerA, post("/api/v1/student-imports/" + id + "/decisions"), "{\"rows\":[7],\"decision\":\"CREATE_NEW\",\"version\":"
                    + v2.path("version").asLong() + "}").andExpect(status().isOk()));
            assertThat(v3.path("counts").path("unresolved").asInt()).isZero();
            assertThat(v3.path("counts").path("toCreate").asInt()).isEqualTo(2);
            assertThat(v3.path("counts").path("toUpdate").asInt()).isEqualTo(1);
            assertThat(row(officerA, id, 2).path("decidedByName").asString()).isEqualTo("ADMISSION_OFFICER user");
            assertThat(audits("student.import.decision")).isEqualTo(3);

            JsonNode done = confirmAndRun(officerA, v3, "VALID_ROWS_ONLY");
            assertThat(done.path("progress").path("imported").asInt()).isEqualTo(2);
            assertThat(done.path("progress").path("updated").asInt()).isEqualTo(1);
            assertThat(ownerJdbc().sql("SELECT status FROM student WHERE id = ?").param(asha).query(String.class).single()).isEqualTo("ENROLLED");
            assertThat(ownerJdbc().sql("SELECT count(*) FROM student WHERE tenant_id = ? AND lower(first_name) = 'asha'").param(tenant).query(Long.class).single()).isEqualTo(1);
            assertThat(ownerJdbc().sql("SELECT count(*) FROM student WHERE tenant_id = ? AND lower(first_name) = 'ravi'").param(tenant).query(Long.class).single()).isEqualTo(2);
            assertThat(other).isNotNull();

            // a second import of the same file finds everything already present (duplicates across imports)
            JsonNode again = mapAndValidate(officerA, upload(officerA, collegeA, "dupes-again.csv", file));
            assertThat(again.path("previousJobId").asString()).isEqualTo(id);
            String id2 = again.path("id").asString();
            assertThat(row(officerA, id2, 2).path("matchCategory").asString()).isEqualTo("EXACT_DUPLICATE");
            assertThat(row(officerA, id2, 3).path("matchCategory").asString()).isEqualTo("EXACT_DUPLICATE");
            assertThat(row(officerA, id2, 7).path("matchCategory").asString()).isEqualTo("EXACT_DUPLICATE");
        }

        @Test
        void matchingRulesAndDateOfBirthPolicyAreConfigurablePerInstitution() throws Exception {
            id(send(admin, post("/api/v1/students"), "{\"institutionId\":\"" + collegeA + "\",\"studentNo\":\"AIT-7101\",\"firstName\":\"Asha\",\"lastName\":\"Rao\","
                    + "\"personal\":{\"dateOfBirth\":\"2006-04-12\"}}"));
            byte[] file = "Student ID,First name,Last name,Date of birth\nAIT-7102,Asha,Rao,2006-04-12\nAIT-7103,Kiran,Nair,\n".getBytes(StandardCharsets.UTF_8);
            assertThat(row(admin, mapAndValidate(admin, upload(admin, collegeA, "r1.csv", file)).path("id").asString(), 2).path("matchCategory").asString())
                    .isEqualTo("POTENTIAL_DUPLICATE");
            send(admin, put("/api/v1/student-imports/settings/" + collegeA), "{\"matchPersonalEmail\":true,\"matchInstitutionalEmail\":true,\"matchNameDob\":false,"
                    + "\"matchNameMobile\":true,\"requireDateOfBirth\":true}").andExpect(status().isOk());
            JsonNode j = mapAndValidate(admin, upload(admin, collegeA, "r2.csv", file));
            assertThat(row(admin, j.path("id").asString(), 2).path("status").asString()).isEqualTo("VALID");
            assertThat(row(admin, j.path("id").asString(), 3).path("issues").get(0).path("message").asString()).contains("required by the institution's import policy");
            assertThat(audits("student.import.settings_updated")).isEqualTo(1);
        }
    }

    // ================================================================================================ 12–14: background processing, retries, concurrency

    @Nested
    class ProcessingIdempotencyAndRecovery {

        @Test
        void importKeysIdentifyOneSourceFile() throws Exception {
            byte[] a = csv(line("AIT-8001", "Asha", "Rao", null, null, null, null, null));
            byte[] b = csv(line("AIT-8002", "Ravi", "Kumar", null, null, null, null, null));
            String first = body(uploadRaw(admin, collegeA, "a.csv", a, "upload-key-0001").andExpect(status().isCreated())).path("id").asString();
            assertThat(body(uploadRaw(admin, collegeA, "a.csv", a, "upload-key-0001").andExpect(status().isCreated())).path("id").asString()).isEqualTo(first);
            uploadRaw(admin, collegeA, "b.csv", b, "upload-key-0001").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("import_key_reused"));
            assertThat(audits("student.import.key_reused")).isEqualTo(1);
        }

        @Test
        void repeatedExecutionAndCrashedWorkersNeverDuplicateStudents() throws Exception {
            JsonNode j = mapAndValidate(admin, upload(admin, collegeA, "repeat.csv", csv(
                    line("AIT-8101", "Asha", "Rao", null, null, "ADM-8101", "CSE-B", null),
                    line("AIT-8102", "Ravi", "Kumar", null, null, "ADM-8102", "CSE-B", null),
                    line("AIT-8103", "Meera", "Iyer", null, null, "ADM-8103", "CSE-B", null))));
            String id = j.path("id").asString();
            JsonNode done = confirmAndRun(admin, j, "VALID_ROWS_ONLY");
            assertThat(done.path("status").asString()).isEqualTo("COMPLETED");
            assertThat(students()).isEqualTo(3);
            // duplicate delivery: the job is queued again and re-run
            ownerJdbc().sql("UPDATE student_import_job SET status = 'QUEUED' WHERE id = ?").param(UUID.fromString(id)).update();
            worker.runPending(tenant);
            assertThat(students()).isEqualTo(3);
            // a worker that died mid-job (lease expired) with a row whose outcome was lost: resuming never creates a second student
            ownerJdbc().sql("UPDATE student_import_row SET outcome = 'PENDING' WHERE job_id = ? AND row_no = 3").param(UUID.fromString(id)).update();
            ownerJdbc().sql("UPDATE student_import_job SET status = 'PROCESSING', lease_owner = 'dead-worker', lease_until = now() - interval '1 minute' WHERE id = ?")
                    .param(UUID.fromString(id)).update();
            worker.runPending(tenant);
            assertThat(students()).isEqualTo(3);
            JsonNode r3 = row(admin, id, 3);
            assertThat(r3.path("outcome").asString()).isEqualTo("FAILED");
            assertThat(r3.path("outcomeCode").asString()).isEqualTo("changed_since_validation");
            assertThat(job(admin, id).path("attempts").asInt()).isGreaterThan(1);
        }

        @Test
        void concurrentImportsOfTheSameStudentsCreateEachStudentOnce() throws Exception {
            List<String> lines = new ArrayList<>();
            for (int i = 0; i < 30; i++) lines.add(line("AIT-82" + String.format("%02d", i), "Student" + (char) ('A' + i % 26), "Same" + (char) ('A' + i / 26), null, null, null, null, null));
            byte[] file = csv(lines.toArray(String[]::new));
            JsonNode a = mapAndValidate(admin, upload(admin, collegeA, "a.csv", file));
            JsonNode b = mapAndValidate(collegeAdminA, upload(collegeAdminA, collegeA, "b.csv", file));
            confirmRaw(admin, a, "VALID_ROWS_ONLY").andExpect(status().isOk());
            confirmRaw(collegeAdminA, b, "VALID_ROWS_ONLY").andExpect(status().isOk());
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                List<Callable<Boolean>> runs = List.of(() -> worker.run(tenant, UUID.fromString(a.path("id").asString())),
                        () -> worker.run(tenant, UUID.fromString(b.path("id").asString())));
                for (Future<Boolean> f : pool.invokeAll(runs)) f.get(120, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }
            assertThat(students()).isEqualTo(30);
            int imported = job(admin, a.path("id").asString()).path("progress").path("imported").asInt()
                    + job(admin, b.path("id").asString()).path("progress").path("imported").asInt();
            int failed = job(admin, a.path("id").asString()).path("progress").path("failed").asInt()
                    + job(admin, b.path("id").asString()).path("progress").path("failed").asInt();
            assertThat(imported).isEqualTo(30);
            assertThat(failed).isEqualTo(30);
        }

        @Test
        void systemFailuresCanBeRetriedAndCancellationIsSafe() throws Exception {
            Fixture.Caller temp = account("INSTITUTION_ADMIN", collegeA);
            JsonNode j = upload(temp, collegeA, "retry.csv", csv(line("AIT-8301", "Asha", "Rao", null, null, null, null, null)));
            String id = j.path("id").asString();
            send(temp, put("/api/v1/student-imports/" + id + "/mapping"), "{\"columns\":[{\"column\":0,\"field\":\"student_no\"},{\"column\":1,\"field\":\"first_name\"},"
                    + "{\"column\":2,\"field\":\"last_name\"}],\"version\":" + j.path("version").asLong() + "}").andExpect(status().isOk());
            // the requesting user loses their account before the background step runs
            ownerJdbc().sql("UPDATE user_account SET status = 'SUSPENDED' WHERE external_subject = ?").param(temp.subject()).update();
            worker.runPending(tenant);
            JsonNode failed = job(admin, id);
            assertThat(failed.path("status").asString()).isEqualTo("FAILED");
            assertThat(failed.path("failureKind").asString()).isEqualTo("SYSTEM");
            assertThat(failed.path("can").path("retry").asBoolean()).isTrue();
            as(admin, post("/api/v1/student-imports/" + id + "/retry")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("QUEUED"));
            worker.runPending(tenant);
            JsonNode ready = job(admin, id);
            assertThat(ready.path("status").asString()).isEqualTo("READY");
            assertThat(audits("student.import.retried")).isEqualTo(1);
            // cancelling before confirmation and during processing
            as(admin, post("/api/v1/student-imports/" + id + "/cancel")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
            JsonNode k = mapAndValidate(admin, upload(admin, collegeA, "cancel.csv", csv(line("AIT-8302", "Ravi", "Kumar", null, null, null, null, null))));
            confirmRaw(admin, k, "VALID_ROWS_ONLY").andExpect(status().isOk());
            ownerJdbc().sql("UPDATE student_import_job SET cancel_requested = true WHERE id = ?").param(UUID.fromString(k.path("id").asString())).update();
            worker.runPending(tenant);
            JsonNode c = job(admin, k.path("id").asString());
            assertThat(c.path("status").asString()).isEqualTo("CANCELLED");
            assertThat(row(admin, k.path("id").asString(), 2).path("outcome").asString()).isEqualTo("NOT_PROCESSED");
            assertThat(students()).isZero();
        }

        @Test
        void aThousandRowsRunInTheBackgroundWithAccurateProgress() throws Exception {
            List<String> lines = new ArrayList<>();
            for (int i = 0; i < 1000; i++) {
                String batch = i % 2 == 0 ? "CSE-B" : "ME-A";
                lines.add(line(String.format("AIT-9%04d", i), "Anu" + letters(i).toLowerCase(), "Rao" + letters(i % 97).toLowerCase(), LocalDate.of(2005, 1, 1).plusDays(i).toString(),
                        "student" + i + "@example.test", String.format("ADM-9%04d", i), batch, i % 3 == 0 ? "Active" : null).replace("Female", i % 2 == 0 ? "Female" : "Male"));
            }
            long t0 = System.nanoTime();
            JsonNode j = mapAndValidate(admin, upload(admin, collegeA, "thousand.csv", csv(lines.toArray(String[]::new))));
            long validated = System.nanoTime();
            assertThat(j.path("counts").path("valid").asInt()).isEqualTo(1000);
            JsonNode done = confirmAndRun(admin, j, "VALID_ROWS_ONLY");
            long processed = System.nanoTime();
            assertThat(done.path("status").asString()).isEqualTo("COMPLETED");
            assertThat(done.path("progress").path("processed").asInt()).isEqualTo(1000);
            assertThat(done.path("progress").path("imported").asInt()).isEqualTo(1000);
            assertThat(students()).isEqualTo(1000);
            assertThat(ownerJdbc().sql("SELECT count(*) FROM enrolment WHERE tenant_id = ? AND is_current").param(tenant).query(Long.class).single()).isEqualTo(1000);
            System.out.printf("[import-1000] validation %d ms, processing %d ms%n", (validated - t0) / 1_000_000, (processed - validated) / 1_000_000);
        }
    }

    // ================================================================================================ 17, 19: isolation and privacy

    @Nested
    class IsolationAndPrivacy {

        @Test
        void otherTenantsAndOtherInstitutionsCannotSeeOrUseImports() throws Exception {
            JsonNode j = upload(collegeAdminA, collegeA, "private.csv", csv(line("AIT-9101", "Asha", "Rao", "2006-04-12", "asha@example.test", null, null, null)));
            String id = j.path("id").asString();
            as(BELA_ORG_ADMIN_B, get("/api/v1/student-imports/" + id)).andExpect(status().isNotFound());
            as(BELA_ORG_ADMIN_B, get("/api/v1/student-imports/" + id + "/rows")).andExpect(status().isNotFound());
            assertThat(body(as(BELA_ORG_ADMIN_B, get("/api/v1/student-imports"))).path("items").toString()).doesNotContain(id);
            as(collegeAdminB, get("/api/v1/student-imports/" + id)).andExpect(status().isForbidden());
            as(collegeAdminB, get("/api/v1/student-imports/" + id + "/reports/errors")).andExpect(status().isForbidden());
            as(collegeAdminB, get("/api/v1/student-imports/" + id + "/source")).andExpect(status().isForbidden());
            assertThat(body(as(collegeAdminB, get("/api/v1/student-imports"))).path("totalElements").asInt()).isZero();
            uploadRaw(collegeAdminB, collegeA, "x.csv", csv(line("AIT-9102", "Ravi", "Kumar", null, null, null, null, null)), null).andExpect(status().isForbidden());
            as(headCse, get("/api/v1/student-imports")).andExpect(status().isForbidden());
            assertThat(audits("student.import.access_denied")).isEqualTo(3);
            // the tenant's own administrator sees it and can download the scanned source file
            byte[] source = as(admin, get("/api/v1/student-imports/" + id + "/source")).andExpect(status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "no-store"))
                    .andReturn().getResponse().getContentAsByteArray();
            assertThat(new String(source, StandardCharsets.UTF_8)).contains("AIT-9101");
            assertThat(audits("student.import.source_downloaded")).isEqualTo(1);
            // storage keys are tenant-prefixed
            assertThat(ownerJdbc().sql("SELECT object_path FROM student_import_job WHERE id = ?").param(UUID.fromString(id)).query(String.class).single())
                    .startsWith("imports/students/" + id + "/");
        }

        @Test
        void retentionPurgesSourceFilesAndRawValuesButKeepsOutcomes() throws Exception {
            JsonNode j = mapAndValidate(admin, upload(admin, collegeA, "old.csv", csv(line("AIT-9201", "Asha", "Rao", "2006-04-12", "asha@example.test", null, null, null))));
            JsonNode done = confirmAndRun(admin, j, "VALID_ROWS_ONLY");
            String id = done.path("id").asString();
            ownerJdbc().sql("UPDATE student_import_job SET finished_at = now() - interval '40 days' WHERE id = ?").param(UUID.fromString(id)).update();
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(worker, "purgeScheduled");
            JsonNode purged = job(admin, id);
            assertThat(purged.path("sourceAvailable").asBoolean()).isFalse();
            assertThat(ownerJdbc().sql("SELECT count(*) FROM student_import_row WHERE job_id = ? AND cells_enc IS NOT NULL").param(UUID.fromString(id)).query(Long.class).single()).isZero();
            as(admin, get("/api/v1/student-imports/" + id + "/source")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("source_purged"));
            as(admin, get("/api/v1/student-imports/" + id + "/reports/corrections")).andExpect(status().isConflict());
            assertThat(report(admin, id, "result")).contains("2,IMPORTED,Asha Rao,AIT-9201");
            assertThat(audits("student.import.purged")).isEqualTo(1);
        }
    }
}
