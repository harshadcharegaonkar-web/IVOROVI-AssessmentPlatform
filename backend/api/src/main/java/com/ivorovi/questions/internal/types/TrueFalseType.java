package com.ivorovi.questions.internal.types;

import static com.ivorovi.questions.internal.types.Validation.allowKeys;
import static com.ivorovi.questions.internal.types.Validation.invalid;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public final class TrueFalseType implements QuestionType {
    public String code() { return "TRUE_FALSE"; }
    public String label() { return "True / false"; }

    public Body body(Map<String, Object> raw) {
        allowKeys(raw, "body", "trueLabel", "falseLabel");
        String t = raw.get("trueLabel") == null ? "True" : String.valueOf(raw.get("trueLabel")).strip();
        String f = raw.get("falseLabel") == null ? "False" : String.valueOf(raw.get("falseLabel")).strip();
        if (t.isEmpty() || f.isEmpty() || t.length() > 30 || f.length() > 30 || t.equalsIgnoreCase(f)) throw invalid("body", "two different labels of 1–30 characters");
        return new Body(Map.of("trueLabel", t, "falseLabel", f), List.of(), List.of());
    }

    public Map<String, Object> key(Map<String, Object> raw, Body body) {
        allowKeys(raw, "answerKey", "value");
        if (!(raw.get("value") instanceof Boolean b)) throw invalid("answerKey.value", "true or false");
        return Map.of("value", b);
    }

    public Map<String, Object> marking(Map<String, Object> raw) { allowKeys(raw, "marking"); return Map.of(); }
    public Map<String, Object> deliveryProjection(Map<String, Object> body) { return Map.copyOf(body); }

    public Grade mark(Map<String, Object> body, Map<String, Object> key, Map<String, Object> marking, BigDecimal marks, BigDecimal neg, Object response) {
        if (!(response instanceof Boolean b)) return new Grade(Outcome.UNANSWERED, BigDecimal.ZERO, "no answer");
        return b.equals(key.get("value")) ? new Grade(Outcome.CORRECT, marks, "correct") : new Grade(Outcome.INCORRECT, neg.negate(), "wrong");
    }
}
