package com.ivorovi.securityregression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ivorovi.support.TestOrg;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import tools.jackson.databind.JsonNode;

/**
 * Security regression for the result workflow (M4, D-10; QA, D-7), over HTTP: a candidate sees a result only once it is
 * published, and only their own; publication needs a review and an independent approval (the reviewer can never approve);
 * who may read, review and approve; isolation between institutions and organizations; no answer information in any result
 * response. Fixture: {@link DeliveryJourneySupport} (four single-choice questions, correct answer A).
 */
@Tag("security-regression")
class ResultWorkflowSecurityIT extends DeliveryJourneySupport {

    private static final String RESULTS = "/api/v1/exam/results/";

    /** Asha answers two questions correctly and submits; scoring runs. Returns her attempt id. */
    private UUID submittedAndScored() throws Exception {
        UUID attempt = startAttempt(asha, codes.get(0));
        delivery(put(portal("/attempts/" + attempt + "/answers/1")).content("{\"seq\":1,\"response\":{\"value\":\"A\"}}"), asha, 200);
        delivery(put(portal("/attempts/" + attempt + "/answers/2")).content("{\"seq\":1,\"response\":{\"value\":\"A\"}}"), asha, 200);
        delivery(post(portal("/attempts/" + attempt + "/submit")).content("{}"), asha, 200);
        assertThat(scoring.scoreDue()).isGreaterThanOrEqualTo(1);
        return attempt;
    }

    private UUID requestPublication(TestOrg.Account reviewer) throws Exception {
        JsonNode r = call(post(RESULTS + schedule + "/reviews").content("{\"comment\":\"Checked against the answer sheet\"}"), reviewer, status().isOk());
        return UUID.fromString(r.path("reviews").get(0).path("approvalId").asString());
    }

    private org.springframework.test.web.servlet.ResultActions decide(TestOrg.Account who, UUID approval, boolean approve) throws Exception {
        return perform(post("/api/v1/approvals/" + approval + "/decision").content("{\"approve\":" + approve + ",\"reason\":\"QA\"}"), who);
    }

    @Test
    void aCandidateSeesOnlyTheirOwnResultAndOnlyOnceItIsPublished() throws Exception {
        UUID attempt = submittedAndScored();
        UUID bilals = startAttempt(bilal, codes.get(1));
        as(get(portal("/attempts/" + attempt + "/result")), asha).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("result_not_published"));
        UUID approval = requestPublication(controller);
        as(get(portal("/attempts/" + attempt + "/result")), asha).andExpect(status().isConflict());          // reviewed is not published
        decide(controller2, approval, true).andExpect(status().isOk());

