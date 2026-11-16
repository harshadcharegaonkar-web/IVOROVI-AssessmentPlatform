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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.JsonNode;

/**
 * Add-on C (Session 4): sections with per-section pools and blueprint constraints, end to end through the API, with
 * the database guards attacked directly. Roles as in {@link AssessmentLifecycleIT}: designer authors, controller
 * reviews, controller2 publishes.
 */
class AssessmentSectionsIT extends QuestionTestSupport {

    UUID bank;
    UUID easy;
    UUID hard;
    List<UUID> easyQs = new ArrayList<>();
    List<UUID> hardQs = new ArrayList<>();

    @Autowired PublishedAssessments published;
    @Autowired com.ivorovi.platform.security.PrincipalResolver principals;

    @BeforeEach
    void setUp() throws Exception {
        setUpOrg("sections");
        bank = standardBank();
        JsonNode tax = call(get("/api/v1/question-taxonomy"), faculty, status().isOk());
        easy = UUID.fromString(tax.path("difficulties").get(0).path("id").asString());
        hard = UUID.fromString(tax.path("difficulties").get(3).path("id").asString());
        for (int i = 0; i < 6; i++) easyQs.add(approved(bank, faculty, designer, controller, withDifficulty(mcq("Easy question " + i + " " + rnd(), "A"), easy)));
        for (int i = 0; i < 6; i++) hardQs.add(approved(bank, faculty, designer, controller, withDifficulty(mcq("Hard question " + i + " " + rnd(), "B"), hard)));
    }

    static String withDifficulty(String draft, UUID difficulty) {
        return draft.replace("\"marks\":2", "\"marks\":2,\"difficultyId\":\"" + difficulty + "\"");
    }

    UUID approvedVersion(UUID q) {
        return ownerJdbc().sql("SELECT approved_version_id FROM question WHERE id = ?").param(q).query(UUID.class).single();
    }

    UUID createAssessment(String purpose) throws Exception {
        JsonNode d = call(post("/api/v1/assessments").content("""
                {"orgUnitId":"%s","code":"S-%s","purpose":"%s","title":"Sectioned %s","durationMinutes":60}""".formatted(college, rnd(), purpose, purpose)),
                designer, status().isCreated());
        return UUID.fromString(d.path("id").asString());
    }

    long lock(UUID a) {
        return ownerJdbc().sql("SELECT v.version FROM assessment a JOIN assessment_version v ON v.id = a.current_version_id WHERE a.id = ?").param(a).query(Long.class).single();
    }

    UUID versionOf(UUID a) {
        return ownerJdbc().sql("SELECT current_version_id FROM assessment WHERE id = ?").param(a).query(UUID.class).single();
    }

    /**
     * Section 1 "Part A": the first easy question fixed plus a rule drawing 2 more easy questions. Section 2 "Part B":
     * a rule drawing 2 hard questions. Blueprint: at least 2 hard in Part B, at least 5 single-choice in the paper.
     */
    String sectioned(UUID a, String sections, String blueprint) {
        return """
                {"title":"Sectioned paper","durationMinutes":60,"shuffleQuestions":true,"shuffleOptions":true,
                 "sections":%s,
                 "items":[{"questionVersionId":"%s","sectionNo":1}],
                 "rules":[{"label":"Easy","bankId":"%s","difficultyId":"%s","count":2,"marks":1,"sectionNo":1},
                          {"label":"Hard","bankId":"%s","difficultyId":"%s","count":2,"marks":3,"sectionNo":2}],
                 "blueprint":%s,"version":%d}"""
                .formatted(sections, approvedVersion(easyQs.get(0)), bank, easy, bank, hard, blueprint, lock(a));
    }

    static final String TWO_SECTIONS = """
            [{"sectionNo":1,"title":"Part A — Foundations","instructions":"Answer every question."},{"sectionNo":2,"title":"Part B — Problems"}]""";

    String standardBlueprint() {
        return """
                [{"sectionNo":2,"dimension":"DIFFICULTY","targetId":"%s","minCount":2},{"dimension":"QUESTION_TYPE","targetCode":"SINGLE_CHOICE","minCount":5,"maxCount":5}]"""
                .formatted(hard);
    }

    JsonNode draft(UUID a, String body) throws Exception {
        return call(put("/api/v1/assessments/" + a + "/draft").content(body), designer, status().isOk());
    }

