package com.ivorovi.platform.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Exam delivery boundary (C-4). Candidate tokens come from the separate candidate realm (Q-03) with their own issuer and
 * audience; staff tokens are never accepted here and candidate tokens never on the staff API. {@code allowedOrigins} is
 * the exam application's origin(s) only.
 */
@ConfigurationProperties("ivorovi.delivery")
public record DeliveryProperties(String issuer, String jwkSetUri, String audience, List<String> allowedOrigins,
                                 int candidatePerMinute, int tenantPerMinute,
                                 int codeAttemptsPerCandidatePerMinute, int codeAttemptsPerIpPerMinute, int codeAttemptsPerCodePerMinute) {

    public DeliveryProperties {
        if (audience == null || audience.isBlank()) audience = "ivorovi-delivery";
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        if (candidatePerMinute <= 0) candidatePerMinute = 240;
        if (tenantPerMinute <= 0) tenantPerMinute = 120_000;
        if (codeAttemptsPerCandidatePerMinute <= 0) codeAttemptsPerCandidatePerMinute = 10;
        if (codeAttemptsPerIpPerMinute <= 0) codeAttemptsPerIpPerMinute = 300;
        if (codeAttemptsPerCodePerMinute <= 0) codeAttemptsPerCodePerMinute = 5;
    }
}
