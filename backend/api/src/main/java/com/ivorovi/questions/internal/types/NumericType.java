package com.ivorovi.questions.internal.types;

import static com.ivorovi.questions.internal.types.Validation.allowKeys;
import static com.ivorovi.questions.internal.types.Validation.invalid;
import static com.ivorovi.questions.internal.types.Validation.map;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Numeric response with deterministic evaluation (exact decimals, no floating point): correct value and optional
 * equivalent values, absolute or percentage tolerance (inclusive), response precision with round-half-up or rejection of
 * extra decimals, negative values and scientific notation, optional partial-marks band.
 */
public final class NumericType implements QuestionType {

    /** Sign, digits with optional fraction (or leading point), optional exponent of at most three digits. */
    static final Pattern NUMBER = Pattern.compile("^[+-]?(\\d{1,30}(\\.\\d{0,30})?|\\.\\d{1,30})([eE][+-]?\\d{1,3})?$");
    private static final MathContext MC = new MathContext(60, RoundingMode.HALF_UP);

    public String code() { return "NUMERIC"; }
    public String label() { return "Numeric response"; }

    public Body body(Map<String, Object> raw) {
        allowKeys(raw, "body", "unit", "precision");
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("precision", Validation.intIn(raw.get("precision"), "body.precision", 0, 10, 2));
        if (raw.get("unit") != null) {
            String u = String.valueOf(raw.get("unit")).strip();
            if (u.length() > 20) throw invalid("body.unit", "at most 20 characters");
            if (!u.isEmpty()) b.put("unit", u);
        }
        return new Body(b, List.of(), List.of());
    }

    public static Optional<BigDecimal> parse(String s) {
        if (s == null) return Optional.empty();
        String t = s.strip();
        if (!NUMBER.matcher(t).matches()) return Optional.empty();
        return Optional.of(new BigDecimal(t));
    }

    private static BigDecimal number(Object v, String path) {
        return parse(v == null ? null : String.valueOf(v)).orElseThrow(() -> invalid(path, "a decimal number such as -12.5 or 6.02e23"));
    }

    private static Map<String, Object> tolerance(Object raw, String path) {
        Map<?, ?> t = map(raw, path);
        allowKeys(t, path, "type", "value");
        String type = t.get("type") == null ? "ABSOLUTE" : String.valueOf(t.get("type"));
        if (!Set.of("ABSOLUTE", "PERCENT").contains(type)) throw invalid(path + ".type", "ABSOLUTE or PERCENT");
        BigDecimal v = t.get("value") == null ? BigDecimal.ZERO : number(t.get("value"), path + ".value");
        if (v.signum() < 0) throw invalid(path + ".value", "not negative");
        if ("PERCENT".equals(type) && v.compareTo(BigDecimal.valueOf(100)) > 0) throw invalid(path + ".value", "at most 100 percent");
        return Map.of("type", type, "value", v.toPlainString());
    }

