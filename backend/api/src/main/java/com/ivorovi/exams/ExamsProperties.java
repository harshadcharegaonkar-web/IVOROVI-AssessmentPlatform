package com.ivorovi.exams;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Exam portal and outage settings. {@code outageThreshold}: a delivery role without a heartbeat for this long is treated as
 * an outage (C-3). {@code idp}: the candidate realm's admin API through the confidential provisioner client.
 */
@ConfigurationProperties("ivorovi.exams")
public record ExamsProperties(String examAppBaseUrl, boolean outageDetectorEnabled, Duration outageThreshold, Duration outageCheckInterval,
                              Idp idp) {

    public record Idp(String adminUrl, String realm, String clientId, String clientSecret) {}

    public ExamsProperties {
        if (examAppBaseUrl == null || examAppBaseUrl.isBlank()) examAppBaseUrl = "http://localhost:5174";
        if (outageThreshold == null) outageThreshold = Duration.ofSeconds(45);
        if (outageCheckInterval == null) outageCheckInterval = Duration.ofSeconds(15);
        if (idp == null) idp = new Idp("http://localhost:8180", "ivorovi-candidates", "ivorovi-candidate-provisioner", "");
    }
}