        JsonNode mine = delivery(get(portal("/attempts/" + attempt + "/result")), asha, 200);
        assertThat(mine.path("awarded").decimalValue()).isEqualByComparingTo("4");                          // 2 × 2 marks
        assertThat(mine.path("correct").asInt()).isEqualTo(2);
        // never someone else's: Bilal through Asha's attempt, Asha on another organization's portal
        as(get(portal("/attempts/" + attempt + "/result")), bilal).andExpect(status().isNotFound());
        as(get(portal("/attempts/" + UUID.randomUUID() + "/result")), bilal).andExpect(status().isNotFound());
        TestOrg other = new TestOrg(ownerJdbc(), mvc, "secreg-res");
        String otherKey = "sr2-" + rnd();
        ownerJdbc().sql("INSERT INTO exam_portal (id, tenant_id, portal_key, name, mfa_policy, self_registration, created_by) VALUES (?, ?, ?, 'Other', 'OFF', true, ?)")
                .params(UUID.randomUUID(), other.tenant, otherKey, UUID.randomUUID()).update();
        mvc.perform(get("/api/v1/delivery/portals/" + otherKey + "/me").header(HttpHeaders.AUTHORIZATION, asha.bearer())).andExpect(status().isOk());
        mvc.perform(get("/api/v1/delivery/portals/" + otherKey + "/attempts/" + attempt + "/result").header(HttpHeaders.AUTHORIZATION, asha.bearer()))
                .andExpect(status().isNotFound());
        // Bilal's attempt was still open at the review: not part of the publication
        as(get(portal("/attempts/" + bilals + "/result")), bilal).andExpect(status().isConflict());
        // no answer information, per-question outcome or other candidate in the candidate's result
        String text = String.join("\n", deliveryBodies);
        for (String secret : java.util.List.of("Because A is right", "answerKey", "\"explanation\"", "\"correct\":\"")) assertThat(text).doesNotContain(secret);
        assertThat(mine.toString()).doesNotContain(bilal.email()).doesNotContain(bilals.toString());
        // a published score never changes
        assertThatThrownBy(() -> ownerJdbc().sql("UPDATE exam_score SET awarded = 8 WHERE attempt_id = ?").param(attempt).update()).isInstanceOf(Exception.class);
    }

    @Test
    void theReviewerCanNeverApproveAndOnlyHoldersOfTheApprovalPermissionCan() throws Exception {
        UUID attempt = submittedAndScored();
        UUID approval = requestPublication(controller);
        decide(controller, approval, true).andExpect(r -> assertThat(r.getResponse().getStatus()).isIn(403, 409));   // reviewer: four-eyes
        decide(admin, approval, true).andExpect(status().isForbidden());                                              // review, not approve
        decide(faculty, approval, true).andExpect(status().isForbidden());
        decide(otherController, approval, true).andExpect(r -> assertThat(r.getResponse().getStatus()).isIn(403, 404));   // another college
        as(get(portal("/attempts/" + attempt + "/result")), asha).andExpect(status().isConflict());
        assertThat(ownerJdbc().sql("SELECT status FROM exam_score WHERE attempt_id = ?").param(attempt).query(String.class).single()).isEqualTo("PROVISIONAL");
        // a rejection publishes nothing
        decide(controller2, approval, false).andExpect(status().isOk());
        as(get(portal("/attempts/" + attempt + "/result")), asha).andExpect(status().isConflict());
        assertThat(ownerJdbc().sql("SELECT status FROM exam_score WHERE attempt_id = ?").param(attempt).query(String.class).single()).isEqualTo("PROVISIONAL");
        // an administrator may review (request publication) but the approval still needs an exam controller other than the reviewer
        UUID second = requestPublication(admin);
        decide(admin, second, true).andExpect(r -> assertThat(r.getResponse().getStatus()).isIn(403, 409));
        decide(controller3, second, true).andExpect(status().isOk());
        delivery(get(portal("/attempts/" + attempt + "/result")), asha, 200);
        assertThat(audits("exam.results.published")).isEqualTo(1);
    }

    @Test
    void resultsAreReadOnlyByAuthorizedStaffOfTheInstitutionAndCarryNoAnswerInformation() throws Exception {
        submittedAndScored();
        JsonNode results = call(get(RESULTS + schedule), controller, status().isOk());
        assertThat(results.path("rows").size()).isGreaterThanOrEqualTo(1);
        String text = results.toString();
        for (String secret : java.util.List.of("Because A is right", "answerKey", "\"explanation\"")) assertThat(text).doesNotContain(secret);
        for (TestOrg.Account who : java.util.List.of(faculty, designer, student)) {
            perform(get(RESULTS + schedule), who).andExpect(status().isForbidden());
            perform(post(RESULTS + schedule + "/reviews").content("{}"), who).andExpect(status().isForbidden());
        }
        perform(get(RESULTS + schedule), otherController).andExpect(r -> assertThat(r.getResponse().getStatus()).isIn(403, 404));
        perform(post(RESULTS + schedule + "/reviews").content("{}"), otherController).andExpect(r -> assertThat(r.getResponse().getStatus()).isIn(403, 404));
        TestOrg other = new TestOrg(ownerJdbc(), mvc, "secreg-res2");
        for (TestOrg.Account outsider : java.util.List.of(other.account("EXAM_CONTROLLER", null), other.account("ORG_ADMIN", null))) {
            perform(get(RESULTS + schedule), outsider).andExpect(status().isNotFound());
            perform(post(RESULTS + schedule + "/reviews").content("{}"), outsider).andExpect(status().isNotFound());
        }
        // candidate tokens never reach the staff result API; staff tokens never the candidate result
        mvc.perform(get(RESULTS + schedule).header(HttpHeaders.AUTHORIZATION, asha.bearer())).andExpect(status().isUnauthorized());
        assertThat(audits("exam.results.reviewed")).isZero();
    }
}
