package com.ivorovi.securityregression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.support.TestOrg;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/**
 * Adversarial review of the Day 5 examination operations (QA, Day 6): termination four-eyes and extra time, attacked over
 * the API (self-approval, wrong role, other institution, other organization, after submission, replay, payload tampering,
 * concurrency) and directly in the database as the application role (row-level security active, tenant bound), which is
 * what a SQL injection or a defect in application code could reach. Complements {@code ExamOperationsIT}.
 * Fixture: {@link DeliveryJourneySupport} (schedule open for 3 hours, 45-minute paper, Asha and Bilal enrolled).
 */
@Tag("security-regression")
class ExamOperationsAdversarialIT extends DeliveryJourneySupport {

    @Autowired JdbcClient app;

    private String base() {
        return "/api/v1/exam/schedules/" + schedule;
    }

    private String enrolment(TestOrg.Account who, String name) throws Exception {
        for (JsonNode c : call(get(base() + "/monitor"), who, status().isOk()).path("candidates")) {
            if (c.path("displayName").asString().equals(name)) return c.path("enrolmentId").asString();
        }
        throw new AssertionError("no " + name);
    }

    private String requestTermination(TestOrg.Account by, String enrolment) throws Exception {
        return call(post(base() + "/candidates/" + enrolment + "/termination").content("{\"reason\":\"Phone use after two warnings\"}"), by, status().isAccepted())
                .path("approvalId").asString();
    }

    private org.springframework.test.web.servlet.ResultActions decide(TestOrg.Account by, String approval, boolean approve) throws Exception {
        return perform(post("/api/v1/approvals/" + approval + "/decision").content("{\"approve\":" + approve + ",\"reason\":\"QA decision\"}"), by);
    }

    private String attemptStatus(UUID attempt) {
        return ownerJdbc().sql("SELECT status FROM exam_attempt WHERE id = ?").param(attempt).query(String.class).single();
    }

    private <T> T asApp(java.util.function.Supplier<T> work) {
        return TenantContext.callInTenant(org.tenant, work);
    }

    // ================================================================================================ termination over the API

    @Test
    void nobodyOutsideTheInstitutionOrWithoutTheRightCanDecideAndAControllerCannotDecideTheirOwnRequest() throws Exception {
        TestOrg.Account proctor = org.account("EXAM_PROCTOR", college);
        UUID attempt = startAttempt(asha, codes.get(0));
        String approval = requestTermination(proctor, enrolment(proctor, "Asha"));
        // the proctor (requester), another college's controller (holds the right, wrong unit), an administrator, faculty,
        // another organization's controller: none of them ends the examination
        TestOrg other = new TestOrg(ownerJdbc(), mvc, "ops-other");
        for (TestOrg.Account who : List.of(proctor, otherController, instAdmin, admin, faculty, other.account("EXAM_CONTROLLER", null))) {
            decide(who, approval, true).andExpect(r -> assertThat(r.getResponse().getStatus()).as("decider %s", who.id()).isIn(403, 404, 409));
        }
        assertThat(attemptStatus(attempt)).isEqualTo("IN_PROGRESS");

        // a controller who asks for a termination themselves cannot be the one who decides it
        UUID bilals = startAttempt(bilal, codes.get(1));
        String own = requestTermination(controller, enrolment(controller, "Bilal"));
        decide(controller, own, true).andExpect(status().isConflict());
        assertThat(attemptStatus(bilals)).isEqualTo("IN_PROGRESS");
        decide(controller2, own, true).andExpect(status().isOk());
        assertThat(attemptStatus(bilals)).isEqualTo("TERMINATED");
    }

