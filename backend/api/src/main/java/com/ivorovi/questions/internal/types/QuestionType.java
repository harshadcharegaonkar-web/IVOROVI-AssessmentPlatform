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

    static List<QuestionType> all() {
        return List.of(new ChoiceTypes.Single(), new ChoiceTypes.Multiple(), new TrueFalseType(), new ShortAnswerType(), new NumericType());
    }
}
