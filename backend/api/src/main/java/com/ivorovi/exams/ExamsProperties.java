package com.ivorovi.exams;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Exam portal and outage settings. {@code outageThreshold}: a delivery instance without a heartbeat for this long is
 * silent, and a role whose instances are all silent is down (C-3). {@code idp}: the candidate realm's admin API through
 * the confidential provisioner client. {@code checkIn}: the layered check-in limits (KI-66). {@code health}: the
 * per-instance and partial-failure rules of the delivery health detector (KI-71).
 */
@ConfigurationProperties("ivorovi.exams")
public record ExamsProperties(String examAppBaseUrl, boolean outageDetectorEnabled, Duration outageThreshold, Duration outageCheckInterval,
                              Idp idp, CheckIn checkIn, Health health) {

    public record Idp(String adminUrl, String realm, String clientId, String clientSecret) {}

    /**
     * Check-in layers beyond the per-minute ones in {@code ivorovi.delivery} (follow-up A):
     * <ul>
     *   <li>L1 {@code failedPerCandidatePerDay}: failed attempts per candidate account per day;</li>
     *   <li>L2 {@code failedPerCodePerDay}: failed attempts on one presented code per day; an existing code is then locked
     *       (staff re-issue a code);</li>
     *   <li>L3 {@code failedPerDevicePerMinute}: failed attempts per device (sign-in session of the token);</li>
     *   <li>L4 {@code anomalyFactor} × {@code expectedFailureRatio} × expected candidates (at least
     *       {@code anomalyMinimumPerMinute}) failed attempts per portal and minute raise an operator alert, never a block;</li>
     *   <li>L5 failed attempts per address beyond {@code ivorovi.delivery.code-attempts-per-ip-per-minute} delay new
     *       accounts' attempts by {@code addressDelayStep} per excess attempt, up to {@code addressMaxDelay}.</li>
     * </ul>
     */
    public record CheckIn(int failedPerCandidatePerDay, int failedPerCodePerDay, int failedPerDevicePerMinute, Duration addressDelayStep,
                          Duration addressMaxDelay, double anomalyFactor, double expectedFailureRatio, int anomalyMinimumPerMinute) {
        public CheckIn {
            if (failedPerCandidatePerDay <= 0) failedPerCandidatePerDay = 30;
            if (failedPerCodePerDay <= 0) failedPerCodePerDay = 20;
            if (failedPerDevicePerMinute <= 0) failedPerDevicePerMinute = 30;
            if (addressDelayStep == null) addressDelayStep = Duration.ofMillis(250);
            if (addressMaxDelay == null) addressMaxDelay = Duration.ofSeconds(5);
            if (anomalyFactor <= 0) anomalyFactor = 3.0;
            if (expectedFailureRatio <= 0) expectedFailureRatio = 0.05;
            if (anomalyMinimumPerMinute <= 0) anomalyMinimumPerMinute = 30;
        }
    }

    /**
     * Delivery health rules (follow-up B). An instance is degraded when its error rate stays above {@code errorRate}
     * (with at least {@code minRequestsPerMinute} requests) or its p95 latency above {@code latencyP95} for
     * {@code sustainedFor}, or a dependency check fails in {@code dependencyFailures} consecutive heartbeats; the role is
     * degraded when fewer than {@code minHealthyShare} of its instances are healthy, and down when none is alive. An
     * incident recovers once every rule has been clear for {@code recoveryHold}. Heartbeat samples are kept for
     * {@code sampleRetention}. {@code compensationBuffer} is added to verified lost time in compensation proposals (Q-06
     * default, institutions decide).
     */
    public record Health(double errorRate, int minRequestsPerMinute, Duration latencyP95, Duration sustainedFor, int dependencyFailures,
                         double minHealthyShare, Duration recoveryHold, Duration sampleRetention, Duration compensationBuffer) {
        public Health {
            if (errorRate <= 0) errorRate = 0.05;
            if (minRequestsPerMinute <= 0) minRequestsPerMinute = 20;
            if (latencyP95 == null) latencyP95 = Duration.ofSeconds(2);
            if (sustainedFor == null) sustainedFor = Duration.ofMinutes(2);
            if (dependencyFailures <= 0) dependencyFailures = 2;
            if (minHealthyShare <= 0) minHealthyShare = 0.5;
            if (recoveryHold == null) recoveryHold = Duration.ofMinutes(2);
            if (sampleRetention == null) sampleRetention = Duration.ofHours(24);
            if (compensationBuffer == null) compensationBuffer = Duration.ofMinutes(5);
        }
    }

    public ExamsProperties {
        if (examAppBaseUrl == null || examAppBaseUrl.isBlank()) examAppBaseUrl = "http://localhost:5174";
        if (outageThreshold == null) outageThreshold = Duration.ofSeconds(45);
        if (outageCheckInterval == null) outageCheckInterval = Duration.ofSeconds(15);
        if (idp == null) idp = new Idp("http://localhost:8180", "ivorovi-candidates", "ivorovi-candidate-provisioner", "");
        if (checkIn == null) checkIn = new CheckIn(0, 0, 0, null, null, 0, 0, 0);
        if (health == null) health = new Health(0, 0, null, null, 0, 0, null, null, null);
    }
}
