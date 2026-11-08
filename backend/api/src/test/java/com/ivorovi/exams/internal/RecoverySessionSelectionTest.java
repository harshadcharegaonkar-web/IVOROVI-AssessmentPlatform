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
}
