package com.ivorovi.securityregression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ivorovi.support.TestOrg;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import tools.jackson.databind.JsonNode;

/**
 * Day 4 P3: invigilation. A proctor (EXAM_PROCTOR, exam.invigilate) follows every enrolled candidate live through the real
 * delivery API (not linked → checked in → in progress with answered count → submitted), records permanent incidents, and
 * never sees answers, keys, scores or the unmasked e-mails; staff without the permission, other organizations and
 * candidates are refused.
 */
class InvigilationIT extends DeliveryJourneySupport {

    JsonNode candidate(JsonNode monitor, String name) {
        for (JsonNode c : monitor.path("candidates")) if (c.path("displayName").asString().equals(name)) return c;
        throw new AssertionError("no candidate " + name + " in " + monitor);
    }

    @Test
    void aProctorFollowsCandidatesLiveAndRecordsPermanentIncidents() throws Exception {
        TestOrg.Account proctor = org.account("EXAM_PROCTOR", college);
        String monitorUrl = "/api/v1/exam/schedules/" + schedule + "/monitor";

        // the proctor finds the schedule, but not the enrolment list with full e-mails
        assertThat(call(get("/api/v1/exam/schedules"), proctor, status().isOk()).findValuesAsString("id")).contains(schedule.toString());
        call(get("/api/v1/exam/schedules/" + schedule), proctor, status().isOk());
        perform(get("/api/v1/exam/schedules/" + schedule + "/enrolments"), proctor).andExpect(status().isForbidden());

        // before anyone signs in
        JsonNode m = call(get(monitorUrl), proctor, status().isOk());
        assertThat(m.path("candidates").size()).isEqualTo(2);
        assertThat(candidate(m, "Asha").path("state").asString()).isEqualTo("NOT_LINKED");
        assertThat(candidate(m, "Asha").path("email").asString()).startsWith("a***@").doesNotContain(asha.email());

        // Asha checks in and starts, answers one question; Bilal only checks in
        UUID attempt = startAttempt(asha, codes.get(0));
        delivery(put(portal("/attempts/" + attempt + "/answers/1")).content("{\"seq\":1,\"response\":{\"value\":\"A\"}}"), asha, 200);
        delivery(get(portal("/me")), bilal, 200);
        delivery(get(portal("/exams")), bilal, 200);   // as the exam app does right after sign-in (links the enrolment)
        delivery(post(portal("/check-ins")).content("{\"code\":\"" + codes.get(1) + "\"}"), bilal, 200);
        m = call(get(monitorUrl), proctor, status().isOk());
        JsonNode a = candidate(m, "Asha");
        assertThat(a.path("state").asString()).isEqualTo("IN_PROGRESS");
        assertThat(a.path("questions").asInt()).isEqualTo(4);
        assertThat(a.path("answered").asInt()).isEqualTo(1);
        assertThat(a.path("secondsLeft").asLong()).isPositive();
        assertThat(candidate(m, "Bilal").path("state").asString()).isEqualTo("CHECKED_IN");
        assertThat(m.path("counts").path("IN_PROGRESS").asInt()).isEqualTo(1);
        assertThat(m.path("counts").path("CHECKED_IN").asInt()).isEqualTo(1);
        // never answers, keys or scores, never a full e-mail
        assertThat(m.toString()).doesNotContain("\"response\"").doesNotContain("answerKey").doesNotContain("awarded").doesNotContain(asha.email())
                .doesNotContain(bilal.email());

        // incidents: recorded by the proctor, permanent, corrections refer to the same candidate; audit without the note
        UUID ashaEnrolment = UUID.fromString(a.path("enrolmentId").asString());
        UUID bilalEnrolment = UUID.fromString(candidate(m, "Bilal").path("enrolmentId").asString());
        JsonNode inc = call(post("/api/v1/exam/schedules/" + schedule + "/candidate-incidents").content("""
                {"enrolmentId":"%s","category":"suspected_malpractice","note":"Phone visible on the desk at 10:42"}""".formatted(ashaEnrolment)),
                proctor, status().isCreated());
        assertThat(inc.path("category").asString()).isEqualTo("SUSPECTED_MALPRACTICE");
        perform(post("/api/v1/exam/schedules/" + schedule + "/candidate-incidents").content("""
                {"enrolmentId":"%s","category":"OTHER","note":"Wrong person","correctsId":"%s"}""".formatted(bilalEnrolment, inc.path("id").asString())), proctor)
                .andExpect(status().isBadRequest());
        perform(post("/api/v1/exam/schedules/" + schedule + "/candidate-incidents").content("""
                {"enrolmentId":"%s","category":"GOSSIP","note":"x"}""".formatted(ashaEnrolment)), proctor).andExpect(status().isBadRequest());
        call(post("/api/v1/exam/schedules/" + schedule + "/candidate-incidents").content("""
                {"enrolmentId":"%s","category":"OTHER","note":"Correction: it was a calculator","correctsId":"%s"}""".formatted(ashaEnrolment,
                inc.path("id").asString())), proctor, status().isCreated());
        assertThat(call(get("/api/v1/exam/schedules/" + schedule + "/candidate-incidents"), proctor, status().isOk()).size()).isEqualTo(2);
        assertThat(candidate(call(get(monitorUrl), proctor, status().isOk()), "Asha").path("incidents").asInt()).isEqualTo(2);
        assertThatThrownBy(() -> ownerJdbc().sql("UPDATE exam_candidate_incident SET note = 'changed'").update()).hasMessageContaining("permanent");
        assertThatThrownBy(() -> ownerJdbc().sql("DELETE FROM exam_candidate_incident").update()).hasMessageContaining("permanent");
        assertThat(audits("exam.candidate_incident.recorded")).isEqualTo(2);
        assertThat(ownerJdbc().sql("SELECT count(*) FROM audit_event WHERE tenant_id = ? AND action = 'exam.candidate_incident.recorded' AND (details::text ILIKE '%phone%' OR details::text ILIKE '%calculator%')")
                .param(org.tenant).query(Long.class).single()).isZero();

        // who may not: faculty, another organization's controller, a candidate
        perform(get(monitorUrl), faculty).andExpect(status().isForbidden());
        perform(get(monitorUrl), otherController).andExpect(status().isNotFound());
        perform(post("/api/v1/exam/schedules/" + schedule + "/candidate-incidents").content("""
                {"enrolmentId":"%s","category":"OTHER","note":"x"}""".formatted(ashaEnrolment)), otherController).andExpect(status().isNotFound());
        mvc.perform(get(monitorUrl).header(HttpHeaders.AUTHORIZATION, asha.bearer())).andExpect(status().isUnauthorized());

        // submission shows on the monitor; exam controllers and administrators invigilate too
        delivery(post(portal("/attempts/" + attempt + "/submit")).content("{\"submitKey\":\"k1\"}"), asha, 200);
        assertThat(candidate(call(get(monitorUrl), controller, status().isOk()), "Asha").path("state").asString()).isEqualTo("SUBMITTED");
        call(get(monitorUrl), instAdmin, status().isOk());
    }
}
