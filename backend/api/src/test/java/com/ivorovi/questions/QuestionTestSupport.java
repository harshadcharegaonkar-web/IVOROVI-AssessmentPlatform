package com.ivorovi.questions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ivorovi.support.IntegrationTest;
import com.ivorovi.support.TestOrg;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Shared fixture for question-bank tests: an organization with two institutions and the P2-07 role matrix. */
public abstract class QuestionTestSupport extends IntegrationTest {

    protected static final JsonMapper JSON = JsonMapper.builder().build();

    protected TestOrg org;
    protected UUID uni, college, otherCollege;
    protected TestOrg.Account admin, controller, controller2, controller3, designer, designer2, faculty, faculty2, instAdmin, otherFaculty, otherController, student;

    protected void setUpOrg(String label) {
        org = new TestOrg(ownerJdbc(), mvc, label);
        uni = org.unit("UNIVERSITY", null, "U");
        college = org.unit("COLLEGE", uni, "C1");
        otherCollege = org.unit("COLLEGE", uni, "C2");
        admin = org.account("ORG_ADMIN", null);
        instAdmin = org.account("INSTITUTION_ADMIN", college);
        controller = org.account("EXAM_CONTROLLER", college);
        controller2 = org.account("EXAM_CONTROLLER", college);
        controller3 = org.account("EXAM_CONTROLLER", college);
        designer = org.account("ASSESSMENT_DESIGNER", college);
        designer2 = org.account("ASSESSMENT_DESIGNER", college);
        faculty = org.account("FACULTY", college);
        faculty2 = org.account("FACULTY", college);
        otherFaculty = org.account("FACULTY", otherCollege);
        otherController = org.account("EXAM_CONTROLLER", otherCollege);
        student = org.account("STUDENT", college);
    }

    protected JsonNode call(MockHttpServletRequestBuilder r, TestOrg.Account who, ResultMatcher expect) throws Exception {
        String body = mvc.perform(r.header(HttpHeaders.AUTHORIZATION, who.bearer()).contentType(MediaType.APPLICATION_JSON)).andExpect(expect)
                .andReturn().getResponse().getContentAsString();
        return body.isEmpty() ? null : JSON.readTree(body);
    }

    protected ResultActions perform(MockHttpServletRequestBuilder r, TestOrg.Account who) throws Exception {
        return mvc.perform(r.header(HttpHeaders.AUTHORIZATION, who.bearer()).contentType(MediaType.APPLICATION_JSON));
    }

    protected UUID bank(TestOrg.Account by, UUID unit, String policy, int reviews, boolean distinct, String uses) throws Exception {
        JsonNode b = call(post("/api/v1/question-banks").content("""
                {"orgUnitId":%s,"code":"B%s","name":"Bank %s","intendedUses":[%s],
                 "policy":{"approvalPolicy":"%s","requiredReviews":%d,"approverDistinct":%s}}"""
                .formatted(unit == null ? "null" : "\"" + unit + "\"", rnd(), rnd(), uses, policy, reviews, distinct)), by, status().isCreated());
        return UUID.fromString(b.path("id").asString());
    }

    protected UUID standardBank() throws Exception {
        return bank(admin, college, "STANDARD", 1, false, "\"ACADEMIC\"");
    }

    protected static String rnd() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    protected static String content(String text) {
        return "{\"format\":\"ivorovi.content.v1\",\"blocks\":[{\"type\":\"paragraph\",\"runs\":[{\"text\":" + JSON.writeValueAsString(text) + "}]}]}";
    }

    protected static String mcq(String stem, String correct) {
        return """
                {"type":"SINGLE_CHOICE","content":%s,"body":{"options":[{"id":"A","content":%s},{"id":"B","content":%s},{"id":"C","content":%s}]},
                 "answerKey":{"correct":"%s"},"explanation":"Because %s is right","marks":2,"negativeMarks":0.5}"""
                .formatted(content(stem), content("Option one"), content("Option two"), content("Option three"), correct, correct);
    }

    protected static String numeric(String stem, String value) {
        return """
                {"type":"NUMERIC","content":%s,"body":{"precision":2,"unit":"m/s"},
                 "answerKey":{"value":"%s","tolerance":{"type":"ABSOLUTE","value":"0.05"}},"marks":4}""".formatted(content(stem), value);
    }

    protected UUID create(UUID bank, TestOrg.Account by, String draft) throws Exception {
        return UUID.fromString(call(post("/api/v1/question-banks/" + bank + "/questions").content(draft), by, status().isCreated()).path("id").asString());
    }

    protected JsonNode detail(UUID q, TestOrg.Account by) throws Exception {
        return call(get("/api/v1/questions/" + q), by, status().isOk());
    }

    protected JsonNode submit(UUID q, TestOrg.Account by) throws Exception {
        return call(post("/api/v1/questions/" + q + "/submit").content("{}"), by, status().isOk());
    }

    protected ResultActions review(UUID q, TestOrg.Account by, String decision, String comment) throws Exception {
        return perform(post("/api/v1/questions/" + q + "/reviews").content("{\"decision\":\"" + decision + "\",\"comment\":"
                + (comment == null ? "null" : "\"" + comment + "\"") + ",\"checklist\":{\"accurate\":true}}"), by);
    }

    protected ResultActions approve(UUID q, TestOrg.Account by) throws Exception {
        return perform(post("/api/v1/questions/" + q + "/approve").content("{\"reason\":\"ok\"}"), by);
    }

    /** Edits the open version, sending the current optimistic-lock version as a client would. */
    protected ResultActions update(UUID q, TestOrg.Account by, String draft) throws Exception {
        long v = ownerJdbc().sql("SELECT v.version FROM question q JOIN question_version v ON v.id = q.current_version_id WHERE q.id = ?").param(q)
                .query(Long.class).single();
        return perform(put("/api/v1/questions/" + q).content("{\"version\":" + v + "," + draft.strip().substring(1)), by);
    }

    /** Draft by {@code author}, reviewed by {@code reviewer}, approved by {@code approver} (standard bank). */
    protected UUID approved(UUID bank, TestOrg.Account author, TestOrg.Account reviewer, TestOrg.Account approver, String draft) throws Exception {
        UUID q = create(bank, author, draft);
        submit(q, author);
        review(q, reviewer, "REVIEWED", null).andExpect(status().isOk());
        approve(q, approver).andExpect(status().isOk());
        return q;
    }

    protected long audits(String action) {
        return ownerJdbc().sql("SELECT count(*) FROM audit_event WHERE tenant_id = ? AND action = ?").params(org.tenant, action).query(Long.class).single();
    }

    protected UUID versionId(UUID q, int no) {
        return ownerJdbc().sql("SELECT id FROM question_version WHERE question_id = ? AND version_no = ?").params(q, no).query(UUID.class).single();
    }
}