    public Map<String, Object> key(Map<String, Object> raw, Body body) {
        allowKeys(raw, "answerKey", "value", "alternatives", "tolerance", "rounding", "partial");
        Map<String, Object> k = new LinkedHashMap<>();
        k.put("value", number(raw.get("value"), "answerKey.value").toString());
        List<String> alts = new ArrayList<>();
        if (raw.get("alternatives") instanceof List<?> l) {
            if (l.size() > 5) throw invalid("answerKey.alternatives", "at most 5");
            for (int i = 0; i < l.size(); i++) alts.add(number(l.get(i), "answerKey.alternatives[" + i + "]").toString());
        } else if (raw.get("alternatives") != null) throw invalid("answerKey.alternatives", "list expected");
        k.put("alternatives", alts);
        Map<String, Object> tol = tolerance(raw.get("tolerance"), "answerKey.tolerance");
        k.put("tolerance", tol);
        String rounding = raw.get("rounding") == null ? "ROUND_HALF_UP" : String.valueOf(raw.get("rounding"));
        if (!Set.of("ROUND_HALF_UP", "REJECT_EXCESS_PRECISION").contains(rounding)) throw invalid("answerKey.rounding", "ROUND_HALF_UP or REJECT_EXCESS_PRECISION");
        k.put("rounding", rounding);
        if (raw.get("partial") != null) {
            Map<?, ?> p = map(raw.get("partial"), "answerKey.partial");
            allowKeys(p, "answerKey.partial", "tolerance", "percent");
            Map<String, Object> ptol = tolerance(p.get("tolerance"), "answerKey.partial.tolerance");
            int pct = Validation.intIn(p.get("percent"), "answerKey.partial.percent", 1, 99, 50);
            if (!ptol.get("type").equals(tol.get("type")) || new BigDecimal((String) ptol.get("value")).compareTo(new BigDecimal((String) tol.get("value"))) <= 0) {
                throw invalid("answerKey.partial.tolerance", "same tolerance type and wider than the full-marks tolerance");
            }
            k.put("partial", Map.of("tolerance", ptol, "percent", pct));
        }
        return k;
    }

    public Map<String, Object> marking(Map<String, Object> raw) { allowKeys(raw, "marking"); return Map.of(); }
    public Map<String, Object> deliveryProjection(Map<String, Object> body) { return Map.copyOf(body); }

    /** True when {@code response} lies within the tolerance of {@code target} (inclusive). */
    static boolean within(BigDecimal response, BigDecimal target, Map<?, ?> tol) {
        BigDecimal t = new BigDecimal(String.valueOf(tol.get("value")));
        BigDecimal allowed = "PERCENT".equals(tol.get("type")) ? target.abs().multiply(t, MC).divide(BigDecimal.valueOf(100), MC) : t;
        return response.subtract(target, MC).abs().compareTo(allowed) <= 0;
    }

    public Grade mark(Map<String, Object> body, Map<String, Object> key, Map<String, Object> marking, BigDecimal marks, BigDecimal neg, Object response) {
        if (response == null || String.valueOf(response).isBlank()) return new Grade(Outcome.UNANSWERED, BigDecimal.ZERO, "no answer");
        Optional<BigDecimal> parsed = parse(String.valueOf(response));
        if (parsed.isEmpty()) return new Grade(Outcome.INVALID, BigDecimal.ZERO, "not a number");
        int precision = ((Number) body.get("precision")).intValue();
        BigDecimal r = parsed.get();
        if (r.stripTrailingZeros().scale() > precision) {
            if ("REJECT_EXCESS_PRECISION".equals(key.get("rounding"))) {
                return new Grade(Outcome.INCORRECT, neg.negate(), "more than " + precision + " decimal places");
            }
        }
        r = r.setScale(precision, RoundingMode.HALF_UP);
        List<BigDecimal> targets = new ArrayList<>();
        targets.add(new BigDecimal(String.valueOf(key.get("value"))));
        for (Object a : (List<?>) key.getOrDefault("alternatives", List.of())) targets.add(new BigDecimal(String.valueOf(a)));
        Map<?, ?> tol = (Map<?, ?>) key.get("tolerance");
        for (BigDecimal t : targets) if (within(r, t, tol)) return new Grade(Outcome.CORRECT, marks, "within tolerance of " + t.toPlainString());
        if (key.get("partial") instanceof Map<?, ?> p) {
            for (BigDecimal t : targets) {
                if (within(r, t, (Map<?, ?>) p.get("tolerance"))) {
                    int pct = ((Number) p.get("percent")).intValue();
                    return new Grade(Outcome.PARTIAL, marks.multiply(BigDecimal.valueOf(pct)).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP),
                            "within the partial band (" + pct + "%)");
                }
            }
        }
        return new Grade(Outcome.INCORRECT, neg.negate(), "outside the tolerance");
    }
}