    @Test
    void anApprovalAfterTheCandidateSubmittedChangesNothingAndCannotBeReplayed() throws Exception {
        TestOrg.Account proctor = org.account("EXAM_PROCTOR", college);
        UUID attempt = startAttempt(asha, codes.get(0));
        String approval = requestTermination(proctor, enrolment(proctor, "Asha"));
        JsonNode submitted = delivery(post(portal("/attempts/" + attempt + "/submit")).content("{}"), asha, 200);
        decide(controller, approval, true).andExpect(r -> assertThat(r.getResponse().getStatus()).isIn(409, 422));
        assertThat(attemptStatus(attempt)).isEqualTo("SUBMITTED");
        assertThat(ownerJdbc().sql("SELECT submitted_at::text FROM exam_attempt WHERE id = ?").param(attempt).query(String.class).single()).isNotNull();
        assertThat(ownerJdbc().sql("SELECT status FROM exam_attempt_termination WHERE attempt_id = ?").param(attempt).query(String.class).single()).isNotEqualTo("APPROVED");
        assertThat(audits("exam.attempt.terminated")).isZero();
        assertThat(submitted.path("status").asString()).isEqualTo("SUBMITTED");

        // a normal termination, then replays of the same decision: one termination, one event
        UUID bilals = startAttempt(bilal, codes.get(1));
        String second = requestTermination(proctor, enrolment(proctor, "Bilal"));
        decide(controller, second, true).andExpect(status().isOk());
        decide(controller, second, true).andExpect(status().isConflict());
        decide(controller2, second, true).andExpect(status().isConflict());
        decide(controller2, second, false).andExpect(status().isConflict());
        assertThat(attemptStatus(bilals)).isEqualTo("TERMINATED");
        assertThat(audits("exam.attempt.terminated")).isEqualTo(1);
        assertThat(ownerJdbc().sql("SELECT count(*) FROM outbox_event WHERE tenant_id = ? AND event_type = 'exam.attempt.submitted' AND aggregate_id = ?")
                .params(org.tenant, bilals.toString()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void concurrentApprovalsOfOneTerminationApplyExactlyOnce() throws Exception {
        TestOrg.Account proctor = org.account("EXAM_PROCTOR", college);
        UUID attempt = startAttempt(asha, codes.get(0));
        String approval = requestTermination(proctor, enrolment(proctor, "Asha"));
        List<Integer> codesSeen = together(8, i -> decide(i % 2 == 0 ? controller : controller2, approval, true).andReturn().getResponse().getStatus());
        assertThat(codesSeen.stream().filter(c -> c == 200).count()).isEqualTo(1);
        assertThat(codesSeen).allMatch(c -> c == 200 || c == 409);
        assertThat(attemptStatus(attempt)).isEqualTo("TERMINATED");
        assertThat(audits("exam.attempt.terminated")).isEqualTo(1);
    }

    @Test
    void aTamperedApprovalPayloadCannotRedirectTheTerminationToAnotherCandidate() throws Exception {
        TestOrg.Account proctor = org.account("EXAM_PROCTOR", college);
        UUID ashas = startAttempt(asha, codes.get(0));
        UUID bilals = startAttempt(bilal, codes.get(1));
        String approval = requestTermination(proctor, enrolment(proctor, "Asha"));
        // the payload is display data: rewrite it to point at Bilal's attempt (owner connection, i.e. a database-level attacker)
        ownerJdbc().sql("UPDATE approval_request SET payload = jsonb_set(payload, '{attemptId}', to_jsonb(?::text)) WHERE id = ?::uuid")
                .params(bilals.toString(), approval).update();
        decide(controller, approval, true).andExpect(status().isOk());
        assertThat(attemptStatus(ashas)).isEqualTo("TERMINATED");
        assertThat(attemptStatus(bilals)).isEqualTo("IN_PROGRESS");
        // and the request row itself cannot be pointed at another attempt, even as the application role
        UUID requestId = ownerJdbc().sql("SELECT id FROM exam_attempt_termination WHERE attempt_id = ?").param(ashas).query(UUID.class).single();
        assertThatThrownBy(() -> asApp(() -> app.sql("UPDATE exam_attempt_termination SET attempt_id = ? WHERE id = ?").params(bilals, requestId).update()))
                .hasMessageContaining("changes only by its decision");
    }

    // ================================================================================================ termination in the database

    @Test
    void theApplicationRoleCannotDeleteOrRewriteTerminationRequestsOrDecideOneForTheRequester() throws Exception {
        TestOrg.Account proctor = org.account("EXAM_PROCTOR", college);
        UUID attempt = startAttempt(asha, codes.get(0));
        requestTermination(proctor, enrolment(proctor, "Asha"));
        UUID id = ownerJdbc().sql("SELECT id FROM exam_attempt_termination WHERE attempt_id = ?").param(attempt).query(UUID.class).single();
        assertThatThrownBy(() -> asApp(() -> app.sql("DELETE FROM exam_attempt_termination WHERE id = ?").param(id).update())).rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> asApp(() -> app.sql("UPDATE exam_attempt_termination SET reason = 'edited' WHERE id = ?").param(id).update()))
                .hasMessageContaining("changes only by its decision");
        assertThatThrownBy(() -> asApp(() -> app.sql("UPDATE exam_attempt_termination SET status = 'APPROVED', decided_by = requested_by, decided_at = now() WHERE id = ?")
                .param(id).update())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        // another organization's context sees no termination rows at all
        TestOrg other = new TestOrg(ownerJdbc(), mvc, "ops-rls");
        assertThat(TenantContext.callInTenant(other.tenant, () -> app.sql("SELECT count(*) FROM exam_attempt_termination").query(Long.class).single())).isZero();
        assertThat(attemptStatus(attempt)).isEqualTo("IN_PROGRESS");
    }

    /**
     * QA-D6-1: the database does not tie a termination to its four-eyes approval. As the application role, a pending request
     * can be set to APPROVED by any id other than the requester (no approval, attempt still running), and an attempt can be set
     * to TERMINATED with no approved request. The result path has this guard (a release needs a PUBLISHED review);
     * termination does not. Both tests below assert the secure behaviour and fail on the current schema (verified 2026-11-15).
     */
    @org.junit.jupiter.api.Disabled("QA-D6-1: fails until the database ties termination to its four-eyes approval — enable with the fix")
    @Test
    void theApplicationRoleCannotMarkATerminationApprovedWithoutAnApproval() throws Exception {
        TestOrg.Account proctor = org.account("EXAM_PROCTOR", college);
        UUID attempt = startAttempt(asha, codes.get(0));
        requestTermination(proctor, enrolment(proctor, "Asha"));
        UUID id = ownerJdbc().sql("SELECT id FROM exam_attempt_termination WHERE attempt_id = ?").param(attempt).query(UUID.class).single();
        assertThatThrownBy(() -> asApp(() -> app.sql("UPDATE exam_attempt_termination SET status = 'APPROVED', decided_by = ?, decided_at = now() WHERE id = ?")
                .params(UUID.randomUUID(), id).update())).as("a termination marked approved without an approval").isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @org.junit.jupiter.api.Disabled("QA-D6-1: fails until the database ties termination to its four-eyes approval — enable with the fix")
    @Test
    void theApplicationRoleCannotEndAnAttemptAsTerminatedWithoutAnApprovedRequest() throws Exception {
        UUID attempt = startAttempt(asha, codes.get(0));
        assertThatThrownBy(() -> asApp(() -> app.sql("UPDATE exam_attempt SET status = 'TERMINATED', submitted_at = now() WHERE id = ?").param(attempt).update()))
                .as("an attempt terminated without an approved request").isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    // ================================================================================================ extra time

    @Test
    void extraTimeRefusesZeroNegativeAfterSubmissionOtherInstitutionsAndOtherOrganizations() throws Exception {
        UUID attempt = startAttempt(asha, codes.get(0));
        String asha = enrolment(controller, "Asha");
        for (String minutes : List.of("0", "-5", "61", "1000")) {
            perform(post(base() + "/candidates/" + asha + "/extensions").content("{\"minutes\":" + minutes + ",\"reason\":\"QA\"}"), controller)
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).as("minutes %s", minutes).isEqualTo(400));
        }
        perform(post(base() + "/candidates/" + asha + "/extensions").content("{\"minutes\":5,\"reason\":\"QA\"}"), otherController)
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isIn(403, 404));
        TestOrg other = new TestOrg(ownerJdbc(), mvc, "ops-ext");
        perform(post(base() + "/candidates/" + asha + "/extensions").content("{\"minutes\":5,\"reason\":\"QA\"}"), other.account("EXAM_CONTROLLER", null))
                .andExpect(status().isNotFound());
        delivery(post(portal("/attempts/" + attempt + "/submit")).content("{}"), this.asha, 200);
        perform(post(base() + "/candidates/" + asha + "/extensions").content("{\"minutes\":5,\"reason\":\"QA\"}"), controller).andExpect(status().isConflict());
        assertThat(ownerJdbc().sql("SELECT count(*) FROM exam_attempt_extension WHERE attempt_id = ?").param(attempt).query(Long.class).single()).isZero();
        assertThat(ownerJdbc().sql("SELECT extended_minutes FROM exam_attempt WHERE id = ?").param(attempt).query(Integer.class).single()).isZero();
    }

    @Test
    void concurrentExtensionsNeverPassTheWindow() throws Exception {
        UUID attempt = startAttempt(asha, codes.get(0));
        String asha = enrolment(controller, "Asha");
        // window 3 h, paper 45 min: about 134 minutes are left, so at most two 60-minute extensions fit
        List<Integer> statuses = together(8, i -> perform(post(base() + "/candidates/" + asha + "/extensions")
                .content("{\"minutes\":60,\"reason\":\"Parallel request " + i + "\"}"), i % 2 == 0 ? controller : controller2).andReturn().getResponse().getStatus());
        assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(2);
        assertThat(statuses).allMatch(s -> s == 200 || s == 409);
        var row = ownerJdbc().sql("SELECT extended_minutes, deadline_at <= (SELECT closes_at FROM exam_schedule WHERE id = ?) FROM exam_attempt WHERE id = ?")
                .params(schedule, attempt).query((rs, n) -> List.of(rs.getInt(1), rs.getBoolean(2))).single();
        assertThat(row).containsExactly(120, true);
        assertThat(ownerJdbc().sql("SELECT count(*) FROM exam_attempt_extension WHERE attempt_id = ?").param(attempt).query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void theApplicationRoleCannotMoveADeadlineWithoutARecordOrRewriteExtensions() throws Exception {
        UUID attempt = startAttempt(asha, codes.get(0));
        UUID other = startAttempt(bilal, codes.get(1));
        // no record: refused
        assertThatThrownBy(() -> asApp(() -> app.sql("UPDATE exam_attempt SET deadline_at = deadline_at + interval '30 minutes', extended_minutes = 30 WHERE id = ?")
                .param(attempt).update())).hasMessageContaining("only forward");
        // a record for another attempt does not count for this one
        asApp(() -> app.sql("INSERT INTO exam_attempt_extension (id, tenant_id, attempt_id, minutes, reason, granted_by) VALUES (?, ?, ?, 30, 'QA', ?)")
                .params(UUID.randomUUID(), org.tenant, other, controller.id()).update());
        assertThatThrownBy(() -> asApp(() -> app.sql("UPDATE exam_attempt SET deadline_at = deadline_at + interval '30 minutes', extended_minutes = 30 WHERE id = ?")
                .param(attempt).update())).hasMessageContaining("only forward");
        // out-of-range records are refused by CHECKs; existing records cannot be changed or removed
        for (int minutes : new int[] {0, -10, 61}) {
            assertThatThrownBy(() -> asApp(() -> app.sql("INSERT INTO exam_attempt_extension (id, tenant_id, attempt_id, minutes, reason, granted_by) VALUES (?, ?, ?, ?, 'QA', ?)")
                    .params(UUID.randomUUID(), org.tenant, attempt, minutes, controller.id()).update())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        }
        assertThatThrownBy(() -> asApp(() -> app.sql("UPDATE exam_attempt_extension SET minutes = 60 WHERE attempt_id = ?").param(other).update()))
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> asApp(() -> app.sql("DELETE FROM exam_attempt_extension WHERE attempt_id = ?").param(other).update()))
                .rootCause().hasMessageContaining("permission denied");
        // after submission the deadline never moves, even with a matching record
        delivery(post(portal("/attempts/" + attempt + "/submit")).content("{}"), asha, 200);
        asApp(() -> app.sql("INSERT INTO exam_attempt_extension (id, tenant_id, attempt_id, minutes, reason, granted_by) VALUES (?, ?, ?, 10, 'QA', ?)")
                .params(UUID.randomUUID(), org.tenant, attempt, controller.id()).update());
        assertThatThrownBy(() -> asApp(() -> app.sql("UPDATE exam_attempt SET deadline_at = deadline_at + interval '10 minutes', extended_minutes = 10 WHERE id = ?")
                .param(attempt).update())).hasMessageContaining("only forward");
    }

    // ================================================================================================ helpers

    interface Work<T> {
        T run(int i) throws Exception;
    }

    private static <T> List<T> together(int n, Work<T> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<T>> fs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int k = i;
                fs.add(pool.submit(() -> {
                    go.await();
                    return work.run(k);
                }));
            }
            go.countDown();
            List<T> out = new ArrayList<>();
            for (Future<T> f : fs) out.add(f.get());
            return out;
        } finally {
            pool.shutdown();
        }
    }
}
