package com.ivorovi.platform;

import static com.ivorovi.support.Fixture.ALICE_ORG_ADMIN_A;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ivorovi.support.IntegrationTest;
import com.ivorovi.support.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

class ErrorHandlingIT extends IntegrationTest {

    private final String token = "Bearer " + TestTokens.forCaller(ALICE_ORG_ADMIN_A);

    @Test
    void validationErrorsUseProblemDetailsWithFieldErrors() throws Exception {
        mvc.perform(put("/api/v1/features/ai.evaluation/override").header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("validation_failed"))
                .andExpect(jsonPath("$.type").value("urn:ivorovi:problem:validation_failed"))
                .andExpect(jsonPath("$.errors[0].field").value("enabled"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void malformedJsonIsBadRequestWithoutParserDetails() throws Exception {
        mvc.perform(put("/api/v1/features/ai.evaluation/override").header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("malformed_request"))
                .andExpect(jsonPath("$.detail").value("The request could not be read."));
    }

    @Test
    void invalidParametersAreRejected() throws Exception {
        mvc.perform(get("/api/v1/institutions/not-a-uuid").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_parameter"));
        mvc.perform(get("/api/v1/institutions").param("sort", "password").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void pageSizeIsCapped() throws Exception {
        mvc.perform(get("/api/v1/institutions").param("size", "100000").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(100));
    }

    @Test
    void unknownRouteIsProblemNotFound() throws Exception {
        mvc.perform(get("/api/v1/does-not-exist").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("not_found"));
    }

    @Test
    void everyResponseCarriesCorrelationId() throws Exception {
        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk()).andExpect(header().exists("X-Request-Id"));
    }

    @Test
    void nonApiPathsAreDenied() throws Exception {
        mvc.perform(get("/internal/anything")).andExpect(status().isUnauthorized());
    }

    @Test
    void healthIsPublic() throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
    }
}
