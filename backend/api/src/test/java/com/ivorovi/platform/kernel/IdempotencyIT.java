package com.ivorovi.platform.kernel;

import static com.ivorovi.support.Fixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ivorovi.support.Fixture;
import com.ivorovi.support.IntegrationTest;
import com.ivorovi.support.TestTokens;
import com.ivorovi.support.probe.IdempotencyProbeController;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** Increment 1.0 AC4. */
class IdempotencyIT extends IntegrationTest {

    private ResultActions create(Fixture.Caller c, String key, String body) throws Exception {
        var req = post("/api/v1/test-probe/items").contentType(MediaType.APPLICATION_JSON).content(body)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.forCaller(c));
        if (key != null) req.header("Idempotency-Key", key);
        return mvc.perform(req);
    }

    private static String key() {
        return "k-" + UUID.randomUUID();
    }

    @Test
    void keyIsRequiredAndValidated() throws Exception {
        create(ALICE_ORG_ADMIN_A, null, "{}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("idempotency_key_required"));
        create(ALICE_ORG_ADMIN_A, "short", "{}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("idempotency_key_invalid"));
        create(ALICE_ORG_ADMIN_A, "bad key with spaces!", "{}").andExpect(status().isBadRequest());
    }

    @Test
    void retryReplaysTheOriginalResponseWithoutReExecuting() throws Exception {
        String k = key();
        int before = IdempotencyProbeController.EXECUTIONS.get();
        String first = create(ALICE_ORG_ADMIN_A, k, "{\"name\":\"CSE\"}").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String second = create(ALICE_ORG_ADMIN_A, k, "{\"name\":\"CSE\"}").andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andReturn().getResponse().getContentAsString();
        assertThat(second).isEqualTo(first);
        assertThat(IdempotencyProbeController.EXECUTIONS.get()).isEqualTo(before + 1);
    }

    @Test
    void reusingAKeyForADifferentRequestIsRejected() throws Exception {
        String k = key();
        create(ALICE_ORG_ADMIN_A, k, "{\"name\":\"CSE\"}").andExpect(status().isCreated());
        create(ALICE_ORG_ADMIN_A, k, "{\"name\":\"ECE\"}").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("idempotency_key_reused"));
    }

    @Test
    void keysAreScopedPerUserAndTenant() throws Exception {
        String k = key();
        int before = IdempotencyProbeController.EXECUTIONS.get();
        create(ALICE_ORG_ADMIN_A, k, "{}").andExpect(status().isCreated());
        create(INES_INST_ADMIN_A1, k, "{}").andExpect(status().isCreated()).andExpect(header().doesNotExist("Idempotency-Replayed"));
        create(BELA_ORG_ADMIN_B, k, "{}").andExpect(status().isCreated()).andExpect(header().doesNotExist("Idempotency-Replayed"));
        assertThat(IdempotencyProbeController.EXECUTIONS.get()).isEqualTo(before + 3);
    }

    @Test
    void concurrentDuplicateIsRejectedWhileInProgress() throws Exception {
        // Take the exact hash of a real request, then simulate a first request with the same key still running.
        String done = key();
        create(ALICE_ORG_ADMIN_A, done, "{\"name\":\"X\"}").andExpect(status().isCreated());
        String k = key();
        ownerJdbc().sql("""
                INSERT INTO idempotency_record (tenant_id, user_account_id, idem_key, method, path, request_hash, status, expires_at)
                SELECT tenant_id, user_account_id, ?, method, path, request_hash, 'IN_PROGRESS', now() + interval '1 hour'
                FROM idempotency_record WHERE idem_key = ?""").params(k, done).update();
        create(ALICE_ORG_ADMIN_A, k, "{\"name\":\"X\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("idempotency_in_progress"));
    }

    @Test
    void serverErrorsReleaseTheKeySoTheClientCanRetry() throws Exception {
        String k = key();
        int before = IdempotencyProbeController.EXECUTIONS.get();
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/v1/test-probe/failing").contentType(MediaType.APPLICATION_JSON).content("{}")
                            .header("Idempotency-Key", k)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.forCaller(ALICE_ORG_ADMIN_A)))
                    .andExpect(status().isInternalServerError());
        }
        assertThat(IdempotencyProbeController.EXECUTIONS.get()).isEqualTo(before + 2);
        assertThat(ownerJdbc().sql("SELECT count(*) FROM idempotency_record WHERE idem_key = ?").param(k)
                .query(Long.class).single()).isZero();
    }
}
