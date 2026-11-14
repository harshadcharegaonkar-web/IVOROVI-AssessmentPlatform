package com.ivorovi.securityregression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ivorovi.exams.internal.AttemptService;
import com.ivorovi.exams.internal.ScoringService;
import com.ivorovi.platform.process.ProcessBoundaryFilter;
import com.ivorovi.questions.QuestionTestSupport;
import com.ivorovi.support.TestOrg;
import com.ivorovi.support.TestTokens;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * Security regression for examination delivery (QA, D-7), end to end over HTTP: a real published assessment, a schedule,
 * an enrolment upload, session codes, candidates of the candidate realm, check-in, attempts, answers, submission and
 * scoring. Checks:
 * <ul>
 *   <li>no answer key, correct answer or explanation in any delivery response, in audit details, outbox payloads or the
 *       application's log output;</li>
 *   <li>another candidate's attempt is 404 (read, save, submit), in the same and in another organization;</li>
 *   <li>no saves after submission or after the deadline;</li>
 *   <li>schedules, enrolments, codes, attempts, answers and scores are isolated between organizations and institutions;</li>
 *   <li>who may call what: staff tokens never work on delivery, candidate tokens never on the staff API, staff without the
 *       permission get 403.</li>
 * </ul>
 *
 * <p>Both APIs are served by one test context: {@link BothApis} disables the registration of the process-boundary filter
 * (production runs them as two processes, ADMIN and DELIVERY). The boundary itself is covered by {@code DeliveryIT}. The
 * security filter chains, portal-tenant binding, rate limits and row-level security are the production ones.
 */
@Tag("security-regression")
@ExtendWith(OutputCaptureExtension.class)
@Import(DeliverySecurityRegressionIT.BothApis.class)
class DeliverySecurityRegressionIT extends QuestionTestSupport {

