package com.ivorovi.questions.internal.types;

import com.ivorovi.questions.internal.ContentFormat;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * A question type plug-in. Adding a type means adding an implementation: the storage (type code, JSON body, encrypted
 * JSON key, JSON marking) does not change. The body never contains answer information; the key is stored encrypted and
 * separately; the delivery projection is what a candidate may ever see.
 */
public interface QuestionType {

    String code();

    String label();

    /** Validated body plus the content of its options (for search, duplicates and images). */
    record Body(Map<String, Object> body, List<ContentFormat.Parsed> optionContents, List<String> optionIds) {}

    Body body(Map<String, Object> raw);

    /** Validated, canonical answer key for the body. */
    Map<String, Object> key(Map<String, Object> raw, Body body);

    /** Validated non-secret marking parameters. */
    Map<String, Object> marking(Map<String, Object> raw);

    /** Candidate-visible fields only (no key, no explanation, no marking rules beyond marks). */
    Map<String, Object> deliveryProjection(Map<String, Object> body);

    enum Outcome { CORRECT, PARTIAL, INCORRECT, UNANSWERED, INVALID }

    record Grade(Outcome outcome, BigDecimal awarded, String detail) {}

    /** Deterministic marking of one response (staff marking preview now; scoring service from 2.6). */
    Grade mark(Map<String, Object> body, Map<String, Object> key, Map<String, Object> marking, BigDecimal marks, BigDecimal negativeMarks, Object response);

    /**
     * How a response to this type gets its marks (contract C-1). AUTOMATIC: {@link #mark} at scoring. MANUAL: evaluators
     * award the marks (manual evaluation); {@link #mark} is never called. EXTERNAL: an isolated executor reports them
     * (coding); {@link #mark} is never called.
     */
    enum MarkingMode { AUTOMATIC, MANUAL, EXTERNAL }

    default MarkingMode markingMode() {
        return MarkingMode.AUTOMATIC;
    }

    /**
     * Whether a candidate's saved answer value (never null: null clears an answer) has the shape this type accepts for
     * the given body. Checked on every save during delivery; nothing else is stored.
     */
    boolean acceptsResponse(Map<String, Object> body, Object value);

    /** Option ids of a choice body. */
    static java.util.Set<String> optionIds(Map<String, Object> body) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        if (body.get("options") instanceof List<?> l) for (Object o : l) if (o instanceof Map<?, ?> m) ids.add(String.valueOf(m.get("id")));
        return ids;
    }

    static List<QuestionType> all() {
        return List.of(new ChoiceTypes.Single(), new ChoiceTypes.Multiple(), new TrueFalseType(), new ShortAnswerType(), new NumericType(), new DescriptiveType());
    }
}
