package com.ivorovi.platform.kernel;

import static com.ivorovi.support.Fixture.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ivorovi.support.IntegrationTest;
import com.ivorovi.support.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/** Increment 1.0 AC3. */
@TestPropertySource(properties = {
        "ivorovi.rate-limit.user-read-per-minute=5",
        "ivorovi.rate-limit.user-write-per-minute=2",
        "ivorovi.rate-limit.failed-auth-per-ip-per-minute=3"})
class RateLimitIT extends IntegrationTest {

    @Autowired StringRedisTemplate redis;

    @BeforeEach
    @AfterEach
    void flush() {
        redis.delete(redis.keys("ivorovi:rl:*"));
    }

    @Test
    void readsAreLimitedPerUserWithStandardHeaders() throws Exception {
        String t = "Bearer " + TestTokens.forCaller(ARUN_FACULTY_A1);
        for (int i = 0; i < 5; i++) {
            mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, t))
                    .andExpect(status().isOk())
                    .andExpect(header().string("RateLimit-Limit", "5"))
                    .andExpect(header().string("RateLimit-Remaining", String.valueOf(4 - i)));
        }
        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, t))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("rate_limited"));
        // another user is unaffected
        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.forCaller(ALICE_ORG_ADMIN_A)))
                .andExpect(status().isOk());
    }

    @Test
    void writesHaveTheirOwnStricterBucket() throws Exception {
        String t = "Bearer " + TestTokens.forCaller(ARUN_FACULTY_A1);
        for (int i = 0; i < 2; i++) {
            mvc.perform(put("/api/v1/features/ai.evaluation/override").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"enabled\":true}").header(HttpHeaders.AUTHORIZATION, t)).andExpect(status().isForbidden());
        }
        mvc.perform(put("/api/v1/features/ai.evaluation/override").contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}").header(HttpHeaders.AUTHORIZATION, t)).andExpect(status().isTooManyRequests());
        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, t)).andExpect(status().isOk());
    }

    @Test
    void repeatedFailedAuthenticationBlocksTheClientAddress() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.signedByUntrustedKey(ALICE_ORG_ADMIN_A)))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.forCaller(ALICE_ORG_ADMIN_A)))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
    }

    @Test
    void successfulRequestsDoNotCountAsFailedAuthentication() throws Exception {
        for (int i = 0; i < 4; i++) {
            mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.forCaller(BELA_ORG_ADMIN_B)))
                    .andExpect(status().isOk());
        }
    }
}
