package com.ivorovi.exams.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** KI-79: which sessions end after a password reset (deterministic; no timing involved). */
class RecoverySessionSelectionTest {

    private static Map<String, Object> session(String id, Object start) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("start", start);
        return m;
    }

    @Test
    void sessionsOlderThanTheResetEndAndTheResetSessionStays() {
        Instant reset = Instant.ofEpochMilli(1_000_000);
        List<Map<String, Object>> sessions = List.of(
                session("phone-before", 900_000L),                 // other device, before the reset → ends
                session("laptop-before", 999_999L),                // 1 ms before → ends
                session("reset-flow", 999_998L),                   // the reset's own session (even if its start is earlier) → stays
                session("signed-in-after", 1_000_500L),            // a new sign-in with the new password → stays
                session("unknown-start", null));                   // unknown → ends (fail safe)
        assertThat(KeycloakCandidateMfa.sessionsToEnd(sessions, reset, "reset-flow"))
                .containsExactly("phone-before", "laptop-before", "unknown-start");
    }

    @Test
    void withoutAReportedResetSessionOnlyTheStartTimeDecides() {
        Instant reset = Instant.ofEpochMilli(5_000);
        assertThat(KeycloakCandidateMfa.sessionsToEnd(List.of(session("a", 4_999L), session("b", 5_000L)), reset, null)).containsExactly("a");
        assertThat(KeycloakCandidateMfa.sessionsToEnd(null, reset, null)).isEmpty();
    }

    /** Shape of a real Keycloak 26 UPDATE_PASSWORD event from the reset-credentials flow (no sessionId; flow id in code_id). */
    private static Map<String, Object> updatePassword(Object sessionId, Object codeId) {
        Map<String, Object> details = new HashMap<>();
        details.put("auth_method", "openid-connect");
        if (codeId != null) details.put("code_id", codeId);
        Map<String, Object> e = new HashMap<>();
        e.put("type", "UPDATE_PASSWORD");
        e.put("time", 1_794_191_460_831L);
        e.put("sessionId", sessionId);
        e.put("details", details);
        return e;
    }

    @Test
    void theResetSessionIsTheFlowsCodeIdWhenTheEventHasNoSessionId() {
        assertThat(KeycloakCandidateMfa.resetSessionOf(updatePassword(null, "tfK7KuYulP9n3xhkwdrehFST"))).isEqualTo("tfK7KuYulP9n3xhkwdrehFST");
        assertThat(KeycloakCandidateMfa.resetSessionOf(updatePassword("explicit", "code"))).isEqualTo("explicit");
        assertThat(KeycloakCandidateMfa.resetSessionOf(updatePassword(null, null))).isNull();
        assertThat(KeycloakCandidateMfa.resetSessionOf(Map.of("type", "UPDATE_PASSWORD"))).isNull();
    }

    /**
     * The CI failure of KI-79's first fix: Keycloak reports session start in whole seconds, so the session created a few
     * milliseconds after the reset looks older than the reset. It must survive because its id is the reset flow's.
     */
    @Test
    void theResetSessionSurvivesSecondResolutionStartTimes() {
        Instant reset = Instant.ofEpochMilli(1_794_191_460_831L);
        String keep = KeycloakCandidateMfa.resetSessionOf(updatePassword(null, "tfK7KuYulP9n3xhkwdrehFST"));
        List<Map<String, Object>> sessions = List.of(
                session("9AiLFUUslL0CRL197HsRIACe", 1_794_191_430_000L),   // the other device, signed in before the reset
                session("tfK7KuYulP9n3xhkwdrehFST", 1_794_191_460_000L));  // the reset flow's session, start truncated
        assertThat(KeycloakCandidateMfa.sessionsToEnd(sessions, reset, keep)).containsExactly("9AiLFUUslL0CRL197HsRIACe");
    }
}
