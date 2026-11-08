package com.ivorovi.questions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.support.TestOrg;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Question lifecycle and governance (demo items 1, 2, 5–8): drafting, independent review and approval, the database
 * backstop for every governance rule, review invalidation on modification, immutable approved versions, new versions,
 * retirement, and permanent references.
 */
class QuestionLifecycleIT extends QuestionTestSupport {

    @Autowired QuestionReferences references;

    @BeforeEach
    void setUp() {
        setUpOrg("qlife");
    }

    /** Gives an account a second system role (e.g. an exam controller who also authors). */
    private void alsoRole(TestOrg.Account a, String role, UUID unit) {
        UUID roleId = ownerJdbc().sql("SELECT id FROM role WHERE code = ? AND tenant_id IS NULL").param(role).query(UUID.class).single();
        ownerJdbc().sql("INSERT INTO role_assignment (id, tenant_id, user_account_id, role_id, scope_type, scope_id) VALUES (?, ?, ?, ?, 'ORG_UNIT', ?)")
                .params(UUID.randomUUID(), org.tenant, a.id(), roleId, unit).update();
    }

    @Test
    void standardWorkflowWithEveryQuestionType() throws Exception {
        UUID bank = standardBank();
        String[] drafts = {
                mcq("Which planet is largest?", "B"),
                """
                {"type":"MULTIPLE_CHOICE","content":%s,"body":{"options":[{"id":"A","content":%s},{"id":"B","content":%s},{"id":"C","content":%s}],"maxSelections":2},
                 "answerKey":{"correct":["A","C"]},"marking":{"mode":"PARTIAL"},"marks":2}""".formatted(content("Select primes"), content("2"), content("4"), content("5")),
                """
                {"type":"TRUE_FALSE","content":%s,"body":{},"answerKey":{"value":true}}""".formatted(content("Water boils at 100 °C at sea level.")),
                """
                {"type":"SHORT_ANSWER","content":%s,"body":{"maxLength":50},"answerKey":{"answers":[{"text":"Photosynthesis"},{"text":"photo synthesis","percent":50}],
                 "match":"NORMALIZED"}}""".formatted(content("Name the process plants use to make food.")),
                numeric("Speed after 2 s of free fall (g = 9.81)?", "19.62")};
        for (String d : drafts) {
            UUID q = create(bank, faculty, d);
            JsonNode det = detail(q, faculty);
            assertThat(det.path("current").path("state").asString()).isEqualTo("DRAFT");
            assertThat(det.path("actions").path("submit").asBoolean()).isTrue();
            submit(q, faculty);
            review(q, designer, "REVIEWED", null).andExpect(status().isOk()).andExpect(jsonPath("$.current.state").value("REVIEWED"));
            approve(q, controller).andExpect(status().isOk()).andExpect(jsonPath("$.current.state").value("APPROVED"))
                    .andExpect(jsonPath("$.current.approvedBy").value(controller.id().toString()));
        }
        JsonNode page = call(get("/api/v1/question-banks/" + bank + "/questions?state=APPROVED"), controller, status().isOk());
        assertThat(page.path("total").asLong()).isEqualTo(5);
        assertThat(audits("question.approved")).isEqualTo(5);
    }

