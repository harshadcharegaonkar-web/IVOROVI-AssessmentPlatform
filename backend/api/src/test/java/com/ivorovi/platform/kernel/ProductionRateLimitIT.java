package com.ivorovi.platform.kernel;

import static com.ivorovi.support.Fixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ivorovi.support.IntegrationTest;
import com.ivorovi.support.TestTokens;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;

/**
 * KI-88 (approved 2026-11-16): the staff E2E layout tests now use their own accounts so they stay within what one person
 * generates; the limits themselves did not change. This test proves it with the shipped production defaults: the
 * per-user read limit in application.yml is still 300 a minute, and with exactly that limit the 301st read of one user in a
 * minute is refused (429, Retry-After), while another user is unaffected. (The other integration tests raise the limits
 * so that they never interfere; this one restores the production value.)
 */
@TestPropertySource(properties = "ivorovi.rate-limit.user-read-per-minute=300")
class ProductionRateLimitIT extends IntegrationTest {

    static final int PRODUCTION_READS_PER_MINUTE = 300;

    @Autowired StringRedisTemplate redis;

    @BeforeEach
    @AfterEach
    void flush() {
        redis.delete(redis.keys("ivorovi:rl:*"));
    }

    @Test
    void theShippedPerUserReadLimitIsUnchanged() throws Exception {
        String yml = new ClassPathResource("application.yml").getContentAsString(StandardCharsets.UTF_8);
        assertThat(yml).contains("user-read-per-minute: ${IVOROVI_RATE_LIMIT_USER_READ:" + PRODUCTION_READS_PER_MINUTE + "}");
        assertThat(yml).contains("user-write-per-minute: ${IVOROVI_RATE_LIMIT_USER_WRITE:60}");
        assertThat(yml).contains("tenant-per-minute: ${IVOROVI_RATE_LIMIT_TENANT:3000}");
    }

    /**
     * A token bucket of 300 refilled at 5 a second: one user's reads succeed for the burst of 300 plus the refill earned
     * while they run, then the next read is refused. Both bounds are asserted, so neither a raised limit nor a disabled
     * limiter passes.
     */
    @Test
    void excessiveReadsOfOneUserAreStillRefusedAtTheProductionLimit() throws Exception {
        String t = "Bearer " + TestTokens.forCaller(ARUN_FACULTY_A1);
        long start = System.nanoTime();
        int ok = 0;
        int refusedAt = -1;
        for (int i = 0; i < 3 * PRODUCTION_READS_PER_MINUTE && refusedAt < 0; i++) {
            int s = mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, t)).andReturn().getResponse().getStatus();
            if (s == 200) ok++;
            else if (s == 429) refusedAt = i;
            else throw new AssertionError("unexpected status " + s);
        }
        double seconds = (System.nanoTime() - start) / 1e9;
        assertThat(refusedAt).as("excessive reads are refused").isGreaterThanOrEqualTo(0);
        assertThat(ok).as("the burst of the production limit is available").isGreaterThanOrEqualTo(PRODUCTION_READS_PER_MINUTE);
        assertThat(ok).as("no more than the limit plus its refill (5 a second) over %.1f s", seconds)
                .isLessThanOrEqualTo(PRODUCTION_READS_PER_MINUTE + (int) Math.ceil(seconds * PRODUCTION_READS_PER_MINUTE / 60.0) + 1);
        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, t))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("rate_limited"));
        // the limit is per user: another person is unaffected
        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.forCaller(ALICE_ORG_ADMIN_A)))
                .andExpect(status().isOk());
    }
}
