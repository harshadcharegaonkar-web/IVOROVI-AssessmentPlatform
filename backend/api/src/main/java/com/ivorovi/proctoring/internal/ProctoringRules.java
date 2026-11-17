package com.ivorovi.proctoring.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * The flag rules (version {@value #VERSION}). Deterministic over an attempt's signals: the same signals always give the
 * same flags. Rules count events; they never score or judge a person, and thresholds are deliberately conservative.
 * A rule fires at most one flag per attempt; later matching signals raise its count.
 */
public final class ProctoringRules {

    public static final int VERSION = 1;

    public enum Severity { LOW, MEDIUM, HIGH }

    public record Signal(String kind, Instant at, Integer durationMs) {}

    /** A rule that fired: matching signals counted from the first to the last. */
    public record Hit(String rule, Severity severity, Instant firstAt, Instant lastAt, int count) {}

    public record Rule(String code, Severity severity, String description) {}

    static final Duration WINDOW = Duration.ofMinutes(5);
    static final int REPEATED = 3;
    static final int LONG_MS = 60_000;
    static final Set<String> AWAY = Set.of("FOCUS_LOST", "TAB_HIDDEN");

    public static final List<Rule> RULES = List.of(
            new Rule("AWAY_REPEATED", Severity.MEDIUM, "The examination window lost focus or was hidden " + REPEATED + " or more times within 5 minutes"),
            new Rule("AWAY_LONG", Severity.MEDIUM, "The examination window lost focus or was hidden for a minute or longer"),
            new Rule("FULLSCREEN_EXIT", Severity.LOW, "Full-screen mode was left"),
            new Rule("CLIPBOARD", Severity.LOW, "Copy or paste was attempted"),
            new Rule("MULTI_DISPLAY", Severity.HIGH, "More than one display was detected"),
            new Rule("CAMERA_UNAVAILABLE", Severity.MEDIUM, "The camera was unavailable while snapshots were required"));

    private ProctoringRules() {}

    public static Rule rule(String code) {
        return RULES.stream().filter(r -> r.code().equals(code)).findFirst().orElseThrow();
    }

    public static List<Hit> evaluate(List<Signal> raw, boolean snapshotsRequired) {
        List<Signal> s = new ArrayList<>(raw);
        s.sort(Comparator.comparing(Signal::at));
        List<Hit> out = new ArrayList<>();
        // AWAY_REPEATED: from the first time REPEATED away-signals fall within the window, every later away-signal counts too
        List<Signal> away = s.stream().filter(x -> AWAY.contains(x.kind())).toList();
        int start = -1;
        for (int i = REPEATED - 1; i < away.size() && start < 0; i++) {
            if (!away.get(i).at().isAfter(away.get(i - REPEATED + 1).at().plus(WINDOW))) start = i - REPEATED + 1;
        }
        if (start >= 0) out.add(hit("AWAY_REPEATED", away.subList(start, away.size())));
        add(out, "AWAY_LONG", away.stream().filter(x -> x.durationMs() != null && x.durationMs() >= LONG_MS).toList());
        add(out, "FULLSCREEN_EXIT", s.stream().filter(x -> x.kind().equals("FULLSCREEN_EXIT")).toList());
        add(out, "CLIPBOARD", s.stream().filter(x -> x.kind().equals("COPY") || x.kind().equals("PASTE")).toList());
        add(out, "MULTI_DISPLAY", s.stream().filter(x -> x.kind().equals("MULTI_DISPLAY")).toList());
        if (snapshotsRequired) add(out, "CAMERA_UNAVAILABLE", s.stream().filter(x -> x.kind().equals("CAMERA_UNAVAILABLE")).toList());
        return out;
    }

    private static void add(List<Hit> out, String rule, List<Signal> matching) {
        if (!matching.isEmpty()) out.add(hit(rule, matching));
    }

    private static Hit hit(String rule, List<Signal> matching) {
        return new Hit(rule, rule(rule).severity(), matching.getFirst().at(), matching.getLast().at(), matching.size());
    }
}