    @Test
    void authorsCannotReviewOrApproveTheirOwnQuestion() throws Exception {
        alsoRole(controller3, "ASSESSMENT_DESIGNER", college);     // controller3 can author, review and approve
        UUID bank = standardBank();
        UUID q = create(bank, controller3, mcq("Self-approval attempt", "A"));
        submit(q, controller3);
        review(q, controller3, "REVIEWED", null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("self_review"));
        assertThat(audits("question.self_review_denied")).isEqualTo(1);
        review(q, designer, "REVIEWED", null).andExpect(status().isOk());
        assertThat(detail(q, controller3).path("actions").path("approve").asBoolean()).isFalse();
        approve(q, controller3).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("self_approval"));
        assertThat(audits("question.self_approval_denied")).isEqualTo(1);
        // the database refuses it as well, even for the schema owner
        assertThatThrownBy(() -> ownerJdbc().sql("UPDATE question_version SET state = 'APPROVED', approved_by = author_id, approved_at = now() WHERE id = ?")
                .param(versionId(q, 1)).update()).hasMessageContaining("author cannot approve");
        // not in the author's own queues
        assertThat(call(get("/api/v1/question-review-queue?stage=approval"), controller3, status().isOk()).size()).isZero();
        approve(q, controller).andExpect(status().isOk());
    }

    @Test
    void facultyAndDesignersCannotApproveAndAdministratorsCannotApprove() throws Exception {
        UUID bank = standardBank();
        UUID q = create(bank, faculty, mcq("Permissions", "A"));
        submit(q, faculty);
        review(q, faculty2, "REVIEWED", null).andExpect(status().isForbidden());        // faculty: no review permission
        review(q, designer, "REVIEWED", null).andExpect(status().isOk());
        approve(q, designer).andExpect(status().isForbidden());                         // designer: no approve permission
        approve(q, admin).andExpect(status().isForbidden());                            // administrators never hold question.approve (P2-08)
        approve(q, instAdmin).andExpect(status().isForbidden());
        // administrators may author (P2-08), students may not
        perform(post("/api/v1/question-banks/" + bank + "/questions").content(mcq("x", "A")), admin).andExpect(status().isCreated());
        perform(post("/api/v1/question-banks/" + bank + "/questions").content(mcq("x", "A")), student).andExpect(status().isForbidden());
        // another author in the same bank cannot edit someone else's draft
        UUID d = create(bank, faculty, mcq("Faculty draft", "A"));
        update(d, faculty2, mcq("Hijacked", "B")).andExpect(status().isForbidden());
    }

    @Test
    void highStakesBanksNeedThreeDistinctPeopleEnforcedByServiceAndDatabase() throws Exception {
        // admission banks must be high-stakes (service and database CHECK)
        perform(post("/api/v1/question-banks").content("""
                {"orgUnitId":"%s","code":"ADM%s","name":"Weak admission bank","intendedUses":["ADMISSION_EXAM"],
                 "policy":{"approvalPolicy":"STANDARD","requiredReviews":1,"approverDistinct":false}}""".formatted(college, rnd())), admin)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("high_stakes_required"));
        assertThatThrownBy(() -> ownerJdbc().sql("""
                INSERT INTO question_bank (id, tenant_id, org_unit_id, code, name, intended_uses, approval_policy, required_reviews, approver_distinct, created_by)
                VALUES (?, ?, ?, 'SQLADM', 'x', '{ADMISSION_EXAM}', 'STANDARD', 1, false, ?)""").params(UUID.randomUUID(), org.tenant, college, admin.id()).update())
                .hasMessageContaining("question_bank_admission_high_stakes");
        // high-stakes without a distinct approver is normalized to a distinct approver
        UUID bank = bank(admin, college, "HIGH_STAKES", 1, false, "\"ADMISSION_EXAM\"");
        JsonNode b = call(get("/api/v1/question-banks/" + bank), controller, status().isOk());
        assertThat(b.path("policy").path("approvalPolicy").asString()).isEqualTo("HIGH_STAKES");
        assertThat(b.path("effectivePolicy").path("approverDistinct").asBoolean()).isTrue();

        UUID q = create(bank, faculty, mcq("Admission question", "C"));
        submit(q, faculty);
        review(q, controller, "REVIEWED", null).andExpect(status().isOk());
        approve(q, controller).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("approver_reviewed"));
        assertThat(audits("question.approval_policy_denied")).isEqualTo(1);
        assertThatThrownBy(() -> ownerJdbc().sql("UPDATE question_version SET state = 'APPROVED', approved_by = ?, approved_at = now() WHERE id = ?")
                .params(controller.id(), versionId(q, 1)).update()).hasMessageContaining("different approver");
        approve(q, controller2).andExpect(status().isOk());
        JsonNode det = detail(q, controller2);
        assertThat(det.path("current").path("authorId").asString()).isEqualTo(faculty.id().toString());
        assertThat(det.path("current").path("reviews").get(0).path("reviewerId").asString()).isEqualTo(controller.id().toString());
        assertThat(det.path("current").path("approvedBy").asString()).isEqualTo(controller2.id().toString());
    }

    @Test
    void requiredReviewCountIsEnforcedAndADirectApprovalWithTooFewReviewsIsRefused() throws Exception {
        UUID bank = bank(admin, college, "HIGH_STAKES", 2, true, "\"ADMISSION_SCREENING\"");
        UUID q = create(bank, faculty, mcq("Two reviews", "A"));
        submit(q, faculty);
        review(q, designer, "REVIEWED", null).andExpect(jsonPath("$.current.state").value("IN_REVIEW"));
        approve(q, controller).andExpect(status().isConflict());
        assertThatThrownBy(() -> ownerJdbc().sql("""
                DO $$ BEGIN
                  UPDATE question_version SET state = 'REVIEWED' WHERE id = '%1$s';
                  UPDATE question_version SET state = 'APPROVED', approved_by = '%2$s', approved_at = now() WHERE id = '%1$s';
                END $$""".formatted(versionId(q, 1), controller.id())).update()).hasMessageContaining("valid review");
        review(q, designer2, "REVIEWED", null).andExpect(jsonPath("$.current.state").value("REVIEWED"));
        approve(q, controller).andExpect(status().isOk());
    }

    @Test
    void institutionFloorsMakeBanksStricterAndWeakeningNeedsASecondAdministrator() throws Exception {
        UUID bank = standardBank();
        call(put("/api/v1/question-policy-floors/" + college).content("{\"approvalPolicy\":\"HIGH_STAKES\",\"requiredReviews\":2,\"approverDistinct\":true}"),
                admin, status().isOk());
        JsonNode b = call(get("/api/v1/question-banks/" + bank), controller, status().isOk());
        assertThat(b.path("policy").path("approvalPolicy").asString()).isEqualTo("STANDARD");
        assertThat(b.path("effectivePolicy").path("approvalPolicy").asString()).isEqualTo("HIGH_STAKES");
        assertThat(b.path("effectivePolicy").path("requiredReviews").asInt()).isEqualTo(2);
        // a bank of the other institution is unaffected
        UUID other = bank(admin, otherCollege, "STANDARD", 1, false, "\"PRACTICE\"");
        assertThat(call(get("/api/v1/question-banks/" + other), admin, status().isOk()).path("effectivePolicy").path("requiredReviews").asInt()).isEqualTo(1);
        // weakening the floor is not applied directly
        JsonNode f = call(put("/api/v1/question-policy-floors/" + college).content("{\"approvalPolicy\":\"STANDARD\",\"requiredReviews\":1,\"approverDistinct\":false}"),
                admin, status().isOk());
        assertThat(f.path("policy").path("approvalPolicy").asString()).isEqualTo("HIGH_STAKES");
        assertThat(ownerJdbc().sql("SELECT count(*) FROM approval_request WHERE tenant_id = ? AND type = 'question.policy' AND status = 'PENDING'")
                .param(org.tenant).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void modifyingReviewedContentInvalidatesReviews() throws Exception {
        UUID bank = standardBank();
        UUID q = create(bank, faculty, mcq("Original statement", "A"));
        submit(q, faculty);
        review(q, designer, "REVIEWED", null).andExpect(jsonPath("$.current.state").value("REVIEWED"));
        // author edits while reviewed: back to draft, review invalidated
        JsonNode e = JSON.readTree(update(q, faculty, mcq("Changed statement", "A")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(e.path("current").path("state").asString()).isEqualTo("DRAFT");
        assertThat(e.path("current").path("reviews").get(0).path("valid").asBoolean()).isFalse();
        submit(q, faculty);
        approve(q, controller).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("not_reviewed"));
        // changing only the answer key also invalidates
        review(q, designer, "REVIEWED", null).andExpect(status().isOk());
        update(q, faculty, mcq("Changed statement", "B")).andExpect(status().isOk()).andExpect(jsonPath("$.current.state").value("DRAFT"));
        assertThat(ownerJdbc().sql("SELECT count(*) FROM question_review WHERE version_id = ? AND invalidated_at IS NULL").param(versionId(q, 1))
                .query(Long.class).single()).isZero();
        // the database refuses a content change of a version in review that keeps its state
        submit(q, faculty);
        assertThatThrownBy(() -> ownerJdbc().sql("UPDATE question_version SET plain_text = 'tampered' WHERE id = ?").param(versionId(q, 1)).update())
                .hasMessageContaining("returning it to draft");
        // and a review whose hash does not match the content
        assertThatThrownBy(() -> ownerJdbc().sql("""
                INSERT INTO question_review (id, tenant_id, version_id, reviewer_id, decision, reviewed_hash) VALUES (?, ?, ?, ?, 'REVIEWED', 'stale')""")
                .params(UUID.randomUUID(), org.tenant, versionId(q, 1), designer.id()).update()).hasMessageContaining("does not match");
    }

    @Test
    void changesRequestedNeedAReasonAndReturnToTheAuthor() throws Exception {
        UUID q = create(standardBank(), faculty, mcq("Needs work", "A"));
        submit(q, faculty);
        review(q, designer, "CHANGES_REQUESTED", null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("reason_required"));
        review(q, designer, "CHANGES_REQUESTED", "Option B is ambiguous").andExpect(status().isOk()).andExpect(jsonPath("$.current.state").value("DRAFT"));
        JsonNode t = call(get("/api/v1/questions/" + q + "/transitions"), faculty, status().isOk());
        assertThat(t.toString()).contains("Option B is ambiguous");
    }

    @Test
    void approvedVersionsAreImmutableAndEditsCreateNewVersions() throws Exception {
        UUID bank = standardBank();
        UUID q = approved(bank, faculty, designer, controller, numeric("Original numeric", "19.62"));
        UUID v1 = versionId(q, 1);
        String v1Text = ownerJdbc().sql("SELECT plain_text FROM question_version WHERE id = ?").param(v1).query(String.class).single();
        // no direct edit of an approved version
        update(q, faculty, numeric("Edited numeric", "19.6")).andExpect(status().isConflict());
        assertThatThrownBy(() -> ownerJdbc().sql("UPDATE question_version SET plain_text = 'tampered' WHERE id = ?").param(v1).update())
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> ownerJdbc().sql("UPDATE question_answer_key SET key_hash = 'x' WHERE version_id = ?").param(v1).update())
                .hasMessageContaining("cannot change");
        assertThatThrownBy(() -> ownerJdbc().sql("DELETE FROM question_version WHERE id = ?").param(v1).update()).hasMessageContaining("cannot be deleted");
        // new version
        JsonNode nv = call(post("/api/v1/questions/" + q + "/versions"), faculty, status().isCreated());
        assertThat(nv.path("current").path("versionNo").asInt()).isEqualTo(2);
        assertThat(nv.path("approved").path("versionNo").asInt()).isEqualTo(1);
        call(post("/api/v1/questions/" + q + "/versions"), faculty, status().isConflict());   // only one open version
        update(q, faculty, numeric("Edited numeric", "19.6")).andExpect(status().isOk());
        submit(q, faculty);
        review(q, designer, "REVIEWED", null).andExpect(status().isOk());
        approve(q, controller).andExpect(status().isOk()).andExpect(jsonPath("$.current.versionNo").value(2));
        assertThat(ownerJdbc().sql("SELECT state FROM question_version WHERE id = ?").param(v1).query(String.class).single()).isEqualTo("SUPERSEDED");
        assertThat(ownerJdbc().sql("SELECT plain_text FROM question_version WHERE id = ?").param(v1).query(String.class).single()).isEqualTo(v1Text);
        // version 1 stays readable exactly as approved
        call(get("/api/v1/questions/" + q + "/versions/1"), controller, status().isOk());
        assertThat(call(get("/api/v1/questions/" + q + "/versions/1"), controller, status().isOk()).path("approvedBy").asString())
                .isEqualTo(controller.id().toString());
    }

    @Test
    void referencedVersionsArePreservedThroughSupersedeAndRetire() throws Exception {
        UUID bank = standardBank();
        UUID q = approved(bank, faculty, designer, controller, mcq("Used in an assessment", "A"));
        UUID v1 = versionId(q, 1);
        TenantContext.runInTenant(org.tenant, () -> references.reference(v1, "assessment", "demo-assessment-1"));
        assertThat(TenantContext.callInTenant(org.tenant, () -> references.referenceCount(v1))).isEqualTo(1);
        // supersede by version 2
        call(post("/api/v1/questions/" + q + "/versions"), faculty, status().isCreated());
        update(q, faculty, mcq("Used in an assessment (v2)", "B")).andExpect(status().isOk());
        submit(q, faculty);
        review(q, designer, "REVIEWED", null).andExpect(status().isOk());
        approve(q, controller).andExpect(status().isOk());
        // retire
        perform(post("/api/v1/questions/" + q + "/retire").content("{}"), controller).andExpect(status().isBadRequest());
        perform(post("/api/v1/questions/" + q + "/retire").content("{\"reason\":\"Syllabus changed\"}"), controller).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RETIRED"));
        // the referenced version is intact, cannot be deleted, and the reference is permanent
        assertThat(ownerJdbc().sql("SELECT state FROM question_version WHERE id = ?").param(v1).query(String.class).single()).isEqualTo("SUPERSEDED");
        assertThatThrownBy(() -> ownerJdbc().sql("DELETE FROM question_version WHERE id = ?").param(v1).update()).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> ownerJdbc().sql("DELETE FROM question_version_reference WHERE version_id = ?").param(v1).update())
                .hasMessageContaining("permanent");
        assertThatThrownBy(() -> ownerJdbc().sql("DELETE FROM question WHERE id = ?").param(q).update()).isInstanceOf(Exception.class);
        // drafts cannot be referenced
        UUID draft = create(bank, faculty, mcq("Draft only", "A"));
        assertThatThrownBy(() -> TenantContext.runInTenant(org.tenant, () -> references.reference(versionId(draft, 1), "assessment", "x")))
                .hasMessageContaining("approved");
        // retired questions cannot get new versions or be approved again
        call(post("/api/v1/questions/" + q + "/versions"), faculty, status().isConflict());
    }

    @Test
    void historyIsAuditedWithoutContentOrKeyValues() throws Exception {
        UUID q = approved(standardBank(), faculty, designer, controller, mcq("History question with secret stem", "C"));
        JsonNode h = call(get("/api/v1/questions/" + q + "/history"), controller, status().isOk());
        assertThat(h.toString()).contains("question.created").contains("question.submitted").contains("question.reviewed").contains("question.approved")
                .doesNotContain("secret stem").doesNotContain("\"correct\"");
    }
}
