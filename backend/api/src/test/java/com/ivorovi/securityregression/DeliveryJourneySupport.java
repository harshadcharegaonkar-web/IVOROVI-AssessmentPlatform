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
 * Shared fixture of the QA delivery suites (D-7), built only through the real APIs: a published assessment of four
 * single-choice questions (correct answer A), an exam portal, a schedule open now for three hours, two enrolled candidates
 * of the candidate realm (Asha, Bilal) and three session codes; helpers for check-in, start and delivery calls.
 *
 * <p>Both APIs are served by one test context: {@link BothApis} disables the registration of the process-boundary filter
 * (production runs them as two processes, ADMIN and DELIVERY; the boundary itself is covered by {@code DeliveryIT}). The
 * security filter chains, portal-tenant binding, rate limits and row-level security are the production ones.
 */
@Import(DeliveryJourneySupport.BothApis.class)
abstract class DeliveryJourneySupport extends QuestionTestSupport {

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

    @Autowired protected AttemptService attempts;
    @Autowired protected ScoringService scoring;

    protected String portalKey;
    protected UUID schedule;
    protected final List<String> deliveryBodies = new ArrayList<>();

    protected record Candidate(String sub, String email, String token) {
        String bearer() {
            return "Bearer " + token;
        }
    }

    protected Candidate asha;
    protected Candidate bilal;
    protected List<String> codes;

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


    protected Candidate candidate(String name) {
        String sub = UUID.randomUUID().toString();
        String email = name + "-" + rnd() + "@cand.test";
        return new Candidate(sub, email, TestTokens.candidate(sub, email));
    }

    /** Links the account (first visit), checks in with a code and starts the attempt; returns its id. */
    protected UUID startAttempt(Candidate c, String code) throws Exception {
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
    protected void expireByRecreating(UUID attempt) {
        ownerJdbc().sql("ALTER TABLE exam_attempt DISABLE TRIGGER USER").update();
        try {
            ownerJdbc().sql("UPDATE exam_attempt SET started_at = now() - interval '2 hours', deadline_at = now() - interval '1 minute' WHERE id = ?").param(attempt).update();
        } finally {
            ownerJdbc().sql("ALTER TABLE exam_attempt ENABLE TRIGGER USER").update();
        }
    }

    protected String portal(String path) {
        return "/api/v1/delivery/portals/" + portalKey + path;
    }

    protected ResultActions as(MockHttpServletRequestBuilder r, Candidate c) throws Exception {
        return mvc.perform(r.header(HttpHeaders.AUTHORIZATION, c.bearer()).contentType(MediaType.APPLICATION_JSON));
    }

    /** A delivery call that must answer {@code expected}; its body is kept for the answer-information checks. */
    protected JsonNode delivery(MockHttpServletRequestBuilder r, Candidate c, int expected) throws Exception {
        String body = as(r, c).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        deliveryBodies.add(body);
        return body.isEmpty() ? null : JSON.readTree(body);
    }
}
