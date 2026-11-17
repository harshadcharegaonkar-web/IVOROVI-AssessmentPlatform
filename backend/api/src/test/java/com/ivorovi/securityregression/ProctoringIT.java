package com.ivorovi.securityregression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.proctoring.internal.ProctoringService;
import com.ivorovi.proctoring.internal.ProctoringService.CandidateAttempt;
import com.ivorovi.proctoring.internal.ProctoringService.SignalInput;
import com.ivorovi.support.TestOrg;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Add-on B (Session 4): proctoring on the existing examination engine. Candidate-side calls go through the service with
 * an attempt verified here the way the delivery layer will (exams.CandidateAttempts, pending from Session A); staff
 * calls go through the real API. Covers consent before capture, rules that only raise flags, decisions by people
 * (warning to the candidate through the pulse, incident, four-eyes termination request), evidence access and audit,
 * retention, immutability, tenant isolation and that evidence can never be an API-client scope.
 */
class ProctoringIT extends DeliveryJourneySupport {

    @Autowired ProctoringService proctoring;

    static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0, 1, (byte) 0xFF, (byte) 0xD9};

    void level(String level) {
        // the fixture's window is already open (the API refuses changes then), so the setting is placed directly
        ownerJdbc().sql("INSERT INTO proctoring_setting (tenant_id, schedule_id, level, snapshot_interval_s, retention_days, consent_version, updated_by) "
                + "VALUES (?, ?, ?, 60, 7, ?, ?)").params(org.tenant, schedule, level, ProctoringService.CONSENT_VERSION, controller.id()).update();
    }

    CandidateAttempt attemptOf(UUID attempt) {
        return ownerJdbc().sql("""
                SELECT a.id, a.schedule_id, e.id, a.status FROM exam_attempt a JOIN exam_enrolment e ON e.schedule_id = a.schedule_id AND e.candidate_id = a.candidate_id
                WHERE a.id = ?""").param(attempt).query((rs, n) -> new CandidateAttempt(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getString(4))).single();
    }

    <T> T inTenant(java.util.function.Supplier<T> work) {
        return TenantContext.callInTenant(org.tenant, work);
    }

    CandidateAttempt consented(TestOrg.Account unused, Candidate who, String code) throws Exception {
        CandidateAttempt a = attemptOf(startAttempt(who, code));
        var view = inTenant(() -> proctoring.candidateView(a));
        inTenant(() -> proctoring.consent(a, true, view.consentHash()));
        return a;
    }

    void signal(CandidateAttempt a, String kind, int secondsAgo) {
        inTenant(() -> proctoring.signals(a, List.of(new SignalInput(kind, Instant.now().minusSeconds(secondsAgo), null))));
    }

    long count(String sql, Object... params) {
        return ownerJdbc().sql(sql).params(params).query(Long.class).single();
    }

    // ================================================================================================ consent

    @Test
    void nothingIsCapturedBeforeConsentAndADeclineIsRecorded() throws Exception {
        level("SNAPSHOTS");
        CandidateAttempt a = attemptOf(startAttempt(asha, codes.get(0)));
        var view = inTenant(() -> proctoring.candidateView(a));
        assertThat(view.level()).isEqualTo("SNAPSHOTS");
        assertThat(view.consentDraft()).isTrue();
        assertThat(view.consentText()).contains("Nothing is decided automatically").contains("No audio, no video");
        assertThat(view.consent()).isNull();

        // signals and snapshots are refused without consent, by the service and by the database
        assertThatThrownBy(() -> signal(a, "FOCUS_LOST", 1)).hasMessageContaining("consent");
        assertThatThrownBy(() -> inTenant(() -> proctoring.snapshot(a, JPEG, Instant.now()))).hasMessageContaining("consent");
        assertThatThrownBy(() -> ownerJdbc().sql("INSERT INTO proctoring_signal (id, tenant_id, attempt_id, kind, client_at) VALUES (?, ?, ?, 'COPY', now())")
                .params(UUID.randomUUID(), org.tenant, a.attemptId()).update()).hasMessageContaining("consent");

        // the hash must match exactly the text shown
        assertThatThrownBy(() -> inTenant(() -> proctoring.consent(a, true, "0".repeat(64)))).hasMessageContaining("notice has changed");
        inTenant(() -> proctoring.consent(a, true, view.consentHash()));
        assertThatThrownBy(() -> inTenant(() -> proctoring.consent(a, false, view.consentHash()))).hasMessageContaining("already recorded");
        assertThat(audits("proctoring.consent_given")).isEqualTo(1);

        // Bilal declines: recorded, nothing captured, visible in the summary; no automatic consequence
        CandidateAttempt b = attemptOf(startAttempt(bilal, codes.get(1)));
        inTenant(() -> proctoring.consent(b, false, view.consentHash()));
        assertThatThrownBy(() -> signal(b, "COPY", 1)).hasMessageContaining("consent");
        JsonNode summary = call(get("/api/v1/proctoring/schedules/" + schedule + "/summary"), controller, status().isOk());
        assertThat(summary.path("consentsGiven").asInt()).isEqualTo(1);
        assertThat(summary.path("consentsDeclined").asInt()).isEqualTo(1);
        assertThat(ownerJdbc().sql("SELECT status FROM exam_attempt WHERE id = ?").param(b.attemptId()).query(String.class).single()).isEqualTo("IN_PROGRESS");
        // consent records are permanent
        assertThatThrownBy(() -> ownerJdbc().sql("UPDATE proctoring_consent SET decision = 'GIVEN' WHERE attempt_id = ?").param(b.attemptId()).update())
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> ownerJdbc().sql("DELETE FROM proctoring_consent WHERE attempt_id = ?").param(b.attemptId()).update()).isInstanceOf(Exception.class);
    }

    @Test
    void anUnproctoredExaminationCollectsNothing() throws Exception {
        CandidateAttempt a = attemptOf(startAttempt(asha, codes.get(0)));
        assertThat(inTenant(() -> proctoring.candidateView(a)).level()).isEqualTo("NONE");
        assertThatThrownBy(() -> inTenant(() -> proctoring.consent(a, true, "x"))).hasMessageContaining("not proctored");
        assertThatThrownBy(() -> signal(a, "COPY", 1)).hasMessageContaining("not proctored");
    }

    // ================================================================================================ rules and decisions

    @Test
    void rulesRaiseFlagsAndOnlyPeopleDecide() throws Exception {
        level("BASIC");
        TestOrg.Account proctor = org.account("EXAM_PROCTOR", college);
        CandidateAttempt a = consented(null, asha, codes.get(0));
        // two focus losses: nothing yet; a third within five minutes: one MEDIUM flag; a second display: one HIGH flag
        signal(a, "FOCUS_LOST", 120);
        signal(a, "TAB_HIDDEN", 90);
        assertThat(call(get("/api/v1/proctoring/schedules/" + schedule + "/flags"), proctor, status().isOk()).size()).isZero();
        signal(a, "FOCUS_LOST", 30);
        signal(a, "MULTI_DISPLAY", 10);
        signal(a, "FOCUS_LOST", 5);
        JsonNode flags = call(get("/api/v1/proctoring/schedules/" + schedule + "/flags"), proctor, status().isOk());
        assertThat(flags.size()).isEqualTo(2);
        assertThat(flags.get(0).path("rule").asString()).isEqualTo("MULTI_DISPLAY");              // high severity first
        assertThat(flags.get(0).path("severity").asString()).isEqualTo("HIGH");
        assertThat(flags.get(1).path("rule").asString()).isEqualTo("AWAY_REPEATED");
        assertThat(flags.get(1).path("signalCount").asInt()).isEqualTo(4);
        assertThat(flags.get(1).path("candidateName").asString()).isEqualTo("Asha");
        assertThat(audits("proctoring.flag_raised")).isEqualTo(2);
        // a flag changes nothing on its own: the attempt still runs
        assertThat(ownerJdbc().sql("SELECT status FROM exam_attempt WHERE id = ?").param(a.attemptId()).query(String.class).single()).isEqualTo("IN_PROGRESS");

        // the reviewer sees the timeline, never answers
        String away = flags.get(1).path("id").asString();
        String multi = flags.get(0).path("id").asString();
        JsonNode detail = call(get("/api/v1/proctoring/flags/" + away), proctor, status().isOk());
        assertThat(detail.path("signals").size()).isEqualTo(5);
        assertThat(detail.path("evidenceVisible").asBoolean()).isFalse();
        assertThat(detail.toString()).doesNotContain("\"response\"").doesNotContain("answerKey").doesNotContain(asha.email());

        // decisions need a reason; a warning needs the text the candidate sees
        perform(post("/api/v1/proctoring/flags/" + away + "/decisions").content("{\"decision\":\"WARN\",\"reason\":\"Repeated absence\"}"), proctor)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("warning_required"));
        call(post("/api/v1/proctoring/flags/" + away + "/decisions").content("""
                {"decision":"WARN","reason":"Repeated absence from the examination window","warningText":"Please keep the examination window open and in front."}"""),
                proctor, status().isOk());
        perform(post("/api/v1/proctoring/flags/" + away + "/decisions").content("{\"decision\":\"DISMISS\",\"reason\":\"x\"}"), proctor)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("flag_decided"));
        // the candidate sees the warning with the next heartbeat
        JsonNode pulse = delivery(post(portal("/attempts/" + a.attemptId() + "/pulse")), asha, 200);
        assertThat(pulse.path("notices").get(0).path("text").asString()).isEqualTo("Please keep the examination window open and in front.");
        assertThat(pulse.toString()).doesNotContain("MULTI_DISPLAY").doesNotContain("AWAY_REPEATED");     // never flags themselves

        // the high flag: the proctor asks for termination; the attempt keeps running until someone else decides (four-eyes)
        JsonNode t = call(post("/api/v1/proctoring/flags/" + multi + "/decisions").content("""
                {"decision":"TERMINATION_REQUESTED","reason":"Second display connected during the examination"}"""), proctor, status().isOk());
        String approval = t.path("flag").path("decision").path("approvalId").asString();
        assertThat(approval).isNotBlank();
        assertThat(ownerJdbc().sql("SELECT status FROM approval_request WHERE id = ?::uuid").param(approval).query(String.class).single()).isEqualTo("PENDING");
        assertThat(ownerJdbc().sql("SELECT status FROM exam_attempt WHERE id = ?").param(a.attemptId()).query(String.class).single()).isEqualTo("IN_PROGRESS");
        perform(post("/api/v1/approvals/" + approval + "/decision").content("{\"approve\":true,\"reason\":\"own\"}"), proctor).andExpect(status().is4xxClientError());
        call(post("/api/v1/approvals/" + approval + "/decision").content("{\"approve\":true,\"reason\":\"Confirmed with the hall invigilator\"}"), controller,
                status().isOk());
        assertThat(ownerJdbc().sql("SELECT status FROM exam_attempt WHERE id = ?").param(a.attemptId()).query(String.class).single()).isEqualTo("TERMINATED");
        assertThat(audits("proctoring.flag_decided")).isEqualTo(2);

        // decisions and signals are permanent
        assertThatThrownBy(() -> ownerJdbc().sql("UPDATE proctoring_flag_decision SET reason = 'x'").update()).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> ownerJdbc().sql("DELETE FROM proctoring_signal WHERE attempt_id = ?").param(a.attemptId()).update()).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> ownerJdbc().sql("UPDATE proctoring_flag SET status = 'OPEN' WHERE id = ?::uuid").param(away).update()).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> ownerJdbc().sql("UPDATE proctoring_flag SET severity = 'LOW' WHERE id = ?::uuid").param(multi).update()).isInstanceOf(Exception.class);
        // a terminated attempt sends nothing more
        assertThatThrownBy(() -> inTenant(() -> proctoring.signals(attemptOf(a.attemptId()), List.of(new SignalInput("COPY", Instant.now(), null)))))
                .hasMessageContaining("ended");
    }

    @Test
    void anIncidentDecisionRecordsAPermanentCandidateIncident() throws Exception {
        level("BASIC");
        CandidateAttempt a = consented(null, asha, codes.get(0));
        signal(a, "PASTE", 3);
        String flag = call(get("/api/v1/proctoring/schedules/" + schedule + "/flags?status=OPEN"), controller, status().isOk()).get(0).path("id").asString();
        JsonNode d = call(post("/api/v1/proctoring/flags/" + flag + "/decisions").content("""
                {"decision":"INCIDENT","reason":"Pasted text into an answer"}"""), controller, status().isOk());
        String incident = d.path("flag").path("decision").path("incidentId").asString();
        assertThat(ownerJdbc().sql("SELECT category FROM exam_candidate_incident WHERE id = ?::uuid").param(incident).query(String.class).single())
                .isEqualTo("SUSPECTED_MALPRACTICE");
        assertThat(ownerJdbc().sql("SELECT enrolment_id FROM exam_candidate_incident WHERE id = ?::uuid").param(incident).query(UUID.class).single())
                .isEqualTo(a.enrolmentId());
        assertThat(call(get("/api/v1/proctoring/schedules/" + schedule + "/flags?status=OPEN"), controller, status().isOk()).size()).isZero();
    }

    // ================================================================================================ evidence

    @Test
    void snapshotsAreEvidenceForControllersOnlyAndAreDeletedAfterRetention() throws Exception {
        level("SNAPSHOTS");
        TestOrg.Account proctor = org.account("EXAM_PROCTOR", college);
        CandidateAttempt a = consented(null, asha, codes.get(0));
        assertThatThrownBy(() -> inTenant(() -> proctoring.snapshot(a, "not an image".getBytes(), Instant.now()))).hasMessageContaining("JPEG");
        assertThatThrownBy(() -> inTenant(() -> proctoring.snapshot(a, new byte[300 * 1024], Instant.now()))).hasMessageContaining("200 KB");
        var snap = inTenant(() -> proctoring.snapshot(a, JPEG, Instant.now().minusSeconds(5)));
        assertThat(snap.expiresAt()).isAfter(Instant.now().plus(Duration.ofDays(6)));            // the schedule keeps evidence 7 days

        signal(a, "CAMERA_UNAVAILABLE", 2);
        String flag = call(get("/api/v1/proctoring/schedules/" + schedule + "/flags"), proctor, status().isOk()).get(0).path("id").asString();
        assertThat(count("SELECT count(*) FROM proctoring_snapshot WHERE attempt_id = ? AND hold", a.attemptId())).isEqualTo(1);   // held while a flag is open
        JsonNode detail = call(get("/api/v1/proctoring/flags/" + flag), controller, status().isOk());
        assertThat(detail.path("evidenceVisible").asBoolean()).isTrue();
        assertThat(detail.path("snapshots").get(0).path("available").asBoolean()).isTrue();

        // a proctor reviews flags but never sees images; a controller does, audited, never cached
        perform(get("/api/v1/proctoring/snapshots/" + snap.id() + "/image"), proctor).andExpect(status().isForbidden());
        var image = perform(get("/api/v1/proctoring/snapshots/" + snap.id() + "/image"), controller).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(image.getHeader("Cache-Control")).contains("no-store");
        assertThat(image.getContentAsByteArray()).isEqualTo(JPEG);
        assertThat(audits("proctoring.evidence_viewed")).isEqualTo(1);
        // another institution's controller does not even learn that the snapshot exists
        perform(get("/api/v1/proctoring/snapshots/" + snap.id() + "/image"), otherController).andExpect(status().isNotFound());

        // dismissing the only flag releases the hold; after expiry the image is deleted and the record says so
        call(post("/api/v1/proctoring/flags/" + flag + "/decisions").content("{\"decision\":\"DISMISS\",\"reason\":\"Camera permission prompt; fixed\"}"),
                proctor, status().isOk());
        assertThat(count("SELECT count(*) FROM proctoring_snapshot WHERE attempt_id = ? AND hold", a.attemptId())).isZero();
        assertThat(inTenant(() -> proctoring.deleteExpired(Instant.now()))).isZero();              // not yet expired
        assertThat(inTenant(() -> proctoring.deleteExpired(Instant.now().plus(Duration.ofDays(8))))).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM proctoring_snapshot WHERE id = ? AND deleted_at IS NOT NULL", snap.id())).isEqualTo(1);
        perform(get("/api/v1/proctoring/snapshots/" + snap.id() + "/image"), controller).andExpect(status().isGone());
        assertThat(audits("proctoring.evidence_deleted")).isEqualTo(1);
        // the record cannot be removed or revived
        assertThatThrownBy(() -> ownerJdbc().sql("DELETE FROM proctoring_snapshot WHERE id = ?").param(snap.id()).update()).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> ownerJdbc().sql("UPDATE proctoring_snapshot SET deleted_at = NULL WHERE id = ?").param(snap.id()).update()).isInstanceOf(Exception.class);
    }

    @Test
    void anEscalatedFlagKeepsTheEvidence() throws Exception {
        level("SNAPSHOTS");
        CandidateAttempt a = consented(null, asha, codes.get(0));
        inTenant(() -> proctoring.snapshot(a, JPEG, Instant.now()));
        signal(a, "MULTI_DISPLAY", 1);
        String flag = call(get("/api/v1/proctoring/schedules/" + schedule + "/flags"), controller, status().isOk()).get(0).path("id").asString();
        call(post("/api/v1/proctoring/flags/" + flag + "/decisions").content("{\"decision\":\"INCIDENT\",\"reason\":\"Second display\"}"), controller, status().isOk());
        assertThat(inTenant(() -> proctoring.deleteExpired(Instant.now().plus(Duration.ofDays(30))))).isZero();
    }

    // ================================================================================================ settings, permissions, isolation

    @Test
    void settingsChangeOnlyBeforeTheWindowOpensAndOnlyByConfigurers() throws Exception {
        // the fixture's window is open: refused
        perform(put("/api/v1/proctoring/schedules/" + schedule + "/settings").content("{\"level\":\"BASIC\"}"), controller)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("window_open"));
        // a schedule tomorrow: the controller sets it; a proctor may read but not change it; a designer cannot even read it
        String version = ownerJdbc().sql("SELECT version_id FROM exam_schedule WHERE id = ?").param(schedule).query(String.class).single();
        String portal = ownerJdbc().sql("SELECT id FROM exam_portal WHERE portal_key = ?").param(portalKey).query(String.class).single();
        String later = call(post("/api/v1/exam/schedules").content("""
                {"versionId":"%s","portalId":"%s","orgUnitId":"%s","opensAt":"%s","closesAt":"%s"}""".formatted(version, portal, college,
                Instant.now().plus(Duration.ofDays(1)), Instant.now().plus(Duration.ofDays(1)).plus(Duration.ofHours(3)))), controller, status().isCreated())
                .path("id").asString();
        TestOrg.Account proctor = org.account("EXAM_PROCTOR", college);
        JsonNode s = call(get("/api/v1/proctoring/schedules/" + later + "/settings"), controller, status().isOk());
        assertThat(s.path("level").asString()).isEqualTo("NONE");
        assertThat(s.path("editable").asBoolean()).isTrue();
        s = call(put("/api/v1/proctoring/schedules/" + later + "/settings").content("""
                {"level":"SNAPSHOTS","snapshotIntervalSeconds":90,"retentionDays":14,"version":-1}"""), controller, status().isOk());
        assertThat(s.path("level").asString()).isEqualTo("SNAPSHOTS");
        perform(put("/api/v1/proctoring/schedules/" + later + "/settings").content("{\"level\":\"NONE\",\"version\":-1}"), controller)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("concurrent_modification"));
        perform(put("/api/v1/proctoring/schedules/" + later + "/settings").content("{\"level\":\"SNAPSHOTS\",\"retentionDays\":365,\"version\":0}"), controller)
                .andExpect(status().isBadRequest());
        assertThat(call(get("/api/v1/proctoring/schedules/" + later + "/settings"), proctor, status().isOk()).path("editable").asBoolean()).isFalse();
        perform(put("/api/v1/proctoring/schedules/" + later + "/settings").content("{\"level\":\"NONE\",\"version\":0}"), proctor).andExpect(status().isForbidden());
        perform(get("/api/v1/proctoring/schedules/" + later + "/flags"), designer).andExpect(status().isForbidden());
        perform(get("/api/v1/proctoring/schedules/" + later + "/settings"), otherController).andExpect(status().isNotFound());
        assertThat(audits("proctoring.configured")).isEqualTo(1);
    }

    @Test
    void proctoringDataStaysInsideItsOrganizationAndEvidenceIsNeverAnApiClientScope() throws Exception {
        level("BASIC");
        CandidateAttempt a = consented(null, asha, codes.get(0));
        signal(a, "COPY", 1);
        for (String t : List.of("proctoring_setting", "proctoring_consent", "proctoring_signal", "proctoring_flag")) {
            assertThat(rawAppJdbc().sql("SELECT count(*) FROM " + t).query(Long.class).single()).as(t).isZero();
        }
        assertThat(call(get("/api/v1/api-clients/scopes"), admin, status().isOk()).findValuesAsString("key")).doesNotContain("proctoring.evidence.view");
    }
}
