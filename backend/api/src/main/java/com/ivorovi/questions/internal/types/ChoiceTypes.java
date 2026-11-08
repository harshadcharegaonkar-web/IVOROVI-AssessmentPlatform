package com.ivorovi.questions.internal.types;

import static com.ivorovi.questions.internal.types.Validation.allowKeys;
import static com.ivorovi.questions.internal.types.Validation.invalid;
import static com.ivorovi.questions.internal.types.Validation.map;

import com.ivorovi.questions.internal.ContentFormat;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Single- and multiple-answer multiple choice. Options carry ids and content only; correctness is in the key. */
public final class ChoiceTypes {

    private static final Pattern OPTION_ID = Pattern.compile("^[A-Z]$");

    private ChoiceTypes() {}

    static QuestionType.Body options(Map<String, Object> raw, boolean multiple) {
        allowKeys(raw, "body", multiple ? new String[] {"options", "shuffle", "minSelections", "maxSelections"} : new String[] {"options", "shuffle"});
        if (!(raw.get("options") instanceof List<?> opts) || opts.size() < 2 || opts.size() > 10) throw invalid("body.options", "2–10 options");
        List<Object> out = new ArrayList<>();
        List<ContentFormat.Parsed> contents = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        Set<String> norm = new LinkedHashSet<>();
        for (int i = 0; i < opts.size(); i++) {
            String p = "body.options[" + i + "]";
            Map<?, ?> o = map(opts.get(i), p);
            allowKeys(o, p, "id", "content");
            String id = String.valueOf(o.get("id"));
            if (!OPTION_ID.matcher(id).matches() || ids.contains(id)) throw invalid(p + ".id", "unique letter A–Z");
            ContentFormat.Parsed c = ContentFormat.parse(o.get("content"), p + ".content", 2000, 2);
            // identical content only (formulas differing by one symbol are different options)
            if (!norm.add(com.ivorovi.questions.internal.Canonical.json(c.document()) + "|" + c.images())) throw invalid(p, "duplicate option");
            ids.add(id);
            contents.add(c);
            out.add(Map.of("id", id, "content", c.document()));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("options", out);
        body.put("shuffle", !Boolean.FALSE.equals(raw.get("shuffle")));
        if (multiple) {
            int min = Validation.intIn(raw.get("minSelections"), "body.minSelections", 1, opts.size(), 1);
            int max = Validation.intIn(raw.get("maxSelections"), "body.maxSelections", min, opts.size(), opts.size());
            body.put("minSelections", min);
            body.put("maxSelections", max);
        }
        return new QuestionType.Body(body, contents, ids);
    }

    static Map<String, Object> projection(Map<String, Object> body) {
        Map<String, Object> p = new LinkedHashMap<>(body);
        return p; // options (ids + content), shuffle, selection limits — no correctness information exists in the body
    }

    public static final class Single implements QuestionType {
        public String code() { return "SINGLE_CHOICE"; }
        public String label() { return "Multiple choice (one answer)"; }
        public Body body(Map<String, Object> raw) { return options(raw, false); }

        public Map<String, Object> key(Map<String, Object> raw, Body body) {
            allowKeys(raw, "answerKey", "correct");
            String c = String.valueOf(raw.get("correct"));
            if (!body.optionIds().contains(c)) throw invalid("answerKey.correct", "one of the option ids");
            return Map.of("correct", c);
        }

        public Map<String, Object> marking(Map<String, Object> raw) { allowKeys(raw, "marking"); return Map.of(); }
        public Map<String, Object> deliveryProjection(Map<String, Object> body) { return projection(body); }

        public Grade mark(Map<String, Object> body, Map<String, Object> key, Map<String, Object> marking, BigDecimal marks, BigDecimal neg, Object response) {
            if (response == null || String.valueOf(response).isBlank()) return new Grade(Outcome.UNANSWERED, BigDecimal.ZERO, "no answer");
            return String.valueOf(key.get("correct")).equals(String.valueOf(response))
                    ? new Grade(Outcome.CORRECT, marks, "correct option")
                    : new Grade(Outcome.INCORRECT, neg.negate(), "wrong option");
        }
    }

    public static final class Multiple implements QuestionType {
        public String code() { return "MULTIPLE_CHOICE"; }
        public String label() { return "Multiple choice (several answers)"; }
        public Body body(Map<String, Object> raw) { return options(raw, true); }

        public Map<String, Object> key(Map<String, Object> raw, Body body) {
            allowKeys(raw, "answerKey", "correct");
            if (!(raw.get("correct") instanceof List<?> list) || list.isEmpty()) throw invalid("answerKey.correct", "at least one correct option");
            List<String> c = new ArrayList<>();
            for (Object o : list) {
                String id = String.valueOf(o);
                if (!body.optionIds().contains(id) || c.contains(id)) throw invalid("answerKey.correct", "distinct option ids");
                c.add(id);
            }
            int max = ((Number) body.body().get("maxSelections")).intValue();
            int min = ((Number) body.body().get("minSelections")).intValue();
            if (c.size() > max || c.size() < min) throw invalid("answerKey.correct", "number of correct options must fit the selection limits");
            c.sort(String::compareTo);
            return Map.of("correct", c);
        }

        public Map<String, Object> marking(Map<String, Object> raw) {
            allowKeys(raw, "marking", "mode", "negativeOnAnyWrong");
            String mode = raw.get("mode") == null ? "ALL_OR_NOTHING" : String.valueOf(raw.get("mode"));
            if (!Set.of("ALL_OR_NOTHING", "PARTIAL").contains(mode)) throw invalid("marking.mode", "ALL_OR_NOTHING or PARTIAL");
            return Map.of("mode", mode, "negativeOnAnyWrong", Boolean.TRUE.equals(raw.get("negativeOnAnyWrong")));
        }

        public Map<String, Object> deliveryProjection(Map<String, Object> body) { return projection(body); }

        public Grade mark(Map<String, Object> body, Map<String, Object> key, Map<String, Object> marking, BigDecimal marks, BigDecimal neg, Object response) {
            if (!(response instanceof List<?> r) || r.isEmpty()) return new Grade(Outcome.UNANSWERED, BigDecimal.ZERO, "no answer");
            Set<String> chosen = new LinkedHashSet<>();
            for (Object o : r) chosen.add(String.valueOf(o));
            Set<String> correct = new LinkedHashSet<>();
            for (Object o : (List<?>) key.get("correct")) correct.add(String.valueOf(o));
            long right = chosen.stream().filter(correct::contains).count();
            long wrong = chosen.size() - right;
            if (right == correct.size() && wrong == 0) return new Grade(Outcome.CORRECT, marks, "all correct options");
            boolean partial = "PARTIAL".equals(marking.get("mode"));
            if (partial && right > 0) {
                BigDecimal share = BigDecimal.valueOf(Math.max(0, right - wrong)).divide(BigDecimal.valueOf(correct.size()), 10, RoundingMode.HALF_UP);
                BigDecimal awarded = marks.multiply(share).setScale(2, RoundingMode.HALF_UP);
                if (wrong > 0 && Boolean.TRUE.equals(marking.get("negativeOnAnyWrong")) && awarded.signum() == 0) {
                    return new Grade(Outcome.INCORRECT, neg.negate(), "wrong options outweigh correct ones");
                }
                return new Grade(awarded.signum() > 0 ? Outcome.PARTIAL : Outcome.INCORRECT, awarded, right + " of " + correct.size() + " correct, " + wrong + " wrong");
            }
            return new Grade(Outcome.INCORRECT, neg.negate(), "not all correct options");
        }
    }
}
