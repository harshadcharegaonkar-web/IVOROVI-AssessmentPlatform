package com.ivorovi.questions.internal.types;

import static com.ivorovi.questions.internal.types.Validation.allowKeys;
import static com.ivorovi.questions.internal.types.Validation.invalid;
import static com.ivorovi.questions.internal.types.Validation.map;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Descriptive (essay or short written) answer, marked by people (contract C-1, MANUAL). The body holds only the word
 * limit, which candidates see. The key holds an optional model answer for evaluators (encrypted like every key, never
 * delivered). The marking holds an optional rubric: criteria with weights summing to 100 (percent of the item's marks);
 * without a rubric, evaluators mark holistically. Nothing here awards marks automatically.
 */
public final class DescriptiveType implements QuestionType {

    public static final int MAX_WORDS = 2500;
    static final int MAX_CRITERIA = 12;
    private static final Pattern WORDS = Pattern.compile("\\s+");

    public String code() { return "DESCRIPTIVE"; }
    public String label() { return "Descriptive (written answer)"; }

    @Override
    public MarkingMode markingMode() { return MarkingMode.MANUAL; }

    public Body body(Map<String, Object> raw) {
        allowKeys(raw, "body", "maxWords");
        return new Body(Map.of("maxWords", Validation.intIn(raw.get("maxWords"), "body.maxWords", 10, MAX_WORDS, 500)), List.of(), List.of());
    }

    /** The optional model answer (guidance for evaluators). */
    public Map<String, Object> key(Map<String, Object> raw, Body body) {
        allowKeys(raw, "answerKey", "modelAnswer");
        Object m = raw.get("modelAnswer");
        if (m == null || String.valueOf(m).isBlank()) return Map.of();
        if (!(m instanceof String s) || s.length() > 20_000) throw invalid("answerKey.modelAnswer", "text of at most 20,000 characters");
        return Map.of("modelAnswer", s.strip());
    }

    /** The optional rubric: {"rubric": [{"title", "description"?, "weight"}]} → criteria C1…Cn, weights summing to 100. */
    public Map<String, Object> marking(Map<String, Object> raw) {
        allowKeys(raw, "marking", "rubric");
        if (raw.get("rubric") == null) return Map.of();
        if (!(raw.get("rubric") instanceof List<?> list) || list.isEmpty() || list.size() > MAX_CRITERIA) throw invalid("marking.rubric", "1–" + MAX_CRITERIA + " criteria");
        List<Object> criteria = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        int total = 0;
        for (int i = 0; i < list.size(); i++) {
            String path = "marking.rubric[" + i + "]";
            Map<?, ?> c = map(list.get(i), path);
            allowKeys(c, path, "id", "title", "description", "weight");
            String title = c.get("title") == null ? "" : String.valueOf(c.get("title")).strip();
            if (title.isEmpty() || title.length() > 200) throw invalid(path + ".title", "1–200 characters");
            if (titles.contains(title.toLowerCase(java.util.Locale.ROOT))) throw invalid(path + ".title", "duplicate criterion");
            titles.add(title.toLowerCase(java.util.Locale.ROOT));
            String description = c.get("description") == null ? "" : String.valueOf(c.get("description")).strip();
            if (description.length() > 2000) throw invalid(path + ".description", "at most 2,000 characters");
            int weight = Validation.intIn(c.get("weight"), path + ".weight", 1, 100, -1);
            if (weight < 0) throw invalid(path + ".weight", "required");
            total += weight;
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", "C" + (i + 1));
            out.put("title", title);
            if (!description.isEmpty()) out.put("description", description);
            out.put("weight", weight);
            criteria.add(out);
        }
        if (total != 100) throw invalid("marking.rubric", "weights must add up to 100 (they add up to " + total + ")");
        return Map.of("rubric", criteria);
    }

    public Map<String, Object> deliveryProjection(Map<String, Object> body) { return Map.of("maxWords", body.get("maxWords")); }

    public static int words(String s) {
        String t = s.strip();
        return t.isEmpty() ? 0 : WORDS.split(t).length;
    }

    public boolean acceptsResponse(Map<String, Object> body, Object v) {
        int max = body.get("maxWords") instanceof Number n ? n.intValue() : 500;
        return v instanceof String s && s.length() <= 18_000 && words(s) <= max;
    }

    /** Never called: scoring hands descriptive answers to evaluators (C-1). */
    public Grade mark(Map<String, Object> body, Map<String, Object> key, Map<String, Object> marking, BigDecimal marks, BigDecimal neg, Object response) {
        throw new UnsupportedOperationException("Descriptive answers are marked by evaluators");
    }
}
