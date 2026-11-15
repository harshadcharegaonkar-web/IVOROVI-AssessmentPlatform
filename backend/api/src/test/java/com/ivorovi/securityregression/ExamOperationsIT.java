package com.ivorovi.securityregression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ivorovi.support.TestOrg;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Day 5 examination operations through the real APIs: attendance and live state, connection interruptions while writing
 * (KI-92: the attempt heartbeat), extra time by an exam controller (append-only, forward only, within the window, never by an
 * invigilator), termination requested by an invigilator and decided by a different holder of the privileged
 * exam.attempt.terminate, and the separation of invigilator, controller, reviewer and approver permissions.
 */
class ExamOperationsIT extends DeliveryJourneySupport {

    JsonNode row(JsonNode monitor, String name) {
        for (JsonNode c : monitor.path("candidates")) if (c.path("displayName").asString().equals(name)) return c;
        throw new AssertionError("no " + name);
    }

    String base() {
        return "/api/v1/exam/schedules/" + schedule;
    }

    @Test
    void extraTimeIsForControllersOnlyForwardOnlyWithinTheWindowAndReachesTheCandidate() throws Exception {
        TestOrg.Account proctor = org.account("EXAM_PROCTOR", college);
        UUID attempt = startAttempt(asha, codes.get(0));
        String enrolment = row(call(get(base() + "/monitor"), proctor, status().isOk()), "Asha").path("enrolmentId").asString();
        Instant before = Instant.parse(delivery(post(portal("/attempts/" + attempt + "/pulse")), asha, 200).path("deadlineAt").asString());

        // invigilators cannot extend; controllers can, with a reason, 1–60 minutes at a time
        perform(post(base() + "/candidates/" + enrolment + "/extensions").content("{\"minutes\":10,\"reason\":\"Power cut in hall B\"}"), proctor)
                .andExpect(status().isForbidden());
        perform(post(base() + "/candidates/" + enrolment + "/extensions").content("{\"minutes\":61,\"reason\":\"x\"}"), controller).andExpect(status().isBadRequest());
        perform(post(base() + "/candidates/" + enrolment + "/extensions").content("{\"minutes\":5,\"reason\":\" \"}"), controller).andExpect(status().isBadRequest());
        JsonNode ext = call(post(base() + "/candidates/" + enrolment + "/extensions").content("{\"minutes\":10,\"reason\":\"Power cut in hall B\"}"), controller,
                status().isOk());
        assertThat(ext.path("extendedMinutes").asInt()).isEqualTo(10);
        // the candidate's heartbeat carries the new deadline (the exam timer follows it)
        JsonNode pulse = delivery(post(portal("/attempts/" + attempt + "/pulse")), asha, 200);
        assertThat(Instant.parse(pulse.path("deadlineAt").asString())).isEqualTo(before.plusSeconds(600));
        assertThat(pulse.path("extendedMinutes").asInt()).isEqualTo(10);
        assertThat(row(call(get(base() + "/monitor"), proctor, status().isOk()), "Asha").path("extendedMinutes").asInt()).isEqualTo(10);

        // never beyond the window (open for 3 hours, 45-minute paper, 10 already added): 60 + 60 fit, 10 more do not
        call(post(base() + "/candidates/" + enrolment + "/extensions").content("{\"minutes\":60,\"reason\":\"Scribe arrived late\"}"), controller, status().isOk());
        call(post(base() + "/candidates/" + enrolment + "/extensions").content("{\"minutes\":60,\"reason\":\"Second power cut\"}"), controller, status().isOk());
        perform(post(base() + "/candidates/" + enrolment + "/extensions").content("{\"minutes\":10,\"reason\":\"more\"}"), controller).andExpect(status().isConflict());

        // the database: the deadline never moves back, never by itself, extensions are permanent
        assertThatThrownBy(() -> ownerJdbc().sql("UPDATE exam_attempt SET deadline_at = deadline_at - interval '5 minutes' WHERE id = ?").param(attempt).update())
                .hasMessageContaining("only forward");
        assertThatThrownBy(() -> ownerJdbc().sql("UPDATE exam_attempt SET deadline_at = deadline_at + interval '5 minutes', extended_minutes = extended_minutes + 5 WHERE id = ?")
                .param(attempt).update()).hasMessageContaining("only forward");
        assertThatThrownBy(() -> ownerJdbc().sql("DELETE FROM exam_attempt_extension").update()).hasMessageContaining("permanent");
        // audited with minutes, never the reason
        assertThat(audits("exam.attempt.extended")).isEqualTo(3);
        assertThat(ownerJdbc().sql("SELECT count(*) FROM audit_event WHERE tenant_id = ? AND action = 'exam.attempt.extended' AND details::text ILIKE '%power cut%'")
                .param(org.tenant).query(Long.class).single()).isZero();
    }

