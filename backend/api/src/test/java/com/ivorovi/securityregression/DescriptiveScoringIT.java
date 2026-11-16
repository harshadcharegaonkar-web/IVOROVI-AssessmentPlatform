package com.ivorovi.securityregression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ivorovi.exams.ItemResults;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.platform.web.ApiException;
import com.ivorovi.support.TestOrg;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Descriptive questions end to end (M-D, contract C-1): authored with a model answer and a rubric, approved, published,
 * answered within the word limit, scored with the descriptive answer deferred to evaluators, completed through
 * {@link ItemResults}, and immutable afterwards. Model answer and rubric never reach a candidate; tenants stay isolated.
 */
@Tag("security-regression")
class DescriptiveScoringIT extends DeliveryJourneySupport {

    private static final String MODEL_ANSWER = "A stack is last-in first-out; a queue is first-in first-out.";

    @Autowired ItemResults itemResults;

    @Override
    protected List<String> questionDrafts(UUID bank) {
        List<String> drafts = new ArrayList<>();
        drafts.add(mcq("Descriptive suite objective " + rnd(), "A"));
        drafts.add("""
                {"type":"DESCRIPTIVE","content":%s,"body":{"maxWords":12},"answerKey":{"modelAnswer":"%s"},
                 "marking":{"rubric":[{"title":"Correctness","description":"Both orders named","weight":70},{"title":"Clarity","weight":30}]},
                 "marks":10,"negativeMarks":0}""".formatted(content("Compare a stack and a queue " + rnd()), MODEL_ANSWER));
        return drafts;
    }

    private int descriptivePosition(UUID attempt) throws Exception {
        JsonNode view = delivery(get(portal("/attempts/" + attempt)), asha, 200);
        for (JsonNode i : view.path("items")) if ("DESCRIPTIVE".equals(i.path("type").asString())) {
            assertThat(i.path("projection").path("maxWords").asInt()).isEqualTo(12);
            return i.path("position").asInt();
        }
        throw new AssertionError("no descriptive item");
    }

    private UUID tenant() {
        return org.tenant;
    }

