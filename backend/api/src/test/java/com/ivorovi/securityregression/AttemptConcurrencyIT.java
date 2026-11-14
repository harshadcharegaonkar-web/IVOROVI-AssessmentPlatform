package com.ivorovi.securityregression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * Failure and recovery (QA, D-7): the delivery API under concurrent requests of one candidate, as a browser with retries,
 * two tabs or a double click produces them. Over HTTP, all at once (a latch releases every request together):
 * <ul>
 *   <li>concurrent starts create exactly one attempt and one paper;</li>
 *   <li>concurrent submissions (same key, different keys, no key) give one submission: every answer is 200 with the same
 *       submission time, one audit record, one outbox event;</li>
 *   <li>concurrent saves of one question with different sequence numbers: the highest sequence wins regardless of arrival
 *       order, every save is in the journal once.</li>
 * </ul>
 * Fixture: {@link DeliveryJourneySupport}.
 */
@Tag("resilience")
class AttemptConcurrencyIT extends DeliveryJourneySupport {

    private static final int N = 24;

    @Test
    void concurrentStartsCreateOneAttempt() throws Exception {
        delivery(get(portal("/me")), asha, 200);
        delivery(post(portal("/check-ins")).content("{\"code\":\"" + codes.get(0) + "\"}"), asha, 200);
        List<MvcResult> out = together(N, i -> as(post(portal("/exams/" + schedule + "/attempt")), asha).andReturn());
        Set<String> ids = new HashSet<>();
        for (MvcResult r : out) {
            assertThat(r.getResponse().getStatus()).isEqualTo(200);
            ids.add(JSON.readTree(r.getResponse().getContentAsString()).path("id").asString());
        }
        assertThat(ids).hasSize(1);
        UUID attempt = UUID.fromString(ids.iterator().next());
        assertThat(ownerJdbc().sql("SELECT count(*) FROM exam_attempt WHERE schedule_id = ?").param(schedule).query(Long.class).single()).isEqualTo(1);
        assertThat(ownerJdbc().sql("SELECT count(*) FROM assessment_paper WHERE attempt_ref = ?").param("attempt:" + attempt).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void concurrentSubmissionsGiveOneSubmissionWithOneResult() throws Exception {
        UUID attempt = startAttempt(asha, codes.get(0));
        delivery(put(portal("/attempts/" + attempt + "/answers/1")).content("{\"seq\":1,\"response\":{\"value\":\"A\"}}"), asha, 200);
        List<MvcResult> out = together(N, i -> as(post(portal("/attempts/" + attempt + "/submit"))
                .content(i % 3 == 0 ? "{\"submitKey\":\"same\"}" : i % 3 == 1 ? "{\"submitKey\":\"key-" + i + "\"}" : "{}"), asha).andReturn());
        Set<String> submittedAt = new HashSet<>();
        for (MvcResult r : out) {
            assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
            JsonNode b = JSON.readTree(r.getResponse().getContentAsString());
            assertThat(b.path("status").asString()).isEqualTo("SUBMITTED");
            assertThat(b.path("answered").asInt()).isEqualTo(1);
            submittedAt.add(b.path("submittedAt").asString());
        }
        assertThat(submittedAt).hasSize(1);
        assertThat(audits("exam.attempt.submitted")).isEqualTo(1);
        assertThat(ownerJdbc().sql("SELECT count(*) FROM outbox_event WHERE tenant_id = ? AND event_type = 'exam.attempt.submitted'").param(org.tenant)
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void concurrentSavesKeepTheHighestSequenceAndJournalEveryAcceptedSave() throws Exception {
        UUID attempt = startAttempt(asha, codes.get(0));
        // sequences 1..N, released together; values alternate so the stored one tells which sequence won
        List<MvcResult> out = together(N, i -> as(put(portal("/attempts/" + attempt + "/answers/1"))
                .content("{\"seq\":" + (i + 1) + ",\"response\":{\"value\":\"" + ((i + 1) == N ? "C" : (i % 2 == 0 ? "A" : "B")) + "\"}}"), asha).andReturn());
        int applied = 0;
        for (MvcResult r : out) {
            assertThat(r.getResponse().getStatus()).isEqualTo(200);
            JsonNode ack = JSON.readTree(r.getResponse().getContentAsString());
            if (ack.path("applied").asBoolean()) applied++;
            assertThat(ack.path("seq").asLong()).isBetween(1L, (long) N);
        }
        assertThat(applied).isBetween(1, N);
        var stored = ownerJdbc().sql("SELECT seq, response->>'value' FROM exam_answer WHERE attempt_id = ? AND position = 1").param(attempt)
                .query((rs, n) -> List.of(rs.getLong(1), rs.getString(2))).single();
        assertThat(stored).containsExactly((long) N, "C");                                   // the newest answer, never an older one
        long journal = ownerJdbc().sql("SELECT count(*) FROM exam_answer_journal WHERE attempt_id = ?").param(attempt).query(Long.class).single();
        assertThat(journal).isEqualTo(applied);                                                // one journal entry per accepted save
        // a late, older save after the race changes nothing
        delivery(put(portal("/attempts/" + attempt + "/answers/1")).content("{\"seq\":2,\"response\":{\"value\":\"A\"}}"), asha, 200);
        assertThat(ownerJdbc().sql("SELECT response->>'value' FROM exam_answer WHERE attempt_id = ? AND position = 1").param(attempt).query(String.class).single())
                .isEqualTo("C");
    }

    interface Work<T> {
        T run(int i) throws Exception;
    }

    /** Runs {@code n} requests on {@code n} threads released at the same instant. */
    private static <T> List<T> together(int n, Work<T> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<T>> fs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int k = i;
                fs.add(pool.submit((Callable<T>) () -> {
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
