package com.ivorovi.securityregression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ivorovi.proctoring.internal.ProctoringService;
import com.ivorovi.support.TestOrg;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * exams.CandidateAttempts.ownedBy (flagged facade, demo programme; reused by proctoring and coding), exercised through its
 * first consumer, the candidate proctoring endpoints: the candidate's own attempt resolves (with the right enrolment);
 * another candidate's attempt, an attempt of another portal and an attempt of another organization resolve to nothing
 * (404); the usual delivery refusals (suspension) still apply.
 */
class CandidateAttemptsIT extends DeliveryJourneySupport {

    String proctoring(String key, UUID attempt) {
        return "/api/v1/delivery/portals/" + key + "/attempts/" + attempt + "/proctoring";
    }

    void level(String level) {
        ownerJdbc().sql("INSERT INTO proctoring_setting (tenant_id, schedule_id, level, consent_version, updated_by) VALUES (?, ?, ?, ?, ?)")
                .params(org.tenant, schedule, level, ProctoringService.CONSENT_VERSION, controller.id()).update();
    }

    @Test
    void theOwnAttemptResolvesWithItsEnrolment() throws Exception {
        level("BASIC");
        UUID attempt = startAttempt(asha, codes.get(0));
        JsonNode view = delivery(get(proctoring(portalKey, attempt)), asha, 200);
        assertThat(view.path("level").asString()).isEqualTo("BASIC");
        delivery(post(proctoring(portalKey, attempt) + "/consent").content("""
                {"given":true,"consentHash":"%s"}""".formatted(view.path("consentHash").asString())), asha, 200);
        // the reference carried the candidate's enrolment in this schedule (recorded with the consent)
        UUID enrolment = ownerJdbc().sql("""
                SELECT e.id FROM exam_enrolment e JOIN exam_attempt a ON a.schedule_id = e.schedule_id AND a.candidate_id = e.candidate_id WHERE a.id = ?""")
                .param(attempt).query(UUID.class).single();
        assertThat(ownerJdbc().sql("SELECT enrolment_id FROM proctoring_consent WHERE attempt_id = ?").param(attempt).query(UUID.class).single()).isEqualTo(enrolment);
        JsonNode stored = delivery(post(proctoring(portalKey, attempt) + "/signals").content("""
                {"signals":[{"kind":"FOCUS_LOST"},{"kind":"PASTE"}]}"""), asha, 200);
        assertThat(stored.path("stored").asInt()).isEqualTo(2);
    }

    @Test
    void anotherCandidatesAttemptResolvesToNothing() throws Exception {
        level("BASIC");
        UUID ashas = startAttempt(asha, codes.get(0));
        startAttempt(bilal, codes.get(1));
        delivery(get(proctoring(portalKey, ashas)), bilal, 404);
        delivery(post(proctoring(portalKey, ashas) + "/signals").content("{\"signals\":[{\"kind\":\"COPY\"}]}"), bilal, 404);
        delivery(get(proctoring(portalKey, UUID.randomUUID())), asha, 404);              // no such attempt
        assertThat(ownerJdbc().sql("SELECT count(*) FROM proctoring_signal").query(Long.class).single()).isZero();
    }

    @Test
    void anAttemptOfAnotherPortalResolvesToNothing() throws Exception {
        level("BASIC");
        UUID attempt = startAttempt(asha, codes.get(0));
        String otherKey = "sr2-" + rnd();
        call(post("/api/v1/exam/portals").content("""
                {"portalKey":"%s","name":"Second portal","mfaPolicy":"OFF","selfRegistration":true}""".formatted(otherKey)), admin, status().isCreated());
        delivery(get("/api/v1/delivery/portals/" + otherKey + "/me"), asha, 200);
        delivery(get(proctoring(otherKey, attempt)), asha, 404);
        delivery(get(proctoring(portalKey, attempt)), asha, 200);                         // through its own portal it resolves
    }

    @Test
    void anAttemptOfAnotherOrganizationResolvesToNothing() throws Exception {
        level("BASIC");
        UUID attempt = startAttempt(asha, codes.get(0));
        TestOrg other = new TestOrg(ownerJdbc(), mvc, "ownedby-other");
        TestOrg.Account otherAdmin = other.account("ORG_ADMIN", null);
        String otherKey = "oth-" + rnd();
        call(post("/api/v1/exam/portals").content("""
                {"portalKey":"%s","name":"Other organization portal","mfaPolicy":"OFF","selfRegistration":true}""".formatted(otherKey)), otherAdmin,
                status().isCreated());
        delivery(get("/api/v1/delivery/portals/" + otherKey + "/me"), asha, 200);         // the same person, linked in the other organization
        delivery(get(proctoring(otherKey, attempt)), asha, 404);
    }

    @Test
    void deliveryRefusalsStillApply() throws Exception {
        level("BASIC");
        UUID attempt = startAttempt(asha, codes.get(0));
        UUID candidate = ownerJdbc().sql("SELECT candidate_id FROM exam_attempt WHERE id = ?").param(attempt).query(UUID.class).single();
        ownerJdbc().sql("UPDATE candidate_account SET status = 'SUSPENDED' WHERE id = ?").param(candidate).update();
        int code = as(get(proctoring(portalKey, attempt)), asha).andReturn().getResponse().getStatus();
        assertThat(code).as("a suspended candidate is refused, not treated as 'no attempt'").isEqualTo(403);
    }
}
