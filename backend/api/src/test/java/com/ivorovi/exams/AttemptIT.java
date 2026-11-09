package com.ivorovi.exams;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ivorovi.exams.internal.AttemptService;
import com.ivorovi.platform.crypto.FieldCipher;
import com.ivorovi.platform.security.CandidateAuthentication;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.platform.web.ApiException;
import com.ivorovi.questions.QuestionTestSupport;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import tools.jackson.databind.json.JsonMapper;

/**
 * M3 delivery engine: start/resume, fixed deadline with extra time, durable ordered answer saves with a journal,
 * idempotent submission, automatic submission at time-up, isolation between candidates, and the database guards.
 * Scheduling is represented by a test implementation of {@link ExamEligibility} (the real one is the scheduling module's).
 */
@Import(AttemptIT.Eligibilities.class)
class AttemptIT extends QuestionTestSupport {

    @TestConfiguration
    static class Eligibilities {
        static final Map<String, ExamEligibility.Eligibility> TERMS = new ConcurrentHashMap<>();

        @Bean
        ExamEligibility testEligibility() {
            return new ExamEligibility() {
                public Optional<Eligibility> forCandidate(UUID scheduleId, UUID candidate) {
                    return Optional.ofNullable(TERMS.get(scheduleId + ":" + candidate));
                }

                public List<Eligibility> upcoming(UUID candidate) {
                    return TERMS.entrySet().stream().filter(e -> e.getKey().endsWith(":" + candidate)).map(Map.Entry::getValue).toList();
                }
            };
        }
    }

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    @Autowired AttemptService attempts;
    @Autowired FieldCipher cipher;

    String portalKey;
    UUID portalId, session, version, schedule;

    @BeforeEach
    void setUp() throws Exception {
        setUpOrg("attempts");
        UUID bank = standardBank();
        for (int i = 0; i < 6; i++) approved(bank, faculty, designer, controller, mcq("Delivery question " + i + " " + rnd(), "A"));
        version = publishedVersion(bank);
        portalKey = "a-" + UUID.randomUUID().toString().substring(0, 8);
        portalId = UUID.randomUUID();
        ownerJdbc().sql("INSERT INTO exam_portal (id, tenant_id, portal_key, name, mfa_policy, self_registration, created_by) VALUES (?, ?, ?, ?, 'OFF', true, ?)")
                .params(portalId, org.tenant, portalKey, "Portal", UUID.randomUUID()).update();
        session = UUID.randomUUID();
        ownerJdbc().sql("INSERT INTO readiness_session (id, tenant_id, portal_id, org_unit_id, name, starts_at, ends_at, created_by) VALUES (?, ?, ?, ?, 'Mid-term', ?, ?, ?)")
                .params(session, org.tenant, portalId, college, Timestamp.from(Instant.now().minusSeconds(600)), Timestamp.from(Instant.now().plusSeconds(7200)),
                        UUID.randomUUID()).update();
        schedule = UUID.randomUUID();
    }

    /** Two fixed questions and a rule drawing two of the other four; 90 minutes. Author, reviewer, publisher differ. */
    UUID publishedVersion(UUID bank) throws Exception {
        List<UUID> qv = ownerJdbc().sql("SELECT approved_version_id FROM question WHERE bank_id = ? ORDER BY created_at").param(bank).query(UUID.class).list();
        String a = call(post("/api/v1/assessments").content("""
                {"orgUnitId":"%s","code":"D-%s","purpose":"SEMESTER","title":"Delivery mid-term","durationMinutes":90}""".formatted(college, rnd())),
                designer, status().isCreated()).path("id").asString();
        long lock = ownerJdbc().sql("SELECT v.version FROM assessment x JOIN assessment_version v ON v.id = x.current_version_id WHERE x.id = ?::uuid").param(a)
                .query(Long.class).single();
        call(put("/api/v1/assessments/" + a + "/draft").content("""
                {"title":"Delivery mid-term","durationMinutes":90,"shuffleOptions":true,"items":[{"questionVersionId":"%s"},{"questionVersionId":"%s"}],
                 "rules":[{"bankId":"%s","count":2,"marks":1}],"version":%d}""".formatted(qv.get(0), qv.get(1), bank, lock)), designer, status().isOk());
        call(post("/api/v1/assessments/" + a + "/submit").content("{}"), designer, status().isOk());
        call(post("/api/v1/assessments/" + a + "/reviews").content("{\"decision\":\"REVIEWED\"}"), controller, status().isOk());
        call(post("/api/v1/assessments/" + a + "/publish").content("{}"), controller2, status().isOk());
        return ownerJdbc().sql("SELECT published_version_id FROM assessment WHERE id = ?::uuid").param(a).query(UUID.class).single();
    }

