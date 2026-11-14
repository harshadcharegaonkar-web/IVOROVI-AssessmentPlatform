package com.ivorovi.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ivorovi.exams.ExamEligibility;
import com.ivorovi.exams.ExamSessions;
import com.ivorovi.platform.crypto.FieldCipher;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.questions.QuestionTestSupport;
import com.ivorovi.support.RecordingEmailSender;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * M2: schedule a published assessment, enrol candidates by bulk upload (preview, errors, idempotent re-upload), invite by
 * email, extra time with a reason, session codes, eligibility for delivery (linking by keyed email index), cancellation,
 * authorization, isolation and the database guards. Personal data stays out of audit details.
 */
class ScheduleIT extends QuestionTestSupport {

    @Autowired RecordingEmailSender mail;
    @Autowired List<ExamEligibility> eligibility;
    @Autowired ExamSessions sessions;
    @Autowired FieldCipher cipher;

    UUID version, draftVersion, portalId;
    String portalKey;

    @BeforeEach
    void setUp() throws Exception {
        setUpOrg("sched");
        UUID bank = standardBank();
        for (int i = 0; i < 3; i++) approved(bank, faculty, designer, controller, mcq("Scheduling question " + i + " " + rnd(), "A"));
        List<UUID> qv = ownerJdbc().sql("SELECT approved_version_id FROM question WHERE bank_id = ? ORDER BY created_at").param(bank).query(UUID.class).list();
        String a = call(post("/api/v1/assessments").content("""
                {"orgUnitId":"%s","code":"S-%s","purpose":"INTERNAL","title":"Unit test 1","durationMinutes":45}""".formatted(college, rnd())), designer, status().isCreated())
                .path("id").asString();
        long lock = ownerJdbc().sql("SELECT v.version FROM assessment x JOIN assessment_version v ON v.id = x.current_version_id WHERE x.id = ?::uuid").param(a).query(Long.class).single();
        call(put("/api/v1/assessments/" + a + "/draft").content("""
                {"title":"Unit test 1","durationMinutes":45,"items":[{"questionVersionId":"%s"},{"questionVersionId":"%s"},{"questionVersionId":"%s"}],"version":%d}"""
                .formatted(qv.get(0), qv.get(1), qv.get(2), lock)), designer, status().isOk());
        call(post("/api/v1/assessments/" + a + "/submit").content("{}"), designer, status().isOk());
        call(post("/api/v1/assessments/" + a + "/reviews").content("{\"decision\":\"REVIEWED\"}"), controller, status().isOk());
        call(post("/api/v1/assessments/" + a + "/publish").content("{}"), controller, status().isOk());
        version = ownerJdbc().sql("SELECT published_version_id FROM assessment WHERE id = ?::uuid").param(a).query(UUID.class).single();
        String d = call(post("/api/v1/assessments").content("""
                {"orgUnitId":"%s","code":"D-%s","purpose":"INTERNAL","title":"Draft only"}""".formatted(college, rnd())), designer, status().isCreated()).path("id").asString();
        draftVersion = ownerJdbc().sql("SELECT current_version_id FROM assessment WHERE id = ?::uuid").param(d).query(UUID.class).single();
        portalKey = "s-" + rnd();
        portalId = UUID.randomUUID();
        ownerJdbc().sql("INSERT INTO exam_portal (id, tenant_id, portal_key, name, mfa_policy, self_registration, created_by) VALUES (?, ?, ?, 'Entrance', 'OFF', true, ?)")
                .params(portalId, org.tenant, portalKey, UUID.randomUUID()).update();
        mail.sent.clear();
    }

    String schedule(String body) {
        return "{\"versionId\":\"%s\",\"portalId\":\"%s\",\"orgUnitId\":\"%s\",%s}".formatted(version, portalId, college, body);
    }

    String window(Instant opens, Instant closes) {
        return "\"opensAt\":\"%s\",\"closesAt\":\"%s\"".formatted(opens, closes);
    }

    UUID create() throws Exception {
        JsonNode s = call(post("/api/v1/exam/schedules").content(schedule(window(Instant.now().minusSeconds(60), Instant.now().plus(Duration.ofHours(3)))
                + ",\"instructions\":\"No phones.\"")), controller, status().isCreated());
        return UUID.fromString(s.path("id").asString());
    }