    void submitReviewPublish(UUID a) throws Exception {
        call(post("/api/v1/assessments/" + a + "/submit").content("{}"), designer, status().isOk());
        call(post("/api/v1/assessments/" + a + "/reviews").content("{\"decision\":\"REVIEWED\"}"), controller, status().isOk());
        call(post("/api/v1/assessments/" + a + "/publish").content("{\"reason\":\"Sectioned paper approved\"}"), controller2, status().isOk());
    }

    void asUser(TestOrg.Account who, Runnable work) {
        com.ivorovi.platform.tenancy.TenantContext.runInTenant(org.tenant, () -> {
            var principal = principals.resolve(org.tenant, who.caller().subject()).orElseThrow();
            SecurityContextHolder.getContext().setAuthentication(new com.ivorovi.platform.security.IvoroviAuthentication(principal, null));
            try {
                work.run();
            } finally {
                SecurityContextHolder.clearContext();
            }
        });
    }

    // ================================================================================================ end to end

    @Test
    void aSectionedAssessmentIsAuthoredProvedPublishedAndGeneratedSectionBySection() throws Exception {
        UUID a = createAssessment("SEMESTER");
        JsonNode d = draft(a, sectioned(a, TWO_SECTIONS, standardBlueprint()));
        JsonNode cur = d.path("current");
        assertThat(cur.path("sections").size()).isEqualTo(2);
        assertThat(cur.path("sections").get(0).path("title").asString()).isEqualTo("Part A — Foundations");
        assertThat(cur.path("items").get(0).path("sectionNo").asInt()).isEqualTo(1);
        assertThat(cur.path("rules").get(1).path("sectionNo").asInt()).isEqualTo(2);
        assertThat(cur.path("blueprint").get(0).path("targetLabel").asString()).isNotBlank();

        JsonNode val = call(get("/api/v1/assessments/" + a + "/validation"), designer, status().isOk());
        assertThat(val.path("errors").size()).as(val.toString()).isZero();
        assertThat(val.path("blueprint").size()).isEqualTo(2);
        assertThat(val.path("blueprint").get(0).path("met").asBoolean()).isTrue();
        assertThat(val.path("blueprint").get(0).path("guaranteedMin").asInt()).isEqualTo(2);
        assertThat(val.path("blueprint").get(1).path("guaranteedMin").asInt()).isEqualTo(5);
        assertThat(val.path("blueprint").get(1).path("possibleMax").asInt()).isEqualTo(5);

        submitReviewPublish(a);
        JsonNode paper = call(post("/api/v1/assessments/" + a + "/preview-papers"), controller, status().isCreated());
        assertThat(paper.path("generatorVersion").asInt()).isEqualTo(2);
        List<Integer> sections = new ArrayList<>();
        paper.path("items").forEach(i -> sections.add(i.path("sectionNo").asInt()));
        assertThat(sections).containsExactly(1, 1, 1, 2, 2);
        assertThat(paper.path("sections").size()).isEqualTo(2);
        // Part B holds only hard questions (3 marks each); Part A holds the fixed easy question
        List<String> partA = new ArrayList<>();
        for (int i = 0; i < 3; i++) partA.add(paper.path("items").get(i).path("questionVersionId").asString());
        assertThat(partA).contains(approvedVersion(easyQs.get(0)).toString());
        for (int i = 3; i < 5; i++) assertThat(paper.path("items").get(i).path("marks").decimalValue()).isEqualByComparingTo("3");
        assertThat(paper.toString()).doesNotContain("answerKey").doesNotContain("\"correct\"").doesNotContain("Because");
        JsonNode rep = call(post("/api/v1/assessment-papers/" + paper.path("id").asString() + "/reproduce"), designer, status().isOk());
        assertThat(rep.path("identical").asBoolean()).as(rep.toString()).isTrue();

        // the candidate's paper carries the section headers (title and instructions), and nothing of the key
        UUID vid = versionOf(a);
        asUser(controller, () -> {
            UUID attemptPaper = published.paperForAttempt(vid, "attempt:sections-" + rnd());
            List<PublishedAssessments.CandidateItem> items = published.candidatePaper(attemptPaper);
            assertThat(items).extracting(PublishedAssessments.CandidateItem::sectionNo).containsExactly(1, 1, 1, 2, 2);
            assertThat(items.getFirst().sectionTitle()).isEqualTo("Part A — Foundations");
            assertThat(items.getFirst().sectionInstructions()).isEqualTo("Answer every question.");
            assertThat(items.getLast().sectionTitle()).isEqualTo("Part B — Problems");
            assertThat(items.toString()).doesNotContain("correct").doesNotContain("Because");
        });
        assertThat(ownerJdbc().sql("SELECT generator_version FROM assessment_paper WHERE version_id = ? AND purpose = 'ATTEMPT'").param(vid).query(Integer.class).single())
                .isEqualTo(2);

        // a new version keeps sections, blueprint and the section of every question
        JsonNode nv = call(post("/api/v1/assessments/" + a + "/versions"), designer, status().isCreated());
        assertThat(nv.path("current").path("sections").size()).isEqualTo(2);
        assertThat(nv.path("current").path("blueprint").size()).isEqualTo(2);
        assertThat(nv.path("current").path("items").get(0).path("sectionNo").asInt()).isEqualTo(1);
        assertThat(nv.path("current").path("rules").get(1).path("sectionNo").asInt()).isEqualTo(2);
        assertThat(audits("assessment.draft_updated")).isEqualTo(1);
    }

