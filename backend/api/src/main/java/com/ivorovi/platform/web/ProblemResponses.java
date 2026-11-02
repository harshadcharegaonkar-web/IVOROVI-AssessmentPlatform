package com.ivorovi.platform.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Builds RFC 9457 problem documents with stable error codes. Used by the MVC advice and security handlers. */
@Component
public class ProblemResponses {

    public static final String TYPE_PREFIX = "urn:ivorovi:problem:";
    private final JsonMapper json;

    ProblemResponses(JsonMapper json) {
        this.json = json;
    }

    public static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create(TYPE_PREFIX + code));
        pd.setTitle(status.getReasonPhrase());
        pd.setProperty("code", code);
        String traceId = MDC.get(RequestCorrelationFilter.MDC_TRACE_ID);
        if (traceId != null) pd.setProperty("traceId", traceId);
        return pd;
    }

    public void write(HttpServletResponse response, HttpStatus status, String code, String detail) throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/problem+json");
        json.writeValue(response.getOutputStream(), problem(status, code, detail));
    }
}
