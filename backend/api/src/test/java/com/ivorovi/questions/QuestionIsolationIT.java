package com.ivorovi.questions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.support.TestOrg;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/**
 * Tenant isolation and institution permissions (demo item 11) and taxonomy/metadata: banks of other institutions and
 * other organizations are invisible (404) through every endpoint and at the database (RLS); taxonomy is managed with
 * bank.manage only; required institution metadata gates submission; search filters by taxonomy.
 */
class QuestionIsolationIT extends QuestionTestSupport {

    @Autowired JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        setUpOrg("qiso");
    }

    @Test
    void otherInstitutionsAndOrganizationsSeeNothing() throws Exception {
        UUID bank = standardBank();
        UUID q = approved(bank, faculty, designer, controller, mcq("Isolated question", "A"));
        // institution scope: the other college does not list or read the bank
        assertThat(call(get("/api/v1/question-banks"), otherFaculty, status().isOk()).toString()).doesNotContain(bank.toString());
        assertThat(call(get("/api/v1/question-banks"), faculty, status().isOk()).toString()).contains(bank.toString());
        assertThat(call(get("/api/v1/question-banks"), admin, status().isOk()).toString()).as("organization-wide administrator").contains(bank.toString());
        for (String path : List.of("/api/v1/question-banks/" + bank, "/api/v1/question-banks/" + bank + "/questions", "/api/v1/questions/" + q,
                "/api/v1/questions/" + q + "/versions/1", "/api/v1/questions/" + q + "/history", "/api/v1/questions/" + q + "/transitions",
                "/api/v1/question-banks/" + bank + "/assets", "/api/v1/question-banks/" + bank + "/imports")) {
            perform(get(path), otherController).andExpect(status().isNotFound());
        }
        perform(post("/api/v1/question-banks/" + bank + "/questions").content(mcq("Intruder", "A")), otherFaculty).andExpect(status().isNotFound());
        perform(post("/api/v1/questions/" + q + "/retire").content("{\"reason\":\"x\"}"), otherController).andExpect(status().isNotFound());
        perform(put("/api/v1/question-banks/" + bank).content("""
                {"orgUnitId":"%s","code":"X","name":"Taken over","policy":{"approvalPolicy":"STANDARD","requiredReviews":1,"approverDistinct":false}}"""
                .formatted(otherCollege)), otherController).andExpect(status().is4xxClientError());
        assertThat(call(get("/api/v1/question-review-queue?stage=approval"), otherController, status().isOk()).size()).isZero();
        // a bank cannot be created in an institution the caller does not manage
        perform(post("/api/v1/question-banks").content("""
                {"orgUnitId":"%s","code":"NOPE","name":"No","policy":{"approvalPolicy":"STANDARD","requiredReviews":1,"approverDistinct":false}}""".formatted(otherCollege)),
                instAdmin).andExpect(status().isForbidden());

        // other organization: nothing through the API, nothing through RLS
        TestOrg other = new TestOrg(ownerJdbc(), mvc, "qiso-other");
        TestOrg.Account foreign = other.account("ORG_ADMIN", null);
        assertThat(call(get("/api/v1/question-banks"), foreign, status().isOk()).size()).isZero();
        perform(get("/api/v1/questions/" + q), foreign).andExpect(status().isNotFound());
        long visible = TenantContext.callInTenant(other.tenant, () -> jdbc.sql("""
                SELECT (SELECT count(*) FROM question_bank) + (SELECT count(*) FROM question) + (SELECT count(*) FROM question_version)
                     + (SELECT count(*) FROM question_answer_key) + (SELECT count(*) FROM question_review) + (SELECT count(*) FROM question_asset)""")
                .query(Long.class).single());
        assertThat(visible).isZero();
        long own = TenantContext.callInTenant(org.tenant, () -> jdbc.sql("SELECT count(*) FROM question_answer_key").query(Long.class).single());
        assertThat(own).isEqualTo(1);
        // without a tenant context the application role sees no rows at all
        assertThat(rawAppJdbc().sql("SELECT count(*) FROM question_version").query(Long.class).single()).isZero();
        // the indexed near-duplicate function (SECURITY DEFINER) applies the same tenant rule
        String stem = ownerJdbc().sql("SELECT normalized_text FROM question_version WHERE question_id = ?").param(q).query(String.class).single();
        assertThat(TenantContext.callInTenant(org.tenant, () -> jdbc.sql("SELECT count(*) FROM question_near_duplicate(?, ?)").params(bank, stem)
                .query(Long.class).single())).isEqualTo(1);
        assertThat(TenantContext.callInTenant(other.tenant, () -> jdbc.sql("SELECT count(*) FROM question_near_duplicate(?, ?)").params(bank, stem)
                .query(Long.class).single())).isZero();
        assertThat(rawAppJdbc().sql("SELECT count(*) FROM question_near_duplicate(?, ?)").params(bank, stem).query(Long.class).single()).isZero();
    }

    @Test
    void taxonomyIsManagedByBankManagersAndDrivesSearchAndRequiredMetadata() throws Exception {
        UUID bank = standardBank();
        JsonNode subject = call(post("/api/v1/question-taxonomy/subjects").content("{\"orgUnitId\":\"" + college + "\",\"code\":\"PHY\",\"name\":\"Physics\"}"),
                admin, status().isCreated());
        UUID sid = UUID.fromString(subject.path("id").asString());
        perform(post("/api/v1/question-taxonomy/subjects").content("{\"orgUnitId\":\"" + college + "\",\"code\":\"CHE\",\"name\":\"Chemistry\"}"), faculty)
                .andExpect(status().isForbidden());
        UUID topic = UUID.fromString(call(post("/api/v1/question-taxonomy/topics").content("{\"subjectId\":\"" + sid + "\",\"name\":\"Mechanics\"}"),
                admin, status().isCreated()).path("id").asString());
        UUID sub = UUID.fromString(call(post("/api/v1/question-taxonomy/topics").content("{\"subjectId\":\"" + sid + "\",\"parentId\":\"" + topic + "\",\"name\":\"Kinematics\"}"),
                admin, status().isCreated()).path("id").asString());
        perform(post("/api/v1/question-taxonomy/topics").content("{\"subjectId\":\"" + sid + "\",\"parentId\":\"" + sub + "\",\"name\":\"Too deep\"}"), admin)
                .andExpect(status().isBadRequest());
        UUID outcome = UUID.fromString(call(post("/api/v1/question-taxonomy/learning-outcomes").content(
                "{\"subjectId\":\"" + sid + "\",\"code\":\"LO1\",\"description\":\"Apply equations of motion\",\"bloomLevel\":\"APPLY\"}"), admin, status().isCreated())
                .path("id").asString());
        JsonNode tax = call(get("/api/v1/question-taxonomy"), faculty, status().isOk());
        assertThat(tax.path("difficulties").size()).as("default difficulty levels").isEqualTo(5);
        UUID hard = UUID.fromString(tax.path("difficulties").get(3).path("id").asString());
        call(post("/api/v1/question-taxonomy/attributes").content("{\"key\":\"syllabus_ref\",\"label\":\"Syllabus reference\",\"type\":\"TEXT\",\"requiredForApproval\":true}"),
                admin, status().isCreated());

        String draft = numeric("Distance travelled in 2 s from rest at 3 m/s²?", "6").replace("\"marks\":4", """
                "marks":4,"subjectId":"%s","topicId":"%s","subtopicId":"%s","difficultyId":"%s","outcomeIds":["%s"],"tags":["kinematics","board-2026"]"""
                .formatted(sid, topic, sub, hard, outcome));
        UUID q = create(bank, faculty, draft);
        perform(post("/api/v1/questions/" + q + "/submit").content("{}"), faculty).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("metadata_missing"));
        update(q, faculty, draft.replace("\"tags\":", "\"attributes\":{\"syllabus_ref\":\"PHY-2.3\"},\"tags\":")).andExpect(status().isOk());
        submit(q, faculty);
        create(bank, faculty, mcq("Unrelated chemistry question", "A"));

        assertThat(call(get("/api/v1/question-banks/" + bank + "/questions?subtopicId=" + sub), faculty, status().isOk()).path("total").asLong()).isEqualTo(1);
        assertThat(call(get("/api/v1/question-banks/" + bank + "/questions?difficultyId=" + hard), faculty, status().isOk()).path("total").asLong()).isEqualTo(1);
        assertThat(call(get("/api/v1/question-banks/" + bank + "/questions?outcomeId=" + outcome), faculty, status().isOk()).path("total").asLong()).isEqualTo(1);
        assertThat(call(get("/api/v1/question-banks/" + bank + "/questions?tag=KINEMATICS"), faculty, status().isOk()).path("total").asLong()).isEqualTo(1);
        assertThat(call(get("/api/v1/question-banks/" + bank + "/questions?attributeKey=syllabus_ref&attributeValue=PHY-2.3"), faculty, status().isOk())
                .path("total").asLong()).isEqualTo(1);
        assertThat(call(get("/api/v1/question-banks/" + bank + "/questions?q=travelled"), faculty, status().isOk()).path("total").asLong()).isEqualTo(1);
        assertThat(call(get("/api/v1/question-banks/" + bank + "/questions?type=SINGLE_CHOICE"), faculty, status().isOk()).path("total").asLong()).isEqualTo(1);
        // a subtopic of another subject's topic is refused
        perform(post("/api/v1/question-banks/" + bank + "/questions").content(numeric("x", "1").replace("\"marks\":4", "\"marks\":4,\"subtopicId\":\"" + sub + "\"")),
                faculty).andExpect(status().isBadRequest());
        // deactivated entries cannot be used for new questions; existing ones keep them
        perform(put("/api/v1/question-taxonomy/topics/" + sub + "/status").content("{\"active\":false}"), admin).andExpect(status().isNoContent());
        perform(post("/api/v1/question-banks/" + bank + "/questions").content(draft), faculty).andExpect(status().isBadRequest());
        assertThat(detail(q, faculty).path("current").path("subtopicId").asString()).isEqualTo(sub.toString());
    }

    @Test
    void negativeMarksAreBoundedByMarks() throws Exception {
        UUID bank = standardBank();
        perform(post("/api/v1/question-banks/" + bank + "/questions").content(mcq("Negative", "A").replace("\"negativeMarks\":0.5", "\"negativeMarks\":3")), faculty)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_marks"));
        perform(post("/api/v1/question-banks/" + bank + "/questions").content(mcq("Zero", "A").replace("\"marks\":2", "\"marks\":0")), faculty)
                .andExpect(status().isBadRequest());
    }
}
