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
 * (KI-92: the attempt heartbeat), extra time under decision R-2 (designated holders of exam.attempt.extend only, 1–60
 * minutes with a reason, permanent records with previous and new expiry, forward only, within the window, a second person
 * where the examination requires it), termination requested by an invigilator and decided by a different holder of the privileged
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
    void extraTimeIsForDesignatedHoldersRecordedPermanentlyForwardOnlyWithinTheWindowAndReachesTheCandidate() throws Exception {
        TestOrg.Account proctor = org.account("EXAM_PROCTOR", college);
        UUID attempt = startAttempt(asha, codes.get(0));
        UUID candidate = ownerJdbc().sql("SELECT candidate_id FROM exam_attempt WHERE id = ?").param(attempt).query(UUID.class).single();
        String enrolment = row(call(get(base() + "/monitor"), proctor, status().isOk()), "Asha").path("enrolmentId").asString();
        Instant before = Instant.parse(delivery(post(portal("/attempts/" + attempt + "/pulse")), asha, 200).path("deadlineAt").asString());

        // designated holders only: an administrator who schedules examinations does not hold it automatically; faculty neither
        perform(post(base() + "/candidates/" + enrolment + "/extensions").content("{\"minutes\":10,\"reason\":\"Power cut in hall B\"}"), instAdmin)
                .andExpect(status().isForbidden());
        perform(post(base() + "/candidates/" + enrolment + "/extensions").content("{\"minutes\":10,\"reason\":\"Power cut in hall B\"}"), faculty)
                .andExpect(status().isForbidden());
        assertThat(call(get(base() + "/monitor"), instAdmin, status().isOk()).path("canExtend").asBoolean()).isFalse();
        assertThat(call(get(base() + "/monitor"), proctor, status().isOk()).path("canExtend").asBoolean()).isTrue();
        // a reason, 1–60 minutes at a time
        perform(post(base() + "/candidates/" + enrolment + "/extensions").content("{\"minutes\":61,\"reason\":\"x\"}"), controller).andExpect(status().isBadRequest());
        perform(post(base() + "/candidates/" + enrolment + "/extensions").content("{\"minutes\":0,\"reason\":\"x\"}"), controller).andExpect(status().isBadRequest());
        perform(post(base() + "/candidates/" + enrolment + "/extensions").content("{\"minutes\":5,\"reason\":\" \"}"), controller).andExpect(status().isBadRequest());

        // the invigilator grants 10 minutes directly (no second person on this examination)
        JsonNode ext = call(post(base() + "/candidates/" + enrolment + "/extensions").content("{\"minutes\":10,\"reason\":\"Power cut in hall B\"}"), proctor,
                status().isOk());
        assertThat(ext.path("status").asString()).isEqualTo("APPLIED");
        assertThat(Instant.parse(ext.path("previousDeadline").asString())).isEqualTo(before);
        assertThat(Instant.parse(ext.path("deadlineAt").asString())).isEqualTo(before.plusSeconds(600));
        // the candidate's heartbeat carries the new expiry (the exam timer follows it), the monitor shows it to staff
        JsonNode pulse = delivery(post(portal("/attempts/" + attempt + "/pulse")), asha, 200);
        assertThat(Instant.parse(pulse.path("deadlineAt").asString())).isEqualTo(before.plusSeconds(600));
        assertThat(pulse.path("extendedMinutes").asInt()).isEqualTo(10);
        JsonNode r = row(call(get(base() + "/monitor"), controller, status().isOk()), "Asha");
        assertThat(r.path("extendedMinutes").asInt()).isEqualTo(10);
        assertThat(Instant.parse(r.path("deadlineAt").asString())).isEqualTo(before.plusSeconds(600));

        // every further grant is its own record; never beyond the window (3 hours, 45-minute paper, 10 added): 60 + 60 fit, 10 more do not
        call(post(base() + "/candidates/" + enrolment + "/extensions").content("{\"minutes\":60,\"reason\":\"Scribe arrived late\"}"), controller, status().isOk());
        call(post(base() + "/candidates/" + enrolment + "/extensions").content("{\"minutes\":60,\"reason\":\"Second power cut\"}"), controller, status().isOk());
        perform(post(base() + "/candidates/" + enrolment + "/extensions").content("{\"minutes\":10,\"reason\":\"more\"}"), controller).andExpect(status().isConflict());

        // the history: candidate, examination, actor, time, previous and new expiry of each grant, chained, visible to staff
        JsonNode history = call(get(base() + "/candidates/" + enrolment + "/extensions"), proctor, status().isOk());
        assertThat(history.size()).isEqualTo(3);
        assertThat(history.get(0).path("minutes").asInt()).isEqualTo(10);
        assertThat(history.get(0).path("reason").asString()).isEqualTo("Power cut in hall B");
        assertThat(history.get(0).path("grantedByName").asString()).isNotBlank();
        assertThat(history.get(0).path("approvedByName").asString("")).isEmpty();   // granted directly: no approver
        for (int i = 0; i < 3; i++) {
            JsonNode h = history.get(i);
            assertThat(Instant.parse(h.path("newExpiry").asString())).isEqualTo(Instant.parse(h.path("previousExpiry").asString()).plusSeconds(60L * h.path("minutes").asInt()));
            if (i > 0) assertThat(h.path("previousExpiry").asString()).isEqualTo(history.get(i - 1).path("newExpiry").asString());
        }
        assertThat(Instant.parse(history.get(2).path("newExpiry").asString())).isEqualTo(before.plusSeconds(130 * 60));
        assertThat(ownerJdbc().sql("SELECT count(*) FROM exam_attempt_extension WHERE attempt_id = ? AND schedule_id = ? AND candidate_id = ?")
                .params(attempt, schedule, candidate).query(Long.class).single()).isEqualTo(3);
        perform(get(base() + "/candidates/" + enrolment + "/extensions"), otherController).andExpect(status().isNotFound());

        // the database: the deadline never moves back or by itself; grants are permanent and must describe the attempt as it is
        assertThatThrownBy(() -> ownerJdbc().sql("UPDATE exam_attempt SET deadline_at = deadline_at - interval '5 minutes' WHERE id = ?").param(attempt).update())
                .hasMessageContaining("only forward");
        assertThatThrownBy(() -> ownerJdbc().sql("UPDATE exam_attempt SET deadline_at = deadline_at + interval '5 minutes', extended_minutes = extended_minutes + 5 WHERE id = ?")
                .param(attempt).update()).hasMessageContaining("only forward");
        assertThatThrownBy(() -> ownerJdbc().sql("DELETE FROM exam_attempt_extension").update()).hasMessageContaining("permanent");
        assertThatThrownBy(() -> ownerJdbc().sql("UPDATE exam_attempt_extension SET minutes = 1").update()).hasMessageContaining("permanent");
        assertThatThrownBy(() -> ownerJdbc().sql("""
                INSERT INTO exam_attempt_extension (id, tenant_id, attempt_id, schedule_id, candidate_id, minutes, reason, granted_by, previous_deadline, new_deadline)
                SELECT ?, tenant_id, id, schedule_id, candidate_id, 1, 'forged', ?, deadline_at - interval '1 hour', deadline_at - interval '59 minutes'
                FROM exam_attempt WHERE id = ?""").params(UUID.randomUUID(), controller.id(), attempt).update())
                .hasMessageContaining("current expiry");
        // audited with candidate, examination, actor (the event's actor), previous and new expiry; never the reason
        assertThat(audits("exam.attempt.extended")).isEqualTo(3);
        JsonNode first = JSON.readTree(ownerJdbc().sql("SELECT details::text FROM audit_event WHERE tenant_id = ? AND action = 'exam.attempt.extended' ORDER BY occurred_at LIMIT 1")
                .param(org.tenant).query(String.class).single());
        assertThat(first.path("candidate").asString()).isEqualTo(candidate.toString());
        assertThat(first.path("schedule").asString()).isEqualTo(schedule.toString());
        assertThat(Instant.parse(first.path("previousExpiry").asString())).isEqualTo(before);
        assertThat(Instant.parse(first.path("newExpiry").asString())).isEqualTo(before.plusSeconds(600));
        assertThat(ownerJdbc().sql("SELECT count(*) FROM audit_event WHERE tenant_id = ? AND action = 'exam.attempt.extended' AND details::text ILIKE '%power cut%'")
                .param(org.tenant).query(Long.class).single()).isZero();
        assertThat(ownerJdbc().sql("SELECT count(DISTINCT actor_id) FROM audit_event WHERE tenant_id = ? AND action = 'exam.attempt.extended'")
                .param(org.tenant).query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void anInstitutionCanRequireASecondPersonForExtraTimeOnAHighStakesExamination() throws Exception {
        TestOrg.Account proctor = org.account("EXAM_PROCTOR", college);
        UUID attempt = startAttempt(asha, codes.get(0));
        String enrolment = row(call(get(base() + "/monitor"), proctor, status().isOk()), "Asha").path("enrolmentId").asString();
        Instant before = ownerJdbc().sql("SELECT deadline_at FROM exam_attempt WHERE id = ?").param(attempt).query(java.sql.Timestamp.class).single().toInstant();

        // the policy: set by whoever manages the schedule; tightening is allowed while it runs, relaxing is not
        perform(put(base() + "/extra-time-policy").content("{\"secondPerson\":true}"), proctor).andExpect(status().isForbidden());
        assertThat(call(put(base() + "/extra-time-policy").content("{\"secondPerson\":true}"), controller, status().isOk())
                .path("extraTimeSecondPerson").asBoolean()).isTrue();
        assertThat(audits("exam.schedule.extra_time_policy_changed")).isEqualTo(1);
        assertThat(call(get(base() + "/monitor"), proctor, status().isOk()).path("extraTimeSecondPerson").asBoolean()).isTrue();

        // a grant now waits: nothing changes until a different holder approves
        JsonNode ext = call(post(base() + "/candidates/" + enrolment + "/extensions").content("{\"minutes\":15,\"reason\":\"Fire alarm, hall evacuated\"}"),
                proctor, status().isOk());
        assertThat(ext.path("status").asString()).isEqualTo("PENDING_APPROVAL");
        String approval = ext.path("approvalId").asString();
        assertThat(ownerJdbc().sql("SELECT deadline_at FROM exam_attempt WHERE id = ?").param(attempt).query(java.sql.Timestamp.class).single().toInstant()).isEqualTo(before);
        assertThat(row(call(get(base() + "/monitor"), controller, status().isOk()), "Asha").path("extensionPending").asBoolean()).isTrue();
        perform(post(base() + "/candidates/" + enrolment + "/extensions").content("{\"minutes\":5,\"reason\":\"again\"}"), proctor).andExpect(status().isConflict());

        // not by the person who granted it, not by an administrator without the right
        perform(post("/api/v1/approvals/" + approval + "/decision").content("{\"approve\":true,\"reason\":\"self\"}"), proctor).andExpect(status().is4xxClientError());
        perform(post("/api/v1/approvals/" + approval + "/decision").content("{\"approve\":true,\"reason\":\"admin\"}"), instAdmin).andExpect(status().is4xxClientError());
        assertThat(ownerJdbc().sql("SELECT count(*) FROM exam_attempt_extension WHERE attempt_id = ?").param(attempt).query(Long.class).single()).isZero();

        // the exam controller approves: applied, recorded with both people
        call(post("/api/v1/approvals/" + approval + "/decision").content("{\"approve\":true,\"reason\":\"Confirmed\"}"), controller, status().isOk());
        assertThat(Instant.parse(delivery(post(portal("/attempts/" + attempt + "/pulse")), asha, 200).path("deadlineAt").asString())).isEqualTo(before.plusSeconds(900));
        JsonNode h = call(get(base() + "/candidates/" + enrolment + "/extensions"), proctor, status().isOk()).get(0);
        assertThat(h.path("approvedByName").asString()).isNotBlank().isNotEqualTo(h.path("grantedByName").asString());
        assertThat(ownerJdbc().sql("SELECT granted_by = ? AND approved_by = ? AND approval_id IS NOT NULL FROM exam_attempt_extension WHERE attempt_id = ?")
                .params(proctor.id(), controller.id(), attempt).query(Boolean.class).single()).isTrue();

        // a rejected grant changes nothing
        String second = call(post(base() + "/candidates/" + enrolment + "/extensions").content("{\"minutes\":5,\"reason\":\"Slow computer\"}"), proctor,
                status().isOk()).path("approvalId").asString();
        call(post("/api/v1/approvals/" + second + "/decision").content("{\"approve\":false,\"reason\":\"Replace the computer instead\"}"), controller, status().isOk());
        assertThat(ownerJdbc().sql("SELECT count(*) FROM exam_attempt_extension WHERE attempt_id = ?").param(attempt).query(Long.class).single()).isEqualTo(1);
        assertThat(audits("exam.attempt.extension_rejected")).isEqualTo(1);

        // the database refuses an unapproved or self-approved grant on this examination, and relaxing the rule after opening
        assertThatThrownBy(() -> ownerJdbc().sql("""
                INSERT INTO exam_attempt_extension (id, tenant_id, attempt_id, schedule_id, candidate_id, minutes, reason, granted_by, previous_deadline, new_deadline)
                SELECT ?, tenant_id, id, schedule_id, candidate_id, 1, 'unapproved', ?, deadline_at, deadline_at + interval '1 minute' FROM exam_attempt WHERE id = ?""")
                .params(UUID.randomUUID(), proctor.id(), attempt).update()).hasMessageContaining("second person");
        assertThatThrownBy(() -> ownerJdbc().sql("""
                INSERT INTO exam_attempt_extension (id, tenant_id, attempt_id, schedule_id, candidate_id, minutes, reason, granted_by, approved_by, approval_id,
                                                    previous_deadline, new_deadline)
                SELECT ?, tenant_id, id, schedule_id, candidate_id, 1, 'self', ?, ?, ?, deadline_at, deadline_at + interval '1 minute' FROM exam_attempt WHERE id = ?""")
                .params(UUID.randomUUID(), proctor.id(), proctor.id(), UUID.randomUUID(), attempt).update())
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        perform(put(base() + "/extra-time-policy").content("{\"secondPerson\":false}"), controller).andExpect(status().isConflict());
        assertThatThrownBy(() -> ownerJdbc().sql("UPDATE exam_schedule SET extra_time_second_person = false WHERE id = ?").param(schedule).update())
                .hasMessageContaining("cannot be relaxed");
        // termination keeps its own independent approval (unchanged by R-2)
        assertThat(ownerJdbc().sql("SELECT 'exam.attempt.terminate' = ANY (privileged_permissions()) AND 'exam.attempt.extend' = ANY (privileged_permissions())")
                .query(Boolean.class).single()).isTrue();
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
        // the proctor role holds exam.invigilate and the designated extra-time right (R-2), and no schedule or result right
        assertThat(ownerJdbc().sql("SELECT array_to_string(array(SELECT p FROM unnest(permissions) p WHERE p LIKE 'exam.%' ORDER BY p), ',') FROM role WHERE system_role AND code = 'EXAM_PROCTOR'")
                .query(String.class).single()).isEqualTo("exam.attempt.extend,exam.invigilate");
        // extra time: designated controllers and invigilators only, never administrators automatically
        assertThat(ownerJdbc().sql("SELECT string_agg(code, ',' ORDER BY code) FROM role WHERE system_role AND 'exam.attempt.extend' = ANY (permissions)")
                .query(String.class).single()).isEqualTo("EXAM_CONTROLLER,EXAM_PROCTOR");
        assertThat(ownerJdbc().sql("SELECT 'exam.attempt.terminate' = ANY (privileged_permissions())").query(Boolean.class).single()).isTrue();
        // controllers and administrators monitor too
        call(get(base() + "/monitor"), controller, status().isOk());
        call(get(base() + "/monitor"), instAdmin, status().isOk());
        // another organization sees nothing
        perform(get(base() + "/monitor"), otherController).andExpect(status().isNotFound());
    }
}