    // ================================================================================================ blueprint proofs

    @Test
    void aBlueprintThatSomePapersWouldMissBlocksSubmissionAndPublication() throws Exception {
        UUID a = createAssessment("SEMESTER");
        // the Part A rule has no difficulty filter: 11 candidates (6 hard), so a paper may get 0 hard questions there
        String body = sectioned(a, TWO_SECTIONS, """
                [{"sectionNo":1,"dimension":"DIFFICULTY","targetId":"%s","minCount":1}]""".formatted(hard))
                .replaceFirst(",\"difficultyId\":\"" + easy + "\",\"count\":2", ",\"count\":2");
        // ...and that rule's pool now overlaps the Part B pool, which is refused on its own; give Part B a disjoint filter
        body = body.replace("\"label\":\"Hard\",\"bankId\":\"" + bank + "\",\"difficultyId\":\"" + hard + "\"",
                "\"label\":\"Hard\",\"bankId\":\"" + bank + "\",\"difficultyId\":\"" + hard + "\",\"questionType\":\"MULTIPLE_CHOICE\"");
        draft(a, body);
        JsonNode val = call(get("/api/v1/assessments/" + a + "/validation"), designer, status().isOk());
        assertThat(val.path("errors").findValuesAsString("code")).contains("blueprint_unmet");
        assertThat(val.path("blueprint").get(0).path("met").asBoolean()).isFalse();
        assertThat(val.path("blueprint").get(0).path("guaranteedMin").asInt()).isZero();
        perform(post("/api/v1/assessments/" + a + "/submit").content("{}"), designer)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("validation_failed"));

        // a maximum the rules can exceed
        UUID b = createAssessment("SEMESTER");
        draft(b, sectioned(b, TWO_SECTIONS, """
                [{"sectionNo":2,"dimension":"DIFFICULTY","targetId":"%s","maxCount":1}]""".formatted(hard)));
        JsonNode vb = call(get("/api/v1/assessments/" + b + "/validation"), designer, status().isOk());
        assertThat(vb.path("errors").findValuesAsString("code")).contains("blueprint_unmet");
        assertThat(vb.path("blueprint").get(0).path("possibleMax").asInt()).isEqualTo(2);

