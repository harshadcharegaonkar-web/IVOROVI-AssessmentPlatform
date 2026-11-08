package com.ivorovi.questions.internal.types;

import static com.ivorovi.questions.internal.types.Validation.allowKeys;
import static com.ivorovi.questions.internal.types.Validation.invalid;
import static com.ivorovi.questions.internal.types.Validation.map;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ShortAnswerType implements QuestionType {
    public String code() { return "SHORT_ANSWER"; }
    public String label() { return "Short answer"; }

    public Body body(Map<String, Object> raw) {
        allowKeys(raw, "body", "maxLength");
        return new Body(Map.of("maxLength", Validation.intIn(raw.get("maxLength"), "body.maxLength", 1, 500, 100)), List.of(), List.of());
    }

    public Map<String, Object> key(Map<String, Object> raw, Body body) {
        allowKeys(raw, "answerKey", "answers", "match");
        String match = raw.get("match") == null ? "NORMALIZED" : String.valueOf(raw.get("match"));
        if (!Set.of("EXACT", "CASE_INSENSITIVE", "NORMALIZED").contains(match)) throw invalid("answerKey.match", "EXACT, CASE_INSENSITIVE or NORMALIZED");
        if (!(raw.get("answers") instanceof List<?> list) || list.isEmpty() || list.size() > 20) throw invalid("answerKey.answers", "1–20 accepted answers");
        int max = ((Number) body.body().get("maxLength")).intValue();
        List<Object> answers = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Map<?, ?> a = map(list.get(i), "answerKey.answers[" + i + "]");
            allowKeys(a, "answerKey.answers[" + i + "]", "text", "percent");
            String t = a.get("text") == null ? "" : String.valueOf(a.get("text"));
            if (t.isBlank() || t.length() > max) throw invalid("answerKey.answers[" + i + "].text", "1–" + max + " characters");
            String n = normalize(t, match);
            if (seen.contains(n)) throw invalid("answerKey.answers[" + i + "]", "duplicate accepted answer");
            seen.add(n);
            int pct = Validation.intIn(a.get("percent"), "answerKey.answers[" + i + "].percent", 1, 100, 100);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("text", t);
            m.put("percent", pct);
            answers.add(m);
        }
        if (answers.stream().noneMatch(a -> ((Integer) ((Map<?, ?>) a).get("percent")) == 100)) throw invalid("answerKey.answers", "at least one answer worth full marks");
        return Map.of("answers", answers, "match", match);
    }

    public Map<String, Object> marking(Map<String, Object> raw) { allowKeys(raw, "marking"); return Map.of(); }
    public Map<String, Object> deliveryProjection(Map<String, Object> body) { return Map.copyOf(body); }

    static String normalize(String s, String match) {
        return switch (match) {
            case "EXACT" -> s;
            case "CASE_INSENSITIVE" -> s.strip().toLowerCase(Locale.ROOT);
            default -> Normalizer.normalize(s, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}\\p{IsPunctuation}]", "")
                    .replaceAll("\\s+", " ").strip();
        };
    }

    public Grade mark(Map<String, Object> body, Map<String, Object> key, Map<String, Object> marking, BigDecimal marks, BigDecimal neg, Object response) {
        if (response == null || String.valueOf(response).isBlank()) return new Grade(Outcome.UNANSWERED, BigDecimal.ZERO, "no answer");
        String match = String.valueOf(key.get("match"));
        String r = normalize(String.valueOf(response), match);
        int best = 0;
        for (Object a : (List<?>) key.get("answers")) {
            Map<?, ?> m = (Map<?, ?>) a;
            if (normalize(String.valueOf(m.get("text")), match).equals(r)) best = Math.max(best, ((Number) m.get("percent")).intValue());
        }
        if (best == 100) return new Grade(Outcome.CORRECT, marks, "matches an accepted answer");
        if (best > 0) return new Grade(Outcome.PARTIAL, marks.multiply(BigDecimal.valueOf(best)).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP), best + "% answer");
        return new Grade(Outcome.INCORRECT, neg.negate(), "no accepted answer matches");
    }
}
