package com.ivorovi.assessments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ivorovi.questions.QuestionTestSupport;
import com.ivorovi.support.TestOrg;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Increment 2.2 / M1: assessment authoring end to end through the API, with the database guards attacked directly.
 * Roles (P2-07 matrix): designer authors, controller reviews, controller2 publishes; faculty authors internal and practice only.
 */
class AssessmentLifecycleIT extends QuestionTestSupport {

    UUID bank;
    List<UUID> qs = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        setUpOrg("assess");
        bank = standardBank();
        for (int i = 0; i < 8; i++) qs.add(approved(bank, faculty, designer, controller, mcq("Assessment question " + i + " " + rnd(), "A")));
    }

    UUID approvedVersion(UUID q) {
        return ownerJdbc().sql("SELECT approved_version_id FROM question WHERE id = ?").param(q).query(UUID.class).single();
    }

    UUID createAssessment(TestOrg.Account by, String purpose) throws Exception {
        JsonNode d = call(post("/api/v1/assessments").content("""
                {"orgUnitId":"%s","code":"A-%s","purpose":"%s","title":"Mid-term %s","durationMinutes":90}""".formatted(college, rnd(), purpose, purpose)),
                by, status().isCreated());
        assertThat(d.path("current").path("state").asString()).isEqualTo("DRAFT");
        return UUID.fromString(d.path("id").asString());
    }

    long lock(UUID a) {
        return ownerJdbc().sql("SELECT v.version FROM assessment a JOIN assessment_version v ON v.id = a.current_version_id WHERE a.id = ?").param(a).query(Long.class).single();
    }

    /** Two fixed questions (q0, q1) and one rule drawing 2 from the bank (the other 6 approved questions). */
    String definition(UUID a, String extra) {
        return """
                {"title":"Mid-term","instructions":"Answer all questions.","durationMinutes":90,"negativeMarking":"QUESTION","shuffleQuestions":true,"shuffleOptions":true,
                 "items":[{"questionVersionId":"%s"},{"questionVersionId":"%s","marks":3}],
                 "rules":[{"label":"Any two","bankId":"%s","count":2,"marks":1}]%s,"version":%d}"""
                .formatted(approvedVersion(qs.get(0)), approvedVersion(qs.get(1)), bank, extra, lock(a));
    }

    JsonNode draft(UUID a, TestOrg.Account by, String body) throws Exception {
        return call(put("/api/v1/assessments/" + a + "/draft").content(body), by, status().isOk());
    }

    UUID versionOf(UUID a) {
        return ownerJdbc().sql("SELECT current_version_id FROM assessment WHERE id = ?").param(a).query(UUID.class).single();
    }

    /** Draft → review (controller) → publish (controller2): three different people. */
    UUID published(String purpose) throws Exception {
        UUID a = createAssessment(designer, purpose);
        draft(a, designer, definition(a, ""));
        call(post("/api/v1/assessments/" + a + "/submit").content("{}"), designer, status().isOk());
        call(post("/api/v1/assessments/" + a + "/reviews").content("{\"decision\":\"REVIEWED\"}"), controller, status().isOk());
        call(post("/api/v1/assessments/" + a + "/publish").content("{\"reason\":\"Approved for the mid-term\"}"), controller2, status().isOk());
        return a;
    }

    // ================================================================================================ lifecycle

    @Test
    void highStakesAssessmentNeedsThreeDifferentPeople() throws Exception {
        UUID a = createAssessment(designer, "SEMESTER");
        JsonNode d = draft(a, designer, definition(a, ""));
        assertThat(d.path("current").path("governance").asString()).isEqualTo("HIGH_STAKES");
        JsonNode val = call(get("/api/v1/assessments/" + a + "/validation"), designer, status().isOk());
        assertThat(val.path("errors").size()).isZero();
        assertThat(val.path("questionCount").asInt()).isEqualTo(4);
        assertThat(val.path("totalMarks").decimalValue()).isEqualByComparingTo("7");   // 2 (default) + 3 + 2×1
        // 6 candidates for a draw of 2: exactly 3× — no exposure warning
        assertThat(val.path("warnings").size()).isZero();

        call(post("/api/v1/assessments/" + a + "/submit").content("{}"), designer, status().isOk());
        // the author can neither review nor publish
        perform(post("/api/v1/assessments/" + a + "/reviews").content("{\"decision\":\"REVIEWED\"}"), designer)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("self_review"));
        perform(post("/api/v1/assessments/" + a + "/publish").content("{}"), designer).andExpect(status().isForbidden());
        // no publication before an independent review
        perform(post("/api/v1/assessments/" + a + "/publish").content("{}"), controller2)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("review_missing"));
        call(post("/api/v1/assessments/" + a + "/reviews").content("{\"decision\":\"REVIEWED\",\"comment\":\"Balanced\"}"), controller, status().isOk());
        // high-stakes: the reviewer may not also publish
        perform(post("/api/v1/assessments/" + a + "/publish").content("{}"), controller)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("publisher_reviewed"));
        assertThat(audits("assessment.publication_policy_denied")).isEqualTo(1);
        JsonNode pub = call(post("/api/v1/assessments/" + a + "/publish").content("{\"reason\":\"ok\"}"), controller2, status().isOk());
        assertThat(pub.path("current").path("state").asString()).isEqualTo("PUBLISHED");
        assertThat(pub.path("current").path("totalMarks").decimalValue()).isEqualByComparingTo("7");
        assertThat(pub.path("actions").path("edit").asBoolean()).isFalse();

        UUID vid = versionOf(a);
        // pools are frozen at publication: the 6 non-fixed approved questions; every used version is referenced
        assertThat(ownerJdbc().sql("SELECT count(*) FROM assessment_rule_pool p JOIN assessment_rule r ON r.id = p.rule_id WHERE r.version_id = ?")
                .param(vid).query(Long.class).single()).isEqualTo(6);
        assertThat(ownerJdbc().sql("SELECT count(*) FROM question_version_reference WHERE referrer_type = 'assessment_version' AND referrer_id = ?")
                .param(vid.toString()).query(Long.class).single()).isEqualTo(8);
        // history, transitions, audit and the outbox event
        JsonNode tr = call(get("/api/v1/assessments/" + a + "/transitions"), designer, status().isOk());
        assertThat(tr.findValuesAsString("toState")).containsExactly("DRAFT", "IN_REVIEW", "PUBLISHED");
        assertThat(audits("assessment.published")).isEqualTo(1);
        assertThat(audits("assessment.self_review_denied")).isEqualTo(1);
        assertThat(ownerJdbc().sql("SELECT count(*) FROM outbox_event WHERE tenant_id = ? AND event_type = 'assessment.published'").param(org.tenant)
                .query(Long.class).single()).isEqualTo(1);
        JsonNode history = call(get("/api/v1/assessments/" + a + "/history"), designer, status().isOk());
        assertThat(history.findValuesAsString("action")).contains("assessment.created", "assessment.submitted", "assessment.reviewed", "assessment.published");
    }

    @Test
    void standardGovernanceLetsTheReviewerPublishButNeverTheAuthor() throws Exception {
        UUID a = createAssessment(faculty, "INTERNAL");
        draft(a, faculty, definition(a, ""));
        call(post("/api/v1/assessments/" + a + "/submit").content("{}"), faculty, status().isOk());
        call(post("/api/v1/assessments/" + a + "/reviews").content("{\"decision\":\"REVIEWED\"}"), controller, status().isOk());
        JsonNode pub = call(post("/api/v1/assessments/" + a + "/publish").content("{}"), controller, status().isOk());
        assertThat(pub.path("current").path("governance").asString()).isEqualTo("STANDARD");
        assertThat(pub.path("current").path("state").asString()).isEqualTo("PUBLISHED");
    }

    @Test
    void facultyAuthorsInternalAndPracticeOnly() throws Exception {
        createAssessment(faculty, "PRACTICE");
        for (String p : List.of("SEMESTER", "ADMISSION", "PLACEMENT", "APTITUDE")) {
            perform(post("/api/v1/assessments").content("""
                    {"orgUnitId":"%s","code":"F-%s","purpose":"%s","title":"x"}""".formatted(college, rnd(), p)), faculty).andExpect(status().isForbidden());
        }
        // faculty can neither review nor publish; students have no access at all
        UUID a = createAssessment(designer, "INTERNAL");
        draft(a, designer, definition(a, ""));
        call(post("/api/v1/assessments/" + a + "/submit").content("{}"), designer, status().isOk());
        perform(post("/api/v1/assessments/" + a + "/reviews").content("{\"decision\":\"REVIEWED\"}"), faculty).andExpect(status().isForbidden());
        perform(post("/api/v1/assessments/" + a + "/publish").content("{}"), faculty).andExpect(status().isForbidden());
        perform(get("/api/v1/assessments/" + a), student).andExpect(status().isForbidden());
        // designers do not hold assessment.publish
        call(post("/api/v1/assessments/" + a + "/reviews").content("{\"decision\":\"REVIEWED\"}"), controller, status().isOk());
        perform(post("/api/v1/assessments/" + a + "/publish").content("{}"), designer2).andExpect(status().isForbidden());
    }

    @Test
    void editingAVersionInReviewReturnsItToDraftAndInvalidatesReviews() throws Exception {
        UUID a = createAssessment(designer, "SEMESTER");
        draft(a, designer, definition(a, ""));
        call(post("/api/v1/assessments/" + a + "/submit").content("{}"), designer, status().isOk());
        call(post("/api/v1/assessments/" + a + "/reviews").content("{\"decision\":\"REVIEWED\"}"), controller, status().isOk());
        JsonNode d = draft(a, designer, definition(a, "").replace("Answer all questions.", "Answer every question."));
        assertThat(d.path("current").path("state").asString()).isEqualTo("DRAFT");
        assertThat(d.path("current").path("reviews").get(0).path("valid").asBoolean()).isFalse();
        call(post("/api/v1/assessments/" + a + "/submit").content("{}"), designer, status().isOk());
        perform(post("/api/v1/assessments/" + a + "/publish").content("{}"), controller2)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("review_missing"));
        // changes requested need a reason and return the version to draft
        perform(post("/api/v1/assessments/" + a + "/reviews").content("{\"decision\":\"CHANGES_REQUESTED\"}"), controller).andExpect(status().isBadRequest());
        JsonNode back = call(post("/api/v1/assessments/" + a + "/reviews").content("{\"decision\":\"CHANGES_REQUESTED\",\"comment\":\"Add a numeric question\"}"),
                controller, status().isOk());
        assertThat(back.path("current").path("state").asString()).isEqualTo("DRAFT");
        // a stale optimistic lock is refused
        perform(put("/api/v1/assessments/" + a + "/draft").content(definition(a, "").replaceFirst("\"version\":\\d+", "\"version\":1")), designer)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("concurrent_modification"));
    }

    @Test
    void onlyApprovedQuestionsOfEligibleBanksCanBeUsed() throws Exception {
        UUID a = createAssessment(designer, "SEMESTER");
        UUID draftQ = create(bank, faculty, mcq("Not approved " + rnd(), "B"));
        UUID draftVersion = ownerJdbc().sql("SELECT current_version_id FROM question WHERE id = ?").param(draftQ).query(UUID.class).single();
        perform(put("/api/v1/assessments/" + a + "/draft").content("""
                {"title":"x","items":[{"questionVersionId":"%s"}],"version":%d}""".formatted(draftVersion, lock(a))), designer)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("question_not_approved"));
        // the database refuses it too, even for the owner role
        assertThatThrownBy(() -> ownerJdbc().sql("INSERT INTO assessment_item (id, tenant_id, version_id, ordinal, question_version_id, marks, negative_marks) VALUES (?, ?, ?, 99, ?, 1, 0)")
                .params(UUID.randomUUID(), org.tenant, versionOf(a), draftVersion).update()).isInstanceOf(Exception.class);
        // a bank of another institution: unreadable for the college designer (404, existence not revealed) and not eligible
        // even for an organization administrator who can read it
        UUID otherBank = bank(admin, otherCollege, "STANDARD", 1, false, "\"ACADEMIC\"");
        perform(put("/api/v1/assessments/" + a + "/draft").content("""
                {"title":"x","rules":[{"bankId":"%s","count":1,"marks":1}],"version":%d}""".formatted(otherBank, lock(a))), designer)
                .andExpect(status().isNotFound());
        UUID byAdmin = createAssessment(admin, "SEMESTER");
        perform(put("/api/v1/assessments/" + byAdmin + "/draft").content("""
                {"title":"x","rules":[{"bankId":"%s","count":1,"marks":1}],"version":%d}""".formatted(otherBank, lock(byAdmin))), admin)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("bank_not_eligible"));
        // admission assessments use admission banks only
        UUID adm = createAssessment(designer, "ADMISSION");
        perform(put("/api/v1/assessments/" + adm + "/draft").content("""
                {"title":"x","rules":[{"bankId":"%s","count":1,"marks":1}],"version":%d}""".formatted(bank, lock(adm))), designer)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("bank_not_eligible"));
    }

    @Test
    void poolSizeRulesFollowQ225() throws Exception {
        UUID a = createAssessment(designer, "SEMESTER");
        // a draw of 3 from 6 candidates: below 3× → warning for SEMESTER
        draft(a, designer, definition(a, "").replace("\"count\":2", "\"count\":3"));
        JsonNode val = call(get("/api/v1/assessments/" + a + "/validation"), designer, status().isOk());
        assertThat(val.findValuesAsString("code")).contains("pool_low_exposure");
        assertThat(val.path("errors").size()).isZero();
        // a draw larger than the pool blocks submission
        draft(a, designer, definition(a, "").replace("\"count\":2", "\"count\":7"));
        perform(post("/api/v1/assessments/" + a + "/submit").content("{}"), designer)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("validation_failed"));
        // two rules over the same pool are refused (pools must be disjoint)
        draft(a, designer, definition(a, "").replace("\"marks\":1}]", "\"marks\":1},{\"bankId\":\"" + bank + "\",\"count\":1,\"marks\":1}]"));
        assertThat(call(get("/api/v1/assessments/" + a + "/validation"), designer, status().isOk()).path("errors").findValuesAsString("code")).contains("rules_overlap");
    }

    // ================================================================================================ immutability

    @Test
    void publishedVersionsNeverChangeEvenWithDirectDatabaseAccess() throws Exception {
        UUID a = published("SEMESTER");
        UUID vid = versionOf(a);
        perform(put("/api/v1/assessments/" + a + "/draft").content(definition(a, "")), designer)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("not_editable"));
        assertThatThrownBy(() -> ownerJdbc().sql("UPDATE assessment_version SET title = 'tampered' WHERE id = ?").param(vid).update()).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> ownerJdbc().sql("UPDATE assessment_item SET marks = 100 WHERE version_id = ?").param(vid).update()).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> ownerJdbc().sql("DELETE FROM assessment_item WHERE version_id = ?").param(vid).update()).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> ownerJdbc().sql("UPDATE assessment_rule SET draw_count = 1 WHERE version_id = ?").param(vid).update()).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> ownerJdbc().sql("DELETE FROM assessment_rule_pool WHERE rule_id IN (SELECT id FROM assessment_rule WHERE version_id = ?)").param(vid).update())
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> ownerJdbc().sql("DELETE FROM assessment_version WHERE id = ?").param(vid).update()).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> ownerJdbc().sql("DELETE FROM assessment WHERE id = ?").param(a).update()).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> ownerJdbc().sql("UPDATE assessment SET purpose = 'PRACTICE' WHERE id = ?").param(a).update()).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> ownerJdbc().sql("DELETE FROM assessment_transition WHERE version_id = ?").param(vid).update()).isInstanceOf(Exception.class);
        // the publication policy is enforced by the database: a version cannot be published by its author or without a review
        UUID b = createAssessment(designer, "INTERNAL");
        draft(b, designer, definition(b, ""));
        call(post("/api/v1/assessments/" + b + "/submit").content("{}"), designer, status().isOk());
        assertThatThrownBy(() -> ownerJdbc().sql("UPDATE assessment_version SET state = 'PUBLISHED', published_by = author_id, published_at = now() WHERE id = ?")
                .param(versionOf(b)).update()).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> ownerJdbc().sql("UPDATE assessment_version SET state = 'PUBLISHED', published_by = ?, published_at = now() WHERE id = ?")
                .params(controller2.id(), versionOf(b)).update()).isInstanceOf(Exception.class);
    }

    @Test
    void retiredQuestionsNeverChangeAPublishedAssessment() throws Exception {
        UUID a = published("SEMESTER");
        UUID vid = versionOf(a);
        UUID q0v = approvedVersion(qs.get(0));
        perform(post("/api/v1/questions/" + qs.get(0) + "/retire").content("{\"reason\":\"Syllabus changed\"}"), controller).andExpect(status().isOk());
        JsonNode d = call(get("/api/v1/assessments/" + a), designer, status().isOk());
        assertThat(d.path("current").path("state").asString()).isEqualTo("PUBLISHED");
        assertThat(d.path("current").path("items").get(0).path("questionVersionId").asString()).isEqualTo(q0v.toString());
        assertThat(d.path("warnings").get(0).asString()).contains("retired or replaced after publication");
        // a new version drops the retired question and records why
        JsonNode nv = call(post("/api/v1/assessments/" + a + "/versions"), designer, status().isCreated());
        assertThat(nv.path("current").path("versionNo").asInt()).isEqualTo(2);
        assertThat(nv.path("current").path("items").size()).isEqualTo(1);
        assertThat(nv.path("published").path("id").asString()).isEqualTo(vid.toString());
        JsonNode tr = call(get("/api/v1/assessments/" + a + "/transitions"), designer, status().isOk());
        assertThat(tr.toString()).contains("removed");
    }

    // ================================================================================================ papers

    @Test
    void previewPapersAreReproducibleAndCarryNoAnswerInformation() throws Exception {
        UUID a = published("SEMESTER");
        JsonNode paper = call(post("/api/v1/assessments/" + a + "/preview-papers"), controller, status().isCreated());
        assertThat(paper.path("items").size()).isEqualTo(4);
        assertThat(paper.path("hash").asString()).hasSize(64);
        String text = paper.toString();
        assertThat(text).doesNotContain("answerKey").doesNotContain("\"correct\"").doesNotContain("Because A is right").doesNotContain("seed");
        // option order in the view follows the drawn order
        JsonNode item = paper.path("items").get(0);
        List<String> shown = new ArrayList<>();
        item.path("projection").path("options").forEach(o -> shown.add(o.path("id").asString()));
        List<String> drawn = new ArrayList<>();
        item.path("optionOrder").forEach(o -> drawn.add(o.asString()));
        assertThat(shown).isEqualTo(drawn);
        // the seed is stored encrypted, never in clear
        String stored = ownerJdbc().sql("SELECT seed_enc FROM assessment_paper WHERE id = ?").param(UUID.fromString(paper.path("id").asString())).query(String.class).single();
        assertThat(stored).startsWith("v1:");
        // reproduction from the stored seed gives the same paper (published pools are frozen)
        JsonNode rep = call(post("/api/v1/assessment-papers/" + paper.path("id").asString() + "/reproduce"), designer, status().isOk());
        assertThat(rep.path("identical").asBoolean()).isTrue();
        // seeds are disclosed only to assessment.publish, audited
        perform(post("/api/v1/assessment-papers/" + paper.path("id").asString() + "/seed-disclosures"), designer).andExpect(status().isForbidden());
        JsonNode seed = call(post("/api/v1/assessment-papers/" + paper.path("id").asString() + "/seed-disclosures"), controller2, status().isOk());
        assertThat(java.util.Base64.getDecoder().decode(seed.path("seedBase64").asString())).hasSize(32);
        assertThat(audits("assessment.paper_seed_disclosed")).isEqualTo(1);
        // papers never change
        assertThatThrownBy(() -> ownerJdbc().sql("UPDATE assessment_paper SET total_marks = 0 WHERE id = ?").param(UUID.fromString(paper.path("id").asString())).update())
                .isInstanceOf(Exception.class);
        // faculty (no workflow role on semester assessments) cannot preview
        perform(post("/api/v1/assessments/" + a + "/preview-papers"), faculty).andExpect(status().isForbidden());
    }

    @Test
    void thePickerShowsApprovedQuestionsOnlyAndNoAnswers() throws Exception {
        create(bank, faculty, mcq("Draft only " + rnd(), "C"));
        JsonNode page = call(get("/api/v1/assessment-sources/banks/" + bank + "/questions"), designer, status().isOk());
        assertThat(page.path("total").asLong()).isEqualTo(8);
        assertThat(page.toString()).doesNotContain("answerKey").doesNotContain("\"correct\"").doesNotContain("Because");
        assertThat(call(get("/api/v1/assessment-sources/banks/" + bank + "/pool-size"), designer, status().isOk()).path("size").asInt()).isEqualTo(8);
        perform(get("/api/v1/assessment-sources/banks/" + bank + "/questions"), student).andExpect(status().isForbidden());
    }

    // ================================================================================================ isolation

    @Test
    void assessmentsStayInsideTheirInstitutionAndOrganization() throws Exception {
        UUID a = published("SEMESTER");
        perform(get("/api/v1/assessments/" + a), otherController).andExpect(status().isNotFound());
        assertThat(call(get("/api/v1/assessments"), otherController, status().isOk()).findValuesAsString("id")).doesNotContain(a.toString());
        TestOrg foreign = new TestOrg(ownerJdbc(), mvc, "foreign");
        TestOrg.Account foreignAdmin = foreign.account("ORG_ADMIN", null);
        perform(get("/api/v1/assessments/" + a), foreignAdmin).andExpect(status().isNotFound());
        perform(post("/api/v1/assessments/" + a + "/preview-papers"), foreignAdmin).andExpect(status().isNotFound());
        // row-level security: the application role outside any tenant context sees no assessment rows at all
        for (String t : List.of("assessment", "assessment_version", "assessment_item", "assessment_rule", "assessment_rule_pool", "assessment_review",
                "assessment_transition", "assessment_paper", "assessment_version_reference")) {
            assertThat(rawAppJdbc().sql("SELECT count(*) FROM " + t).query(Long.class).single()).as(t).isZero();
        }
    }
}