        // minimum marks: Part B guarantees 2 × 3 = 6 marks of hard questions, not 7
        UUID c = createAssessment("SEMESTER");
        draft(c, sectioned(c, TWO_SECTIONS, """
                [{"sectionNo":2,"dimension":"DIFFICULTY","targetId":"%s","minMarks":7}]""".formatted(hard)));
        JsonNode vc = call(get("/api/v1/assessments/" + c + "/validation"), designer, status().isOk());
        assertThat(vc.path("blueprint").get(0).path("guaranteedMarks").decimalValue()).isEqualByComparingTo("6");
        assertThat(vc.path("blueprint").get(0).path("met").asBoolean()).isFalse();
    }

    @Test
    void publicationReprovesTheBlueprintOnTheLivePools() throws Exception {
        UUID a = createAssessment("INTERNAL");
        draft(a, sectioned(a, TWO_SECTIONS, standardBlueprint()));
        call(post("/api/v1/assessments/" + a + "/submit").content("{}"), designer, status().isOk());
        call(post("/api/v1/assessments/" + a + "/reviews").content("{\"decision\":\"REVIEWED\"}"), controller, status().isOk());
        // after review, hard questions are retired until only one remains: the rule (and the blueprint) can no longer be met
        for (int i = 0; i < 5; i++) {
            perform(post("/api/v1/questions/" + hardQs.get(i) + "/retire").content("{\"reason\":\"Withdrawn\"}"), controller).andExpect(status().isOk());
        }
        perform(post("/api/v1/assessments/" + a + "/publish").content("{\"reason\":\"x\"}"), controller2)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("validation_failed"));
        JsonNode val = call(get("/api/v1/assessments/" + a + "/validation"), designer, status().isOk());
        assertThat(val.path("errors").findValuesAsString("code")).contains("pool_too_small", "blueprint_unmet");
    }

    // ================================================================================================ input rules

    @Test
    void sectionAndBlueprintInputIsChecked() throws Exception {
        UUID a = createAssessment("SEMESTER");
        // a rule in a section that is not declared
        perform(put("/api/v1/assessments/" + a + "/draft").content(sectioned(a, """
                [{"sectionNo":1,"title":"Only part"}]""", "[]")), designer)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("unknown_section"));
        // without headers everything must be in section 1
        perform(put("/api/v1/assessments/" + a + "/draft").content(sectioned(a, "[]", "[]")), designer)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("unknown_section"));
        // duplicate section numbers
        perform(put("/api/v1/assessments/" + a + "/draft").content(sectioned(a, """
                [{"sectionNo":1,"title":"A"},{"sectionNo":1,"title":"B"},{"sectionNo":2,"title":"C"}]""", "[]")), designer)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_section"));
        // blueprint: unknown target, undeclared section, empty constraint, inverted range
        perform(put("/api/v1/assessments/" + a + "/draft").content(sectioned(a, TWO_SECTIONS, """
                [{"dimension":"TOPIC","targetId":"%s","minCount":1}]""".formatted(UUID.randomUUID()))), designer)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_blueprint"));
        perform(put("/api/v1/assessments/" + a + "/draft").content(sectioned(a, TWO_SECTIONS, """
                [{"sectionNo":3,"dimension":"DIFFICULTY","targetId":"%s","minCount":1}]""".formatted(hard))), designer)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("unknown_section"));
        perform(put("/api/v1/assessments/" + a + "/draft").content(sectioned(a, TWO_SECTIONS, """
                [{"dimension":"DIFFICULTY","targetId":"%s"}]""".formatted(hard))), designer)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_blueprint"));
        perform(put("/api/v1/assessments/" + a + "/draft").content(sectioned(a, TWO_SECTIONS, """
                [{"dimension":"DIFFICULTY","targetId":"%s","minCount":3,"maxCount":2}]""".formatted(hard))), designer)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_blueprint"));
        // a declared section without questions is a validation error (authors build sections step by step)
        draft(a, sectioned(a, TWO_SECTIONS.replace("]", ",{\"sectionNo\":3,\"title\":\"Part C\"}]"), "[]"));
        JsonNode val = call(get("/api/v1/assessments/" + a + "/validation"), designer, status().isOk());
        assertThat(val.path("errors").findValuesAsString("code")).containsExactly("section_empty");
        // nothing was stored by the refused requests
        assertThat(audits("assessment.draft_updated")).isEqualTo(1);
    }

    // ================================================================================================ review binding and immutability

    @Test
    void sectionsAndBlueprintAreReviewedContentAndNeverChangeOncePublished() throws Exception {
        UUID a = createAssessment("SEMESTER");
        draft(a, sectioned(a, TWO_SECTIONS, standardBlueprint()));
        call(post("/api/v1/assessments/" + a + "/submit").content("{}"), designer, status().isOk());
        call(post("/api/v1/assessments/" + a + "/reviews").content("{\"decision\":\"REVIEWED\"}"), controller, status().isOk());
        String reviewed = ownerJdbc().sql("SELECT review_hash FROM assessment_version WHERE id = ?").param(versionOf(a)).query(String.class).single();
        // renaming a section after review returns the version to draft and invalidates the review
        JsonNode d = draft(a, sectioned(a, TWO_SECTIONS.replace("Part B — Problems", "Part B — Harder problems"), standardBlueprint()));
        assertThat(d.path("current").path("state").asString()).isEqualTo("DRAFT");
        assertThat(ownerJdbc().sql("SELECT review_hash FROM assessment_version WHERE id = ?").param(versionOf(a)).query(String.class).single()).isNotEqualTo(reviewed);
        assertThat(d.path("current").path("reviews").get(0).path("valid").asBoolean()).isFalse();
        // moving a constraint to the whole paper changes the hash too
        String before = ownerJdbc().sql("SELECT review_hash FROM assessment_version WHERE id = ?").param(versionOf(a)).query(String.class).single();
        draft(a, sectioned(a, TWO_SECTIONS.replace("Part B — Problems", "Part B — Harder problems"), standardBlueprint().replace("\"sectionNo\":2,", "")));
        assertThat(ownerJdbc().sql("SELECT review_hash FROM assessment_version WHERE id = ?").param(versionOf(a)).query(String.class).single()).isNotEqualTo(before);

        draft(a, sectioned(a, TWO_SECTIONS, standardBlueprint()));
        submitReviewPublish(a);
        UUID vid = versionOf(a);
        assertThatThrownBy(() -> ownerJdbc().sql("UPDATE assessment_section SET title = 'tampered' WHERE version_id = ?").param(vid).update()).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> ownerJdbc().sql("DELETE FROM assessment_section WHERE version_id = ?").param(vid).update()).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> ownerJdbc().sql("INSERT INTO assessment_section (tenant_id, version_id, section_no, title) VALUES (?, ?, 9, 'x')")
                .params(org.tenant, vid).update()).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> ownerJdbc().sql("UPDATE assessment_blueprint_constraint SET min_count = 0 WHERE version_id = ?").param(vid).update()).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> ownerJdbc().sql("DELETE FROM assessment_blueprint_constraint WHERE version_id = ?").param(vid).update()).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> ownerJdbc().sql("UPDATE assessment_item SET section_no = 2 WHERE version_id = ?").param(vid).update()).isInstanceOf(Exception.class);
    }

    @Test
    void aVersionWithoutSectionsKeepsItsReviewHashAndVersionOnePapers() throws Exception {
        UUID a = createAssessment("INTERNAL");
        String plain = """
                {"title":"Plain","items":[{"questionVersionId":"%s"}],"rules":[{"bankId":"%s","difficultyId":"%s","count":2,"marks":1}],"version":%d}"""
                .formatted(approvedVersion(easyQs.get(0)), bank, hard, lock(a));
        draft(a, plain);
        // the hash of a version without sections or blueprint is computed exactly as before add-on C (no S/P/B records)
        assertThat(ownerJdbc().sql("SELECT count(*) FROM assessment_section WHERE version_id = ?").param(versionOf(a)).query(Long.class).single()).isZero();
        String hash = ownerJdbc().sql("SELECT review_hash FROM assessment_version WHERE id = ?").param(versionOf(a)).query(String.class).single();
        draft(a, plain.replace("\"version\":" + (lock(a) - 1), "\"version\":" + lock(a)));
        assertThat(ownerJdbc().sql("SELECT review_hash FROM assessment_version WHERE id = ?").param(versionOf(a)).query(String.class).single()).isEqualTo(hash);
        submitReviewPublish(a);
        JsonNode paper = call(post("/api/v1/assessments/" + a + "/preview-papers"), controller, status().isCreated());
        assertThat(paper.path("generatorVersion").asInt()).isEqualTo(1);
        assertThat(paper.path("sections").size()).isZero();
        paper.path("items").forEach(i -> assertThat(i.path("sectionNo").isNull() || i.path("sectionNo").isMissingNode()).as(i.toString()).isTrue());
    }

    // ================================================================================================ isolation

    @Test
    void sectionsAndBlueprintsStayInsideTheirOrganization() throws Exception {
        UUID a = createAssessment("SEMESTER");
        draft(a, sectioned(a, TWO_SECTIONS, standardBlueprint()));
        // the application role sees another organization's rows nowhere (RLS)
        assertThat(rawAppJdbc().sql("SELECT count(*) FROM assessment_section").query(Long.class).single()).isZero();
        assertThat(rawAppJdbc().sql("SELECT count(*) FROM assessment_blueprint_constraint").query(Long.class).single()).isZero();
        // another institution's controller cannot read the assessment at all
        perform(get("/api/v1/assessments/" + a), otherController).andExpect(status().isNotFound());
        // a blueprint cannot target another organization's taxonomy (RLS hides it: unknown)
        TestOrg other = new TestOrg(ownerJdbc(), mvc, "sections-other");
        UUID foreignDifficulty = UUID.randomUUID();
        ownerJdbc().sql("INSERT INTO difficulty_level (id, tenant_id, code, label, rank) VALUES (?, ?, 'FOREIGN', 'Foreign', 9)").params(foreignDifficulty, other.tenant).update();
        perform(put("/api/v1/assessments/" + a + "/draft").content(sectioned(a, TWO_SECTIONS, """
                [{"dimension":"DIFFICULTY","targetId":"%s","minCount":1}]""".formatted(foreignDifficulty))), designer)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_blueprint"));
    }
}