    @TestConfiguration
    static class BothApis {
        @Bean
        static BeanPostProcessor noProcessBoundary() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (bean instanceof FilterRegistrationBean<?> r && r.getFilter() instanceof ProcessBoundaryFilter) r.setEnabled(false);
                    return bean;
                }
            };
        }
    }

    /** Text that must never reach a candidate, an audit record, an event or a log line. */
    private static final List<String> SECRETS = List.of("Because A is right", "answerKey", "answer_key", "\"correct\"", "\"explanation\"");

    @Autowired AttemptService attempts;
    @Autowired ScoringService scoring;

    private String portalKey;
    private UUID schedule;
    private final List<String> deliveryBodies = new ArrayList<>();

    record Candidate(String sub, String email, String token) {
        String bearer() {
            return "Bearer " + token;
        }
    }

    private Candidate asha;
    private Candidate bilal;
    private List<String> codes;

    @BeforeEach
    void setUp() throws Exception {
        setUpOrg("secreg");
        UUID bank = standardBank();
        for (int i = 0; i < 4; i++) approved(bank, faculty, designer, controller, mcq("Security regression question " + i + " " + rnd(), "A"));
        List<UUID> qv = ownerJdbc().sql("SELECT approved_version_id FROM question WHERE bank_id = ? ORDER BY created_at").param(bank).query(UUID.class).list();
        String a = call(post("/api/v1/assessments").content("""
                {"orgUnitId":"%s","code":"SR-%s","purpose":"INTERNAL","title":"Security regression","durationMinutes":45}""".formatted(college, rnd())),
                designer, status().isCreated()).path("id").asString();
        long lock = ownerJdbc().sql("SELECT v.version FROM assessment x JOIN assessment_version v ON v.id = x.current_version_id WHERE x.id = ?::uuid").param(a)
                .query(Long.class).single();
        call(put("/api/v1/assessments/" + a + "/draft").content("""
                {"title":"Security regression","durationMinutes":45,"items":[{"questionVersionId":"%s"},{"questionVersionId":"%s"},{"questionVersionId":"%s"},
                 {"questionVersionId":"%s"}],"version":%d}""".formatted(qv.get(0), qv.get(1), qv.get(2), qv.get(3), lock)), designer, status().isOk());
        call(post("/api/v1/assessments/" + a + "/submit").content("{}"), designer, status().isOk());
        call(post("/api/v1/assessments/" + a + "/reviews").content("{\"decision\":\"REVIEWED\"}"), controller, status().isOk());
        call(post("/api/v1/assessments/" + a + "/publish").content("{}"), controller, status().isOk());
        UUID version = ownerJdbc().sql("SELECT published_version_id FROM assessment WHERE id = ?::uuid").param(a).query(UUID.class).single();

        portalKey = "sr-" + rnd();
        UUID portal = UUID.fromString(call(post("/api/v1/exam/portals").content("""
                {"portalKey":"%s","name":"Security regression portal","mfaPolicy":"OFF","selfRegistration":true}""".formatted(portalKey)),
                admin, status().isCreated()).path("id").asString());
        schedule = UUID.fromString(call(post("/api/v1/exam/schedules").content("""
                {"versionId":"%s","portalId":"%s","orgUnitId":"%s","opensAt":"%s","closesAt":"%s"}""".formatted(version, portal, college,
                Instant.now().minusSeconds(60), Instant.now().plus(Duration.ofHours(3)))), controller, status().isCreated()).path("id").asString());
        asha = candidate("asha");
        bilal = candidate("bilal");
        call(post("/api/v1/exam/schedules/" + schedule + "/enrolments/upload").content(JSON.writeValueAsString(Map.of(
                "csv", asha.email() + ",Asha\n" + bilal.email() + ",Bilal\n", "apply", true))), controller, status().isOk());
        codes = call(post("/api/v1/exam/schedules/" + schedule + "/codes").content("{\"count\":3}"), controller, status().isCreated()).findValuesAsString("code");
        deliveryBodies.clear();
    }

    // ================================================================================================ answer information

    @Test
    void noAnswerInformationInAnyDeliveryResponseAuditRecordEventOrLog(CapturedOutput output) throws Exception {
        UUID attempt = startAttempt(asha, codes.get(0));
        JsonNode view = delivery(get(portal("/attempts/" + attempt)), asha, 200);
        assertThat(view.path("items").size()).isEqualTo(4);
        for (int position = 1; position <= 4; position++) {
            delivery(put(portal("/attempts/" + attempt + "/answers/" + position)).content("{\"seq\":1,\"response\":{\"value\":\"" + (position % 2 == 0 ? "A" : "B") + "\"}}"),
                    asha, 200);
        }
        delivery(post(portal("/attempts/" + attempt + "/submit")).content("{\"submitKey\":\"s-1\"}"), asha, 200);
        delivery(get(portal("/attempts/" + attempt)), asha, 200);
        delivery(get(portal("/exams")), asha, 200);
        delivery(get(portal("/me")), asha, 200);
        assertThat(scoring.scoreDue()).isGreaterThanOrEqualTo(1);
        assertThat(ownerJdbc().sql("SELECT status FROM exam_score WHERE attempt_id = ?").param(attempt).query(String.class).single()).isEqualTo("PROVISIONAL");

        String audit = ownerJdbc().sql("SELECT coalesce(string_agg(details::text, ' '), '') FROM audit_event WHERE tenant_id = ?").param(org.tenant).query(String.class).single();
        String outbox = ownerJdbc().sql("SELECT coalesce(string_agg(payload::text, ' '), '') FROM outbox_event WHERE tenant_id = ?").param(org.tenant).query(String.class).single();
        assertThat(audit).contains("paperHash").contains("scoreHash");    // the attempt's paper and score audit records are part of what is checked
        for (String text : List.of(String.join("\n", deliveryBodies), audit, outbox, output.getAll())) {
            for (String secret : SECRETS) assertThat(text).as("must not contain %s", secret).doesNotContain(secret);
        }
        // the candidate's own answers come back, but never whether they were right or the marks awarded (the marking scheme,
        // marks and negativeMarks per question, is shown on purpose)
        assertThat(String.join("\n", deliveryBodies)).doesNotContain("awarded").doesNotContain("INCORRECT").doesNotContain("UNANSWERED");
    }

    // ================================================================================================ ownership

    @Test
    void anotherCandidatesAttemptIsNotFoundForReadSaveAndSubmit() throws Exception {
        UUID attempt = startAttempt(asha, codes.get(0));
        delivery(get(portal("/me")), bilal, 200);
        delivery(post(portal("/check-ins")).content("{\"code\":\"" + codes.get(1) + "\"}"), bilal, 200);
        for (ResultActions r : List.of(
                as(get(portal("/attempts/" + attempt)), bilal),
                as(put(portal("/attempts/" + attempt + "/answers/1")).content("{\"seq\":1,\"response\":{\"value\":\"A\"}}"), bilal),
                as(post(portal("/attempts/" + attempt + "/submit")).content("{}"), bilal))) {
            r.andExpect(status().isNotFound());
        }
        // an unknown id looks exactly the same
        as(get(portal("/attempts/" + UUID.randomUUID())), bilal).andExpect(status().isNotFound());
        // nothing was written for Asha's attempt
        assertThat(ownerJdbc().sql("SELECT count(*) FROM exam_answer WHERE attempt_id = ?").param(attempt).query(Long.class).single()).isZero();
        assertThat(ownerJdbc().sql("SELECT status FROM exam_attempt WHERE id = ?").param(attempt).query(String.class).single()).isEqualTo("IN_PROGRESS");

        // the same candidate identity on another organization's portal does not reach the attempt either
        TestOrg other = new TestOrg(ownerJdbc(), mvc, "secreg-other");
        String otherKey = "so-" + rnd();
        ownerJdbc().sql("INSERT INTO exam_portal (id, tenant_id, portal_key, name, mfa_policy, self_registration, created_by) VALUES (?, ?, ?, 'Other', 'OFF', true, ?)")
                .params(UUID.randomUUID(), other.tenant, otherKey, UUID.randomUUID()).update();
        mvc.perform(get("/api/v1/delivery/portals/" + otherKey + "/me").header(HttpHeaders.AUTHORIZATION, asha.bearer())).andExpect(status().isOk());
        mvc.perform(get("/api/v1/delivery/portals/" + otherKey + "/attempts/" + attempt).header(HttpHeaders.AUTHORIZATION, asha.bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/delivery/portals/" + otherKey + "/exams").header(HttpHeaders.AUTHORIZATION, asha.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(post("/api/v1/delivery/portals/" + otherKey + "/exams/" + schedule + "/attempt").header(HttpHeaders.AUTHORIZATION, asha.bearer()))
                .andExpect(status().isNotFound());
    }

    // ================================================================================================ no writes after the end

    @Test
    void noSavesAfterSubmissionAndTheSubmissionIsIdempotent() throws Exception {
        UUID attempt = startAttempt(asha, codes.get(0));
        delivery(put(portal("/attempts/" + attempt + "/answers/1")).content("{\"seq\":1,\"response\":{\"value\":\"B\"}}"), asha, 200);
        JsonNode first = delivery(post(portal("/attempts/" + attempt + "/submit")).content("{\"submitKey\":\"k-1\"}"), asha, 200);
        JsonNode replay = delivery(post(portal("/attempts/" + attempt + "/submit")).content("{\"submitKey\":\"k-1\"}"), asha, 200);
        JsonNode other = delivery(post(portal("/attempts/" + attempt + "/submit")).content("{}"), asha, 200);
        assertThat(replay).isEqualTo(first);
        assertThat(other.path("submittedAt")).isEqualTo(first.path("submittedAt"));
        as(put(portal("/attempts/" + attempt + "/answers/1")).content("{\"seq\":2,\"response\":{\"value\":\"A\"}}"), asha)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("attempt_submitted"));
        as(put(portal("/attempts/" + attempt + "/answers/2")).content("{\"seq\":1,\"response\":{\"value\":\"A\"}}"), asha)
                .andExpect(status().isConflict());
        assertThat(ownerJdbc().sql("SELECT response->>'value' FROM exam_answer WHERE attempt_id = ? AND position = 1").param(attempt).query(String.class).single())
                .isEqualTo("B");
        assertThat(ownerJdbc().sql("SELECT count(*) FROM exam_answer WHERE attempt_id = ?").param(attempt).query(Long.class).single()).isEqualTo(1);
        assertThat(audits("exam.attempt.submitted")).isEqualTo(1);
        // a new start after submission does not reopen it
        as(post(portal("/exams/" + schedule + "/attempt")), asha).andExpect(r -> assertThat(r.getResponse().getStatus()).isIn(200, 409));
        assertThat(ownerJdbc().sql("SELECT status FROM exam_attempt WHERE id = ?").param(attempt).query(String.class).single()).isEqualTo("SUBMITTED");
    }

    @Test
    void noSavesAfterTheDeadlineAndTimeUpSubmitsAutomatically() throws Exception {
        UUID attempt = startAttempt(asha, codes.get(0));
        delivery(put(portal("/attempts/" + attempt + "/answers/1")).content("{\"seq\":1,\"response\":{\"value\":\"B\"}}"), asha, 200);
        // the deadline cannot be moved, so simulate the passage of time by creating Bilal's attempt with a past deadline on the same schedule
        UUID late = startAttempt(bilal, codes.get(1));
        expireByRecreating(late);
        as(put(portal("/attempts/" + late + "/answers/1")).content("{\"seq\":1,\"response\":{\"value\":\"A\"}}"), bilal)
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isIn(409, 410))
                .andExpect(r -> assertThat(r.getResponse().getContentAsString()).doesNotContain("Because A is right"));
        assertThat(attempts.autoSubmitDue()).isGreaterThanOrEqualTo(1);
        assertThat(ownerJdbc().sql("SELECT status FROM exam_attempt WHERE id = ?").param(late).query(String.class).single()).isEqualTo("AUTO_SUBMITTED");
        as(put(portal("/attempts/" + late + "/answers/1")).content("{\"seq\":2,\"response\":{\"value\":\"A\"}}"), bilal).andExpect(status().isConflict());
        as(post(portal("/attempts/" + late + "/submit")).content("{}"), bilal)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("AUTO_SUBMITTED"));
        assertThat(ownerJdbc().sql("SELECT count(*) FROM exam_answer WHERE attempt_id = ?").param(late).query(Long.class).single()).isZero();
        // Asha's live attempt is untouched by the job
        assertThat(ownerJdbc().sql("SELECT status FROM exam_attempt WHERE id = ?").param(attempt).query(String.class).single()).isEqualTo("IN_PROGRESS");
    }

    // ================================================================================================ isolation

    @Test
    void schedulesEnrolmentsCodesAttemptsAnswersAndScoresAreIsolated() throws Exception {
        UUID attempt = startAttempt(asha, codes.get(0));
        delivery(put(portal("/attempts/" + attempt + "/answers/1")).content("{\"seq\":1,\"response\":{\"value\":\"A\"}}"), asha, 200);
        delivery(post(portal("/attempts/" + attempt + "/submit")).content("{}"), asha, 200);
        scoring.scoreDue();

        // another organization's exam controller and administrator
        TestOrg other = new TestOrg(ownerJdbc(), mvc, "secreg-iso");
        for (TestOrg.Account outsider : List.of(other.account("EXAM_CONTROLLER", null), other.account("ORG_ADMIN", null))) {
            perform(get("/api/v1/exam/schedules/" + schedule), outsider).andExpect(status().isNotFound());
            perform(get("/api/v1/exam/schedules/" + schedule + "/enrolments"), outsider).andExpect(status().isNotFound());
            perform(get("/api/v1/exam/schedules/" + schedule + "/codes"), outsider).andExpect(status().isNotFound());
            perform(post("/api/v1/exam/schedules/" + schedule + "/codes").content("{\"count\":1}"), outsider).andExpect(status().isNotFound());
            perform(post("/api/v1/exam/schedules/" + schedule + "/invitations"), outsider).andExpect(status().isNotFound());
            perform(post("/api/v1/exam/schedules/" + schedule + "/cancel").content("{\"reason\":\"x\"}"), outsider).andExpect(status().isNotFound());
            assertThat(call(get("/api/v1/exam/schedules"), outsider, status().isOk()).findValuesAsString("id")).doesNotContain(schedule.toString());
        }
        // another institution of the same organization
        perform(get("/api/v1/exam/schedules/" + schedule + "/enrolments"), otherController).andExpect(status().isNotFound());
        perform(get("/api/v1/exam/schedules/" + schedule + "/codes"), otherController).andExpect(status().isNotFound());
        perform(post("/api/v1/exam/schedules/" + schedule + "/enrolments/upload").content(JSON.writeValueAsString(Map.of("csv", "x@cand.test,X\n", "apply", true))),
                otherController).andExpect(status().isNotFound());

        // row-level security: no tenant context sees nothing, another organization's context sees nothing
        for (String table : List.of("exam_schedule", "exam_enrolment", "exam_attempt", "exam_answer", "exam_answer_journal", "exam_score", "exam_answer_score",
                "session_code", "check_in")) {
            assertThat(rawAppJdbc().sql("SELECT count(*) FROM " + table).query(Long.class).single()).as(table).isZero();
            assertThat(com.ivorovi.platform.tenancy.TenantContext.callInTenant(other.tenant, () -> appCount(table))).as(table).isZero();
            assertThat(com.ivorovi.platform.tenancy.TenantContext.callInTenant(org.tenant, () -> appCount(table))).as(table).isPositive();
        }
    }

    @Autowired org.springframework.jdbc.core.simple.JdbcClient jdbc;

    private long appCount(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    // ================================================================================================ who may call what

    @Test
    void tokensOfTheWrongRealmAndStaffWithoutPermissionAreRefused() throws Exception {
        UUID attempt = startAttempt(asha, codes.get(0));
        // a staff token on every delivery endpoint
        for (MockHttpServletRequestBuilder r : List.of(get(portal("/me")), get(portal("/exams")), post(portal("/exams/" + schedule + "/attempt")),
                get(portal("/attempts/" + attempt)), put(portal("/attempts/" + attempt + "/answers/1")).content("{\"seq\":1,\"response\":{\"value\":\"A\"}}"),
                post(portal("/attempts/" + attempt + "/submit")).content("{}"))) {
            mvc.perform(r.header(HttpHeaders.AUTHORIZATION, controller.bearer()).contentType(MediaType.APPLICATION_JSON)).andExpect(status().isUnauthorized());
        }
        // a candidate token on staff endpoints
        for (MockHttpServletRequestBuilder r : List.of(get("/api/v1/exam/schedules"), get("/api/v1/exam/schedules/" + schedule + "/enrolments"),
                get("/api/v1/assessments"), get("/api/v1/me"))) {
            mvc.perform(r.header(HttpHeaders.AUTHORIZATION, asha.bearer())).andExpect(status().isUnauthorized());
        }
        // no token at all
        mvc.perform(get(portal("/attempts/" + attempt))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/exam/schedules/" + schedule)).andExpect(status().isUnauthorized());
        // staff of the organization without the permission
        for (TestOrg.Account who : List.of(faculty, designer, student)) {
            perform(get("/api/v1/exam/schedules/" + schedule + "/enrolments"), who).andExpect(status().isForbidden());
            perform(post("/api/v1/exam/schedules/" + schedule + "/codes").content("{\"count\":1}"), who).andExpect(status().isForbidden());
            perform(post("/api/v1/exam/schedules/" + schedule + "/enrolments/upload").content(JSON.writeValueAsString(Map.of("csv", "x@cand.test,X\n", "apply", true))),
                    who).andExpect(status().isForbidden());
        }
        // nothing changed
        assertThat(ownerJdbc().sql("SELECT count(*) FROM exam_enrolment WHERE schedule_id = ?").param(schedule).query(Long.class).single()).isEqualTo(2);
        assertThat(ownerJdbc().sql("SELECT count(*) FROM exam_answer WHERE attempt_id = ?").param(attempt).query(Long.class).single()).isZero();
    }

    // ================================================================================================ helpers

    private Candidate candidate(String name) {
        String sub = UUID.randomUUID().toString();
        String email = name + "-" + rnd() + "@cand.test";
        return new Candidate(sub, email, TestTokens.candidate(sub, email));
    }

    /** Links the account (first visit), checks in with a code and starts the attempt; returns its id. */
    private UUID startAttempt(Candidate c, String code) throws Exception {
        delivery(get(portal("/me")), c, 200);
        delivery(post(portal("/check-ins")).content("{\"code\":\"" + code + "\"}"), c, 200);
        JsonNode exams = delivery(get(portal("/exams")), c, 200);
        assertThat(exams.findValuesAsString("scheduleId")).contains(schedule.toString());
        return UUID.fromString(delivery(post(portal("/exams/" + schedule + "/attempt")), c, 200).path("id").asString());
    }

    /**
     * The deadline of an attempt never changes (database guard trigger), so a time-up is simulated by moving a fresh
     * attempt's start and deadline into the past with the guard triggers briefly disabled — test setup on the owner
     * connection only; the application role can never do this.
     */
    private void expireByRecreating(UUID attempt) {
        ownerJdbc().sql("ALTER TABLE exam_attempt DISABLE TRIGGER USER").update();
        try {
            ownerJdbc().sql("UPDATE exam_attempt SET started_at = now() - interval '2 hours', deadline_at = now() - interval '1 minute' WHERE id = ?").param(attempt).update();
        } finally {
            ownerJdbc().sql("ALTER TABLE exam_attempt ENABLE TRIGGER USER").update();
        }
    }

    private String portal(String path) {
        return "/api/v1/delivery/portals/" + portalKey + path;
    }

    private ResultActions as(MockHttpServletRequestBuilder r, Candidate c) throws Exception {
        return mvc.perform(r.header(HttpHeaders.AUTHORIZATION, c.bearer()).contentType(MediaType.APPLICATION_JSON));
    }

    /** A delivery call that must answer {@code expected}; its body is kept for the answer-information checks. */
    private JsonNode delivery(MockHttpServletRequestBuilder r, Candidate c, int expected) throws Exception {
        String body = as(r, c).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        deliveryBodies.add(body);
        return body.isEmpty() ? null : JSON.readTree(body);
    }
}
