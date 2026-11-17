package com.ivorovi.proctoring.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Add-on B: the flag rules are deterministic, conservative and count events only. */
class ProctoringRulesTest {

    static final Instant T0 = Instant.parse("2026-11-16T10:00:00Z");

    static ProctoringRules.Signal s(String kind, int secondsAfter) {
        return new ProctoringRules.Signal(kind, T0.plusSeconds(secondsAfter), null);
    }

    static List<String> rules(List<ProctoringRules.Hit> hits) {
        return hits.stream().map(ProctoringRules.Hit::rule).toList();
    }

    @Test
    void noSignalsNoFlags() {
        assertThat(ProctoringRules.evaluate(List.of(), true)).isEmpty();
    }

    @Test
    void twoFocusLossesAreNotAFlagButThreeWithinFiveMinutesAre() {
        assertThat(ProctoringRules.evaluate(List.of(s("FOCUS_LOST", 0), s("TAB_HIDDEN", 60)), false)).isEmpty();
        // spread over more than five minutes: still nothing
        assertThat(ProctoringRules.evaluate(List.of(s("FOCUS_LOST", 0), s("FOCUS_LOST", 200), s("FOCUS_LOST", 400)), false)).isEmpty();
        var hits = ProctoringRules.evaluate(List.of(s("FOCUS_LOST", 0), s("TAB_HIDDEN", 100), s("FOCUS_LOST", 300), s("FOCUS_LOST", 900)), false);
        assertThat(rules(hits)).containsExactly("AWAY_REPEATED");
        assertThat(hits.getFirst().severity()).isEqualTo(ProctoringRules.Severity.MEDIUM);
        assertThat(hits.getFirst().count()).isEqualTo(4);                  // later away-signals keep counting
        assertThat(hits.getFirst().firstAt()).isEqualTo(T0);
        assertThat(hits.getFirst().lastAt()).isEqualTo(T0.plusSeconds(900));
    }

    @Test
    void aLongAbsenceAloneRaisesAFlag() {
        var hits = ProctoringRules.evaluate(List.of(new ProctoringRules.Signal("TAB_HIDDEN", T0, 59_999)), false);
        assertThat(hits).isEmpty();
        hits = ProctoringRules.evaluate(List.of(new ProctoringRules.Signal("TAB_HIDDEN", T0, 60_000)), false);
        assertThat(rules(hits)).containsExactly("AWAY_LONG");
    }

    @Test
    void eachRuleHasItsSeverity() {
        var hits = ProctoringRules.evaluate(List.of(s("FULLSCREEN_EXIT", 1), s("COPY", 2), s("PASTE", 3), s("MULTI_DISPLAY", 4), s("CAMERA_UNAVAILABLE", 5)), true);
        assertThat(rules(hits)).containsExactly("FULLSCREEN_EXIT", "CLIPBOARD", "MULTI_DISPLAY", "CAMERA_UNAVAILABLE");
        assertThat(hits.stream().filter(h -> h.rule().equals("CLIPBOARD")).findFirst().orElseThrow().count()).isEqualTo(2);
        assertThat(hits.stream().filter(h -> h.rule().equals("MULTI_DISPLAY")).findFirst().orElseThrow().severity()).isEqualTo(ProctoringRules.Severity.HIGH);
    }

    @Test
    void aMissingCameraMattersOnlyWhenSnapshotsAreRequired() {
        assertThat(ProctoringRules.evaluate(List.of(s("CAMERA_UNAVAILABLE", 0)), false)).isEmpty();
        assertThat(rules(ProctoringRules.evaluate(List.of(s("CAMERA_UNAVAILABLE", 0)), true))).containsExactly("CAMERA_UNAVAILABLE");
    }

    @Test
    void theResultDoesNotDependOnArrivalOrder() {
        List<ProctoringRules.Signal> signals = new ArrayList<>(List.of(s("FOCUS_LOST", 0), s("FOCUS_LOST", 10), s("FOCUS_LOST", 20), s("PASTE", 30), s("MULTI_DISPLAY", 40)));
        var expected = ProctoringRules.evaluate(signals, false);
        for (int i = 0; i < 20; i++) {
            Collections.shuffle(signals, new java.util.Random(i));
            assertThat(ProctoringRules.evaluate(signals, false)).isEqualTo(expected);
        }
    }
}