    private <T> T inTenant(UUID t, java.util.concurrent.Callable<T> c) {
        return TenantContext.callInTenant(t, () -> {
            try {
                return c.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Test
    void aDescriptiveAnswerWaitsForEvaluatorsAndTheScoreIsWrittenOnceItsMarksAreRecorded() throws Exception {
        UUID attempt = startAttempt(asha, codes.get(0));
        int d = descriptivePosition(attempt);
        int objective = d == 1 ? 2 : 1;
        // the word limit is enforced on save; a non-text answer is refused
        as(put(portal("/attempts/" + attempt + "/answers/" + d)).content("{\"seq\":1,\"response\":{\"value\":\"" + "word ".repeat(13) + "\"}}"), asha)
                .andExpect(status().isBadRequest());
        as(put(portal("/attempts/" + attempt + "/answers/" + d)).content("{\"seq\":1,\"response\":{\"value\":[\"A\"]}}"), asha).andExpect(status().isBadRequest());
        delivery(put(portal("/attempts/" + attempt + "/answers/" + d)).content("{\"seq\":2,\"response\":{\"value\":\"Stack: last in first out. Queue: first in first out.\"}}"), asha, 200);
        delivery(put(portal("/attempts/" + attempt + "/answers/" + objective)).content("{\"seq\":1,\"response\":{\"value\":\"A\"}}"), asha, 200);
        delivery(post(portal("/attempts/" + attempt + "/submit")).content("{}"), asha, 200);

        scoring.scoreDue();
        assertThat(ownerJdbc().sql("SELECT count(*) FROM exam_score WHERE attempt_id = ?").param(attempt).query(Integer.class).single()).isZero();
        assertThat(ownerJdbc().sql("SELECT evaluation_positions[1] FROM exam_attempt_deferred WHERE attempt_id = ?").param(attempt).query(Integer.class).single()).isEqualTo(d);
        assertThat(ownerJdbc().sql("SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'exam.attempt.awaiting_marks'").param(attempt.toString())
                .query(Integer.class).single()).isEqualTo(1);
        scoring.scoreDue();                                                                                  // deferral is recorded once
        assertThat(audits("exam.attempt.deferred")).isEqualTo(1);
        JsonNode results = call(get("/api/v1/exam/results/" + schedule), controller, status().isOk());
        assertThat(results.path("awaitingMarks").asInt()).isEqualTo(1);
        assertThat(results.path("provisional").asInt()).isZero();
        assertThat(results.path("canReview").asBoolean()).isFalse();                                       // nothing to publish yet

        List<ItemResults.Item> deferred = inTenant(tenant(), () -> itemResults.deferred(attempt));
        assertThat(deferred).singleElement().satisfies(i -> {
            assertThat(i.position()).isEqualTo(d);
            assertThat(i.source()).isEqualTo(ItemResults.Source.EVALUATION);
            assertThat(i.marks()).isEqualByComparingTo("10");
            assertThat(i.awarded()).isNull();
        });
        // refused: wrong source, outside 0…marks, three decimals, an item that is not deferred
        for (Runnable bad : List.<Runnable>of(
                () -> itemResults.record(attempt, d, BigDecimal.ONE, ItemResults.Source.EXECUTION, null),
                () -> itemResults.record(attempt, d, new BigDecimal("10.5"), ItemResults.Source.EVALUATION, null),
                () -> itemResults.record(attempt, d, new BigDecimal("-1"), ItemResults.Source.EVALUATION, null),
                () -> itemResults.record(attempt, d, new BigDecimal("7.125"), ItemResults.Source.EVALUATION, null),
                () -> itemResults.record(attempt, objective, BigDecimal.ONE, ItemResults.Source.EVALUATION, null)))
            assertThatThrownBy(() -> inTenant(tenant(), () -> { bad.run(); return null; })).isInstanceOf(ApiException.class);
        // another organization sees nothing and can record nothing
        TestOrg other = new TestOrg(ownerJdbc(), mvc, "desc-other");
        assertThat(inTenant(other.tenant, () -> itemResults.deferred(attempt))).isEmpty();
        assertThatThrownBy(() -> inTenant(other.tenant, () -> itemResults.record(attempt, d, BigDecimal.ONE, ItemResults.Source.EVALUATION, null)))
                .isInstanceOf(ApiException.class);

        assertThat(inTenant(tenant(), () -> itemResults.record(attempt, d, new BigDecimal("7.5"), ItemResults.Source.EVALUATION, "rubric C1 6, C2 1.5"))).isTrue();
        assertThat(ownerJdbc().sql("SELECT awarded FROM exam_score WHERE attempt_id = ?").param(attempt).query(BigDecimal.class).single())
                .isEqualByComparingTo("9.5");                                                                // 2 (objective) + 7.5
        assertThat(ownerJdbc().sql("SELECT outcome || '/' || source FROM exam_answer_score WHERE attempt_id = ? AND position = ?").params(attempt, d)
                .query(String.class).single()).isEqualTo("PARTIAL/EVALUATION");
        assertThat(ownerJdbc().sql("SELECT source FROM exam_answer_score WHERE attempt_id = ? AND position = ?").params(attempt, objective)
                .query(String.class).single()).isEqualTo("AUTOMATIC");
        // idempotent for the same marks; different marks only through re-evaluation; records never change
        assertThat(inTenant(tenant(), () -> itemResults.record(attempt, d, new BigDecimal("7.50"), ItemResults.Source.EVALUATION, null))).isFalse();
        assertThatThrownBy(() -> inTenant(tenant(), () -> itemResults.record(attempt, d, new BigDecimal("8"), ItemResults.Source.EVALUATION, null)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> ownerJdbc().sql("UPDATE exam_item_mark SET awarded = 10 WHERE attempt_id = ?").param(attempt).update()).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> ownerJdbc().sql("DELETE FROM exam_attempt_deferred WHERE attempt_id = ?").param(attempt).update()).isInstanceOf(Exception.class);
        results = call(get("/api/v1/exam/results/" + schedule), controller, status().isOk());
        assertThat(results.path("awaitingMarks").asInt()).isZero();
        assertThat(results.path("provisional").asInt()).isEqualTo(1);
        assertThat(audits("exam.item.marked")).isEqualTo(1);

        // the model answer and the rubric never reached the candidate
        String text = String.join("\n", deliveryBodies);
        for (String secret : List.of(MODEL_ANSWER, "modelAnswer", "Correctness", "rubric", "Both orders named")) assertThat(text).doesNotContain(secret);
    }

    @Test
    void aBlankDescriptiveAnswerIsUnansweredAndNeedsNoEvaluation() throws Exception {
        UUID attempt = startAttempt(asha, codes.get(0));
        int d = descriptivePosition(attempt);
        delivery(put(portal("/attempts/" + attempt + "/answers/" + (d == 1 ? 2 : 1))).content("{\"seq\":1,\"response\":{\"value\":\"B\"}}"), asha, 200);
        delivery(put(portal("/attempts/" + attempt + "/answers/" + d)).content("{\"seq\":1,\"response\":{\"value\":\"   \"}}"), asha, 200);
        delivery(post(portal("/attempts/" + attempt + "/submit")).content("{}"), asha, 200);
        scoring.scoreDue();
        assertThat(ownerJdbc().sql("SELECT count(*) FROM exam_attempt_deferred WHERE attempt_id = ?").param(attempt).query(Integer.class).single()).isZero();
        assertThat(ownerJdbc().sql("SELECT unanswered FROM exam_score WHERE attempt_id = ?").param(attempt).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void theStaffMarkingPreviewRefusesDescriptiveQuestions() throws Exception {
        UUID q = ownerJdbc().sql("SELECT q.id FROM question q JOIN question_version v ON v.id = q.approved_version_id WHERE v.type_code = 'DESCRIPTIVE' AND q.tenant_id = ?")
                .param(tenant()).query(UUID.class).single();
        perform(post("/api/v1/questions/" + q + "/versions/1/marking-preview").content("{\"responses\":[\"x\"]}"), controller)
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isIn(403, 409));
    }
}