    UUID candidate(String email) {
        UUID id = UUID.randomUUID();
        String index = TenantContext.callInTenant(org.tenant, () -> sessions.emailIndex(email));
        ownerJdbc().sql("INSERT INTO candidate_account (id, tenant_id, subject, email_enc, email_index, display_name, portal_id) VALUES (?, ?, ?, ?, ?, 'Candidate', ?)")
                .params(id, org.tenant, "sub-" + id, cipher.encrypt(email, "candidate_account.email", org.tenant), index, portalId).update();
        return id;
    }

    @Test
    void schedulePublishedVersionsOnlyWithinTheirInstitutionAndAPlausibleWindow() throws Exception {
        UUID id = create();
        JsonNode s = call(get("/api/v1/exam/schedules/" + id), controller, status().isOk());
        assertThat(s.path("versionId").asString()).isEqualTo(version.toString());
        assertThat(s.path("durationMinutes").asInt()).isEqualTo(45);
        assertThat(s.path("status").asString()).isEqualTo("SCHEDULED");
        UUID session = UUID.fromString(s.path("sessionId").asString());
        assertThat(ownerJdbc().sql("SELECT count(*) FROM readiness_session WHERE id = ?").param(session).query(Long.class).single()).isEqualTo(1);
        assertThat(ownerJdbc().sql("SELECT count(*) FROM assessment_version_reference WHERE version_id = ? AND referrer_type = 'exam_schedule'").param(version)
                .query(Long.class).single()).isEqualTo(1);
        // a draft cannot be scheduled; the window must hold the duration; another institution is not eligible
        perform(post("/api/v1/exam/schedules").content(schedule(window(Instant.now(), Instant.now().plus(Duration.ofHours(3)))).replace(version.toString(), draftVersion.toString())),
                controller).andExpect(status().isNotFound());
        perform(post("/api/v1/exam/schedules").content(schedule(window(Instant.now(), Instant.now().plus(Duration.ofMinutes(30))))), controller)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_window"));
        perform(post("/api/v1/exam/schedules").content(schedule(window(Instant.now(), Instant.now().plus(Duration.ofHours(3)))).replace(college.toString(), otherCollege.toString())),
                admin).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("unit_not_eligible"));
        // who may schedule: not faculty, not designers; another college's controller does not even see it
        perform(post("/api/v1/exam/schedules").content(schedule(window(Instant.now(), Instant.now().plus(Duration.ofHours(3))))), faculty).andExpect(status().isForbidden());
        perform(get("/api/v1/exam/schedules/" + id), otherController).andExpect(status().isNotFound());
        assertThat(call(get("/api/v1/exam/schedules"), otherController, status().isOk()).findValuesAsString("id")).doesNotContain(id.toString());
        // the binding never changes; schedules are never deleted
        assertThatThrownBy(() -> ownerJdbc().sql("UPDATE exam_schedule SET version_id = ? WHERE id = ?").params(draftVersion, id).update()).hasMessageContaining("never change");
        assertThatThrownBy(() -> ownerJdbc().sql("DELETE FROM exam_schedule WHERE id = ?").param(id).update()).isInstanceOf(Exception.class);
    }

    @Test
    void bulkUploadPreviewsValidatesAndIsIdempotentWithoutPersonalDataInAudit() throws Exception {
        UUID id = create();
        String csv = """
                email,name,extra_minutes,extra_reason
                riya.sharma@cand.test,Riya Sharma
                arjun@cand.test,"Arjun, K",15,Scribe approved
                not-an-email,Nobody
                riya.sharma@cand.test,Duplicate
                meera@cand.test,Meera,10,
                """;
        JsonNode preview = call(post("/api/v1/exam/schedules/" + id + "/enrolments/upload").content(JSON.writeValueAsString(java.util.Map.of("csv", csv, "apply", false))),
                controller, status().isOk());
        assertThat(preview.path("rows").asInt()).isEqualTo(5);
        assertThat(preview.path("valid").asInt()).isEqualTo(2);
        assertThat(preview.path("errors").findValuesAsString("message")).containsExactly("Not a valid email address.", "This email appears earlier in the file.", "Extra time needs a reason.");
        assertThat(ownerJdbc().sql("SELECT count(*) FROM exam_enrolment WHERE schedule_id = ?").param(id).query(Long.class).single()).isZero();     // preview stores nothing
        JsonNode applied = call(post("/api/v1/exam/schedules/" + id + "/enrolments/upload").content(JSON.writeValueAsString(java.util.Map.of("csv", csv, "apply", true))),
                controller, status().isOk());
        assertThat(applied.path("created").asInt()).isEqualTo(2);
        JsonNode again = call(post("/api/v1/exam/schedules/" + id + "/enrolments/upload").content(JSON.writeValueAsString(java.util.Map.of("csv", csv, "apply", true))),
                controller, status().isOk());
        assertThat(again.path("created").asInt()).isZero();
        assertThat(again.path("alreadyEnrolled").asInt()).isEqualTo(2);
        JsonNode list = call(get("/api/v1/exam/schedules/" + id + "/enrolments"), controller, status().isOk());
        assertThat(list.findValuesAsString("email")).containsExactly("riya.sharma@cand.test", "arjun@cand.test");
        assertThat(list.get(1).path("extraMinutes").asInt()).isEqualTo(15);
        assertThat(list.get(1).path("displayName").asString()).isEqualTo("Arjun, K");
        // emails are encrypted at rest and never in audit details
        assertThat(ownerJdbc().sql("SELECT string_agg(email_enc, ' ') FROM exam_enrolment WHERE schedule_id = ?").param(id).query(String.class).single()).doesNotContain("@cand.test");
        assertThat(ownerJdbc().sql("SELECT coalesce(string_agg(details::text, ' '), '') FROM audit_event WHERE tenant_id = ?").param(org.tenant).query(String.class).single())
                .doesNotContain("@cand.test").doesNotContain("Riya");
        perform(post("/api/v1/exam/schedules/" + id + "/enrolments/upload").content(JSON.writeValueAsString(java.util.Map.of("csv", csv, "apply", true))), faculty)
                .andExpect(status().isForbidden());
    }

    @Test
    void invitationsExtraTimeCodesAndEligibilityForDelivery() throws Exception {
        UUID id = create();
        call(post("/api/v1/exam/schedules/" + id + "/enrolments/upload").content(JSON.writeValueAsString(java.util.Map.of(
                "csv", "kavya@cand.test,Kavya\nrohit@cand.test,Rohit\n", "apply", true))), controller, status().isOk());
        JsonNode inv = call(post("/api/v1/exam/schedules/" + id + "/invitations"), controller, status().isOk());
        assertThat(inv.path("sent").asInt()).isEqualTo(2);
        assertThat(mail.sent).extracting(m -> m.to()).containsExactlyInAnyOrder("kavya@cand.test", "rohit@cand.test");
        assertThat(mail.sent.getFirst().textBody()).contains("Unit test 1").contains("/p/" + portalKey);
        assertThat(call(post("/api/v1/exam/schedules/" + id + "/invitations"), controller, status().isOk()).path("sent").asInt()).isZero();   // not re-sent

        JsonNode list = call(get("/api/v1/exam/schedules/" + id + "/enrolments"), controller, status().isOk());
        JsonNode kavya = list.get(0);
        perform(put("/api/v1/exam/schedules/" + id + "/enrolments/" + kavya.path("id").asString() + "/extra-time")
                .content("{\"minutes\":20,\"version\":" + kavya.path("version").asLong() + "}"), controller)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("reason_required"));
        call(put("/api/v1/exam/schedules/" + id + "/enrolments/" + kavya.path("id").asString() + "/extra-time")
                .content("{\"minutes\":20,\"reason\":\"Medical certificate\",\"version\":" + kavya.path("version").asLong() + "}"), controller, status().isOk());
        JsonNode codes = call(post("/api/v1/exam/schedules/" + id + "/codes").content("{\"count\":3}"), controller, status().isCreated());
        assertThat(codes.size()).isEqualTo(3);
        assertThat(codes.get(0).path("code").asString()).isNotBlank();

        // delivery eligibility: Kavya's account (same email on this portal) is found by the keyed index, linked, with her extra time
        UUID kavyaAccount = candidate("Kavya@Cand.test");
        UUID stranger = candidate("someone@cand.test");
        List<ExamEligibility.Eligibility> terms = TenantContext.callInTenant(org.tenant, () -> eligibility.stream().flatMap(e -> e.upcoming(kavyaAccount).stream()).toList());
        assertThat(terms).singleElement().satisfies(t -> {
            assertThat(t.scheduleId()).isEqualTo(id);
            assertThat(t.assessmentVersionId()).isEqualTo(version);
            assertThat(t.extraMinutes()).isEqualTo(20);
            assertThat(t.durationMinutes()).isEqualTo(45);
        });
        assertThat(ownerJdbc().sql("SELECT candidate_id FROM exam_enrolment WHERE id = ?::uuid").param(kavya.path("id").asString()).query(UUID.class).single()).isEqualTo(kavyaAccount);
        assertThat(TenantContext.callInTenant(org.tenant, () -> eligibility.stream().flatMap(e -> e.upcoming(stranger).stream()).toList())).isEmpty();
        // a linked enrolment never moves to another candidate
        assertThatThrownBy(() -> ownerJdbc().sql("UPDATE exam_enrolment SET candidate_id = ? WHERE id = ?::uuid").params(stranger, kavya.path("id").asString()).update())
                .hasMessageContaining("never change");

        // cancelled: no longer eligible, session cancelled, nothing changes afterwards
        call(post("/api/v1/exam/schedules/" + id + "/cancel").content("{\"reason\":\"Venue unavailable\"}"), controller, status().isOk());
        assertThat(TenantContext.callInTenant(org.tenant, () -> eligibility.stream().flatMap(e -> e.forCandidate(id, kavyaAccount).stream()).toList())).isEmpty();
        perform(post("/api/v1/exam/schedules/" + id + "/invitations"), controller).andExpect(status().isConflict());
        assertThat(audits("exam.schedule.cancelled")).isEqualTo(1);
    }

    @Test
    void assessmentsWithImagesAreSchedulableOnlyWhileEveryImageIsScannedClean() throws Exception {
        UUID bank = standardBank();
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(40, 30, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", png);
        String asset = JSON.readTree(mvc.perform(multipart("/api/v1/question-banks/" + bank + "/assets")
                        .file(new MockMultipartFile("file", "figure.png", "image/png", png.toByteArray())).param("altText", "Triangle")
                        .header(HttpHeaders.AUTHORIZATION, faculty.bearer()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("id").asString();
        UUID q = approved(bank, faculty, designer, controller, """
                {"type":"TRUE_FALSE","content":{"format":"ivorovi.content.v1","blocks":[{"type":"paragraph","runs":[{"text":"Is this right-angled? %s"}]},
                  {"type":"image","asset":"%s","alt":"A labelled triangle"}]},"body":{},"answerKey":{"value":true}}""".formatted(rnd(), asset));
        UUID qv = ownerJdbc().sql("SELECT approved_version_id FROM question WHERE id = ?").param(q).query(UUID.class).single();
        String a = call(post("/api/v1/assessments").content("""
                {"orgUnitId":"%s","code":"I-%s","purpose":"INTERNAL","title":"With an image","durationMinutes":30}""".formatted(college, rnd())), designer, status().isCreated())
                .path("id").asString();
        long lock = ownerJdbc().sql("SELECT v.version FROM assessment x JOIN assessment_version v ON v.id = x.current_version_id WHERE x.id = ?::uuid").param(a).query(Long.class).single();
        call(put("/api/v1/assessments/" + a + "/draft").content("""
                {"title":"With an image","durationMinutes":30,"items":[{"questionVersionId":"%s"}],"version":%d}""".formatted(qv, lock)), designer, status().isOk());
        call(post("/api/v1/assessments/" + a + "/submit").content("{}"), designer, status().isOk());
        call(post("/api/v1/assessments/" + a + "/reviews").content("{\"decision\":\"REVIEWED\"}"), controller, status().isOk());
        call(post("/api/v1/assessments/" + a + "/publish").content("{}"), controller, status().isOk());
        UUID imageVersion = ownerJdbc().sql("SELECT published_version_id FROM assessment WHERE id = ?::uuid").param(a).query(UUID.class).single();
        // KI-83: candidates receive scanned-clean images, so this version can be scheduled
        perform(post("/api/v1/exam/schedules").content(schedule(window(Instant.now(), Instant.now().plus(Duration.ofHours(3)))).replace(version.toString(), imageVersion.toString())),
                controller).andExpect(status().isCreated());
        // an image that a later scan finds infected blocks further scheduling (fail closed)
        ownerJdbc().sql("UPDATE question_asset SET scan_status = 'INFECTED', served_key = NULL WHERE id = ?::uuid").param(asset).update();
        perform(post("/api/v1/exam/schedules").content(schedule(window(Instant.now(), Instant.now().plus(Duration.ofHours(3)))).replace(version.toString(), imageVersion.toString())),
                controller).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("images_not_deliverable"));
        // the text-only assessment of this class is still schedulable
        create();
    }
}