    @Test
    void terminationIsRequestedByAnInvigilatorAndDecidedByAnotherPersonWithTheRight() throws Exception {
        TestOrg.Account proctor = org.account("EXAM_PROCTOR", college);
        UUID attempt = startAttempt(asha, codes.get(0));
        delivery(put(portal("/attempts/" + attempt + "/answers/1")).content("{\"seq\":1,\"response\":{\"value\":\"A\"}}"), asha, 200);
        String enrolment = row(call(get(base() + "/monitor"), proctor, status().isOk()), "Asha").path("enrolmentId").asString();

        // who may ask: invigilators (and controllers); not faculty; a reason is required
        perform(post(base() + "/candidates/" + enrolment + "/termination").content("{\"reason\":\"Phone use\"}"), faculty).andExpect(status().isForbidden());
        perform(post(base() + "/candidates/" + enrolment + "/termination").content("{\"reason\":\"\"}"), proctor).andExpect(status().isBadRequest());
        String approval = call(post(base() + "/candidates/" + enrolment + "/termination").content("{\"reason\":\"Phone use after two warnings\"}"), proctor,
                status().isAccepted()).path("approvalId").asString();
        perform(post(base() + "/candidates/" + enrolment + "/termination").content("{\"reason\":\"again\"}"), proctor).andExpect(status().isConflict());
        assertThat(row(call(get(base() + "/monitor"), proctor, status().isOk()), "Asha").path("terminationPending").asBoolean()).isTrue();

        // who may not decide: the requester, an administrator without the right; the candidate keeps writing meanwhile
        perform(post("/api/v1/approvals/" + approval + "/decision").content("{\"approve\":true,\"reason\":\"self\"}"), proctor).andExpect(status().is4xxClientError());
        perform(post("/api/v1/approvals/" + approval + "/decision").content("{\"approve\":true,\"reason\":\"admin\"}"), instAdmin).andExpect(status().is4xxClientError());
        delivery(put(portal("/attempts/" + attempt + "/answers/2")).content("{\"seq\":1,\"response\":{\"value\":\"B\"}}"), asha, 200);

        // an exam controller decides: the attempt ends, answers are kept, nothing more is accepted, it is scored
        call(post("/api/v1/approvals/" + approval + "/decision").content("{\"approve\":true,\"reason\":\"Confirmed with the hall supervisor\"}"), controller,
                status().isOk());
        assertThat(ownerJdbc().sql("SELECT status FROM exam_attempt WHERE id = ?").param(attempt).query(String.class).single()).isEqualTo("TERMINATED");
        assertThat(ownerJdbc().sql("SELECT count(*) FROM exam_answer WHERE attempt_id = ?").param(attempt).query(Long.class).single()).isEqualTo(2);
        delivery(put(portal("/attempts/" + attempt + "/answers/3")).content("{\"seq\":1,\"response\":{\"value\":\"A\"}}"), asha, 409);
        assertThat(delivery(post(portal("/attempts/" + attempt + "/pulse")), asha, 200).path("status").asString()).isEqualTo("TERMINATED");
        assertThat(delivery(get(portal("/exams")), asha, 200).findValuesAsString("availability")).contains("FINISHED");
        assertThat(row(call(get(base() + "/monitor"), proctor, status().isOk()), "Asha").path("state").asString()).isEqualTo("TERMINATED");
        scoring.scoreDue();
        assertThat(ownerJdbc().sql("SELECT status FROM exam_score WHERE attempt_id = ?").param(attempt).query(String.class).single()).isEqualTo("PROVISIONAL");

        // audited; the requester can never be the decider, also on a direct write
        assertThat(audits("exam.attempt.termination_requested")).isEqualTo(1);
        assertThat(audits("exam.attempt.terminated")).isEqualTo(1);
        UUID requester = ownerJdbc().sql("SELECT requested_by FROM exam_attempt_termination WHERE attempt_id = ?").param(attempt).query(UUID.class).single();
        assertThatThrownBy(() -> ownerJdbc().sql("INSERT INTO exam_attempt_termination (id, tenant_id, attempt_id, reason, requested_by, status, decided_by, decided_at) VALUES (?, ?, ?, 'x', ?, 'APPROVED', ?, now())")
                .params(UUID.randomUUID(), org.tenant, attempt, requester, requester).update()).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void aRejectedTerminationLeavesTheCandidateWriting() throws Exception {
        TestOrg.Account proctor = org.account("EXAM_PROCTOR", college);
        UUID attempt = startAttempt(bilal, codes.get(1));
        String enrolment = row(call(get(base() + "/monitor"), proctor, status().isOk()), "Bilal").path("enrolmentId").asString();
        String approval = call(post(base() + "/candidates/" + enrolment + "/termination").content("{\"reason\":\"Talking\"}"), proctor, status().isAccepted())
                .path("approvalId").asString();
        call(post("/api/v1/approvals/" + approval + "/decision").content("{\"approve\":false,\"reason\":\"A warning is enough\"}"), controller, status().isOk());
        assertThat(ownerJdbc().sql("SELECT status FROM exam_attempt WHERE id = ?").param(attempt).query(String.class).single()).isEqualTo("IN_PROGRESS");
        delivery(put(portal("/attempts/" + attempt + "/answers/1")).content("{\"seq\":1,\"response\":{\"value\":\"A\"}}"), bilal, 200);
        assertThat(audits("exam.attempt.termination_rejected")).isEqualTo(1);
    }

    @Test
    void connectionInterruptionsWhileWritingAreVisibleToInvigilators() throws Exception {
        TestOrg.Account proctor = org.account("EXAM_PROCTOR", college);
        UUID attempt = startAttempt(asha, codes.get(0));
        delivery(post(portal("/attempts/" + attempt + "/pulse")), asha, 200);
        JsonNode a = row(call(get(base() + "/monitor"), proctor, status().isOk()), "Asha");
        assertThat(a.path("online").asBoolean()).isTrue();
        assertThat(a.path("interruptions").asInt()).isZero();
        // the candidate's device went quiet for 75 s (simulated on the presence row), then the heartbeat returns
        UUID candidate = ownerJdbc().sql("SELECT candidate_id FROM exam_attempt WHERE id = ?").param(attempt).query(UUID.class).single();
        ownerJdbc().sql("UPDATE delivery_presence SET last_seen_at = now() - interval '75 seconds' WHERE candidate_id = ?").param(candidate).update();
        assertThat(row(call(get(base() + "/monitor"), proctor, status().isOk()), "Asha").path("online").asBoolean()).isFalse();
        delivery(post(portal("/attempts/" + attempt + "/pulse")), asha, 200);
        a = row(call(get(base() + "/monitor"), proctor, status().isOk()), "Asha");
        assertThat(a.path("online").asBoolean()).isTrue();
        assertThat(a.path("interruptions").asInt()).isEqualTo(1);
        assertThat(a.path("interruptedSeconds").asLong()).isBetween(70L, 90L);
    }

    @Test
    void invigilatorControllerReviewerAndApproverPermissionsStaySeparate() throws Exception {
        TestOrg.Account proctor = org.account("EXAM_PROCTOR", college);
        String results = "/api/v1/exam/results/" + schedule;
        startAttempt(asha, codes.get(0));
        // the invigilator: monitors, records incidents, asks for termination; nothing else
        call(get(base() + "/monitor"), proctor, status().isOk());
        perform(get(base() + "/enrolments"), proctor).andExpect(status().isForbidden());
        perform(post(base() + "/cancel").content("{\"reason\":\"no\"}"), proctor).andExpect(status().isForbidden());
        perform(get(results), proctor).andExpect(status().isForbidden());
        perform(post(results + "/reviews").content("{}"), proctor).andExpect(status().isForbidden());
        // the system roles: who holds what
        assertThat(ownerJdbc().sql("SELECT string_agg(code, ',' ORDER BY code) FROM role WHERE system_role AND 'exam.attempt.terminate' = ANY (permissions)")
                .query(String.class).single()).isEqualTo("EXAM_CONTROLLER");
        assertThat(ownerJdbc().sql("SELECT string_agg(code, ',' ORDER BY code) FROM role WHERE system_role AND 'exam.invigilate' = ANY (permissions)")
                .query(String.class).single()).isEqualTo("EXAM_CONTROLLER,EXAM_PROCTOR,INSTITUTION_ADMIN,ORG_ADMIN");
        // the proctor role holds exam.invigilate and no other examination, schedule or result right
        assertThat(ownerJdbc().sql("SELECT array_to_string(array(SELECT p FROM unnest(permissions) p WHERE p LIKE 'exam.%' ORDER BY p), ',') FROM role WHERE system_role AND code = 'EXAM_PROCTOR'")
                .query(String.class).single()).isEqualTo("exam.invigilate");
        assertThat(ownerJdbc().sql("SELECT 'exam.attempt.terminate' = ANY (privileged_permissions())").query(Boolean.class).single()).isTrue();
        // controllers and administrators monitor too
        call(get(base() + "/monitor"), controller, status().isOk());
        call(get(base() + "/monitor"), instAdmin, status().isOk());
        // another organization sees nothing
        perform(get(base() + "/monitor"), otherController).andExpect(status().isNotFound());
    }
}