    UUID candidate(String sub, String email) {
        UUID id = UUID.randomUUID();
        ownerJdbc().sql("INSERT INTO candidate_account (id, tenant_id, subject, email_enc, display_name, portal_id) VALUES (?, ?, ?, ?, ?, ?)")
                .params(id, org.tenant, sub, cipher.encrypt(email, "candidate_account.email", org.tenant), "Candidate", portalId).update();
        return id;
    }

    void terms(UUID candidate, Instant opens, Instant closes, Instant lateEntry, int extra) {
        Eligibilities.TERMS.put(schedule + ":" + candidate, new ExamEligibility.Eligibility(schedule, "Delivery mid-term", version, session, opens, closes, lateEntry, 90, extra));
    }

    void checkIn(UUID candidate) {
        ownerJdbc().sql("INSERT INTO check_in (id, tenant_id, session_id, candidate_id, code_id) VALUES (?, ?, ?, ?, ?)")
                .params(UUID.randomUUID(), org.tenant, session, candidate, UUID.randomUUID()).update();
    }

    <T> T as(String sub, Supplier<T> work) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject(sub).claim("email", sub + "@c.test").claim("email_verified", true)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        SecurityContextHolder.getContext().setAuthentication(new CandidateAuthentication(jwt));
        try {
            return TenantContext.callInTenant(org.tenant, work);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    static String code(Throwable t) {
        return t instanceof ApiException e ? e.code() : t.getClass().getSimpleName();
    }

    // ================================================================================================ journey

    @Test
    void startResumeSaveSubmitWithAFixedDeadlineAndNoAnswerInformation() throws Exception {
        String sub = "cand-" + rnd();
        UUID me = candidate(sub, sub + "@c.test");
        terms(me, Instant.now().minusSeconds(60), Instant.now().plus(Duration.ofHours(3)), null, 15);

        // check-in first
        assertThat(as(sub, () -> attempts.exams(portalKey))).singleElement().satisfies(e -> assertThat(e.availability()).isEqualTo("CHECK_IN_REQUIRED"));
        assertThatThrownBy(() -> as(sub, () -> attempts.start(portalKey, schedule))).satisfies(t -> assertThat(code(t)).isEqualTo("not_checked_in"));
        checkIn(me);
        assertThat(as(sub, () -> attempts.exams(portalKey)).getFirst().availability()).isEqualTo("START");

        Instant before = Instant.now();
        AttemptService.AttemptView v = as(sub, () -> attempts.start(portalKey, schedule));
        assertThat(v.status()).isEqualTo("IN_PROGRESS");
        assertThat(v.items()).hasSize(4);
        assertThat(Duration.between(before, v.deadlineAt()).toMinutes()).isBetween(104L, 105L);     // 90 + 15 extra
        String text = MAPPER.writeValueAsString(v);
        assertThat(text).doesNotContain("answerKey").doesNotContain("\"correct\"").doesNotContain("Because A is right").doesNotContain("RULE:");
        // resume: same attempt, same paper, same deadline
        AttemptService.AttemptView again = as(sub, () -> attempts.start(portalKey, schedule));
        assertThat(again.id()).isEqualTo(v.id());
        assertThat(again.deadlineAt()).isEqualTo(v.deadlineAt());
        assertThat(again.items()).extracting(i -> i.questionVersionId()).containsExactlyElementsOf(v.items().stream().map(i -> i.questionVersionId()).toList());
        assertThat(ownerJdbc().sql("SELECT count(*) FROM assessment_paper WHERE attempt_ref = ?").param("attempt:" + v.id()).query(Long.class).single()).isEqualTo(1);

        // durable, ordered saves
        Map<String, Object> b = Map.of("selected", "B");
        assertThat(as(sub, () -> attempts.save(portalKey, v.id(), 1, 5, b)).applied()).isTrue();
        AttemptService.AnswerAck stale = as(sub, () -> attempts.save(portalKey, v.id(), 1, 4, Map.of("selected", "C")));
        assertThat(stale.applied()).isFalse();
        assertThat(stale.seq()).isEqualTo(5);
        assertThat(as(sub, () -> attempts.save(portalKey, v.id(), 1, 5, b)).applied()).isFalse();          // repeated send
        as(sub, () -> attempts.save(portalKey, v.id(), 2, 1, Map.of("selected", "A")));
        assertThatThrownBy(() -> as(sub, () -> attempts.save(portalKey, v.id(), 9, 1, b))).satisfies(t -> assertThat(code(t)).isEqualTo("invalid_position"));
        assertThat(ownerJdbc().sql("SELECT response->>'selected' FROM exam_answer WHERE attempt_id = ? AND position = 1").param(v.id()).query(String.class).single())
                .isEqualTo("B");
        assertThat(ownerJdbc().sql("SELECT count(*) FROM exam_answer_journal WHERE attempt_id = ?").param(v.id()).query(Long.class).single()).isEqualTo(2);

        // idempotent submission
        AttemptService.SubmitResult s1 = as(sub, () -> attempts.submit(portalKey, v.id(), "k-1"));
        AttemptService.SubmitResult s2 = as(sub, () -> attempts.submit(portalKey, v.id(), "k-1"));
        assertThat(s1.status()).isEqualTo("SUBMITTED");
        assertThat(s2).isEqualTo(s1);
        assertThat(s1.answered()).isEqualTo(2);
        assertThat(s1.questions()).isEqualTo(4);
        assertThat(audits("exam.attempt.submitted")).isEqualTo(1);
        assertThat(ownerJdbc().sql("SELECT count(*) FROM outbox_event WHERE tenant_id = ? AND event_type = 'exam.attempt.submitted'").param(org.tenant)
                .query(Long.class).single()).isEqualTo(1);
        // nothing changes afterwards
        assertThatThrownBy(() -> as(sub, () -> attempts.save(portalKey, v.id(), 3, 9, b))).satisfies(t -> assertThat(code(t)).isEqualTo("attempt_submitted"));
        assertThat(as(sub, () -> attempts.exams(portalKey)).getFirst().availability()).isEqualTo("FINISHED");
        assertThat(as(sub, () -> attempts.get(portalKey, v.id())).items()).isEmpty();             // the paper is not shown after submission
    }

    @Test
    void theDeadlineNeverPassesTheWindowAndLateEntryCloses() {
        String sub = "cand-" + rnd();
        UUID me = candidate(sub, sub + "@c.test");
        checkIn(me);
        Instant closes = Instant.now().plus(Duration.ofMinutes(40));
        terms(me, Instant.now().minusSeconds(60), closes, null, 30);
        AttemptService.AttemptView v = as(sub, () -> attempts.start(portalKey, schedule));
        assertThat(v.deadlineAt()).isCloseTo(closes, within(1, java.time.temporal.ChronoUnit.MILLIS));   // database stores microseconds

        String late = "cand-" + rnd();
        UUID other = candidate(late, late + "@c.test");
        checkIn(other);
        terms(other, Instant.now().minus(Duration.ofHours(1)), Instant.now().plus(Duration.ofHours(1)), Instant.now().minusSeconds(5), 0);
        assertThatThrownBy(() -> as(late, () -> attempts.start(portalKey, schedule))).satisfies(t -> assertThat(code(t)).isEqualTo("closed"));
        assertThat(audits("exam.attempt.start_refused")).isEqualTo(1);

        String early = "cand-" + rnd();
        UUID third = candidate(early, early + "@c.test");
        checkIn(third);
        terms(third, Instant.now().plus(Duration.ofHours(1)), Instant.now().plus(Duration.ofHours(3)), null, 0);
        assertThatThrownBy(() -> as(early, () -> attempts.start(portalKey, schedule))).satisfies(t -> assertThat(code(t)).isEqualTo("not_open"));
        // not enrolled at all: the schedule does not exist for this candidate
        assertThatThrownBy(() -> as(early, () -> attempts.start(portalKey, UUID.randomUUID()))).satisfies(t -> assertThat(code(t)).isEqualTo("not_found"));
    }

    @Test
    void attemptsBelongToTheirCandidateOnly() {
        String a = "cand-" + rnd();
        String b = "cand-" + rnd();
        UUID ma = candidate(a, a + "@c.test");
        candidate(b, b + "@c.test");
        checkIn(ma);
        terms(ma, Instant.now().minusSeconds(60), Instant.now().plus(Duration.ofHours(3)), null, 0);
        UUID attempt = as(a, () -> attempts.start(portalKey, schedule)).id();
        assertThatThrownBy(() -> as(b, () -> attempts.get(portalKey, attempt))).satisfies(t -> assertThat(code(t)).isEqualTo("not_found"));
        assertThatThrownBy(() -> as(b, () -> attempts.save(portalKey, attempt, 1, 1, Map.of("selected", "A")))).satisfies(t -> assertThat(code(t)).isEqualTo("not_found"));
        assertThatThrownBy(() -> as(b, () -> attempts.submit(portalKey, attempt, null))).satisfies(t -> assertThat(code(t)).isEqualTo("not_found"));
        // another organization's context sees no attempt rows at all
        assertThat(rawAppJdbc().sql("SELECT count(*) FROM exam_attempt").query(Long.class).single()).isZero();
    }

    @Test
    void timeUpSubmitsAutomaticallyAndTheDatabaseRefusesLateOrTamperedWrites() {
        String sub = "cand-" + rnd();
        UUID me = candidate(sub, sub + "@c.test");
        checkIn(me);
        terms(me, Instant.now().minusSeconds(60), Instant.now().plus(Duration.ofHours(3)), null, 0);
        UUID live = as(sub, () -> attempts.start(portalKey, schedule)).id();
        // tampering with an open attempt's binding or deadline is refused even for the owner role
        assertThatThrownBy(() -> ownerJdbc().sql("UPDATE exam_attempt SET deadline_at = deadline_at + interval '1 hour' WHERE id = ?").param(live).update())
                .hasMessageContaining("never change");
        assertThatThrownBy(() -> ownerJdbc().sql("DELETE FROM exam_attempt WHERE id = ?").param(live).update()).isInstanceOf(Exception.class);
        as(sub, () -> attempts.save(portalKey, live, 1, 1, Map.of("selected", "A")));
        assertThatThrownBy(() -> ownerJdbc().sql("DELETE FROM exam_answer WHERE attempt_id = ?").param(live).update()).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> ownerJdbc().sql("UPDATE exam_answer SET seq = 0 WHERE attempt_id = ?").param(live).update()).hasMessageContaining("stale");
        assertThatThrownBy(() -> ownerJdbc().sql("DELETE FROM exam_answer_journal WHERE attempt_id = ?").param(live).update()).hasMessageContaining("append-only");

        // an attempt whose time ran out (created directly with a past deadline) is submitted by the job; late saves are refused
        String late = "cand-" + rnd();
        UUID other = candidate(late, late + "@c.test");
        UUID expired = UUID.randomUUID();
        UUID paper = TenantContext.callInTenant(org.tenant, () -> ownerPaper(expired));
        ownerJdbc().sql("""
                INSERT INTO exam_attempt (id, tenant_id, schedule_id, candidate_id, session_id, version_id, paper_id, started_at, deadline_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, now() - interval '2 hours', now() - interval '1 minute')""")
                .params(expired, org.tenant, UUID.randomUUID(), other, session, version, paper).update();
        assertThatThrownBy(() -> ownerJdbc().sql("INSERT INTO exam_answer (tenant_id, attempt_id, position, question_version_id, response, seq) VALUES (?, ?, 1, ?, '{}', 1)")
                .params(org.tenant, expired, version).update()).hasMessageContaining("closed");
        assertThat(attempts.autoSubmitDue()).isGreaterThanOrEqualTo(1);
        assertThat(ownerJdbc().sql("SELECT status FROM exam_attempt WHERE id = ?").param(expired).query(String.class).single()).isEqualTo("AUTO_SUBMITTED");
        assertThat(ownerJdbc().sql("SELECT status FROM exam_attempt WHERE id = ?").param(live).query(String.class).single()).isEqualTo("IN_PROGRESS");
        assertThatThrownBy(() -> ownerJdbc().sql("UPDATE exam_attempt SET status = 'IN_PROGRESS', submitted_at = NULL WHERE id = ?").param(expired).update())
                .hasMessageContaining("never changes");
    }

    @Autowired com.ivorovi.assessments.PublishedAssessments published;

    UUID ownerPaper(UUID attemptId) {
        return published.paperForAttempt(version, "attempt:" + attemptId);
    }
}
