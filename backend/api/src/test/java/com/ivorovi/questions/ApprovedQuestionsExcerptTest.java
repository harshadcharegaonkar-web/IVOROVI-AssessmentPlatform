package com.ivorovi.questions;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Excerpts show the question statement only, never option text. */
class ApprovedQuestionsExcerptTest {

    @Test
    void statementOnlyWithMathListsAndImageDescriptions() {
        Map<String, Object> content = Map.of("format", "ivorovi.content.v1", "blocks", List.of(
                Map.of("type", "paragraph", "runs", List.of(Map.of("text", "A body falls for "), Map.of("math", "t = 2"), Map.of("text", " s."))),
                Map.of("type", "math", "tex", "v = gt"),
                Map.of("type", "image", "asset", "x", "alt", "Velocity graph"),
                Map.of("type", "list", "ordered", true, "items", List.of(Map.of("runs", List.of(Map.of("text", "first"))), Map.of("runs", List.of(Map.of("text", "second")))))));
        assertThat(ApprovedQuestions.excerpt(content)).isEqualTo("A body falls for $t = 2$ s. $v = gt$ [Velocity graph] first second");
    }

    @Test
    void longStatementsAreShortenedAndEmptyContentIsEmpty() {
        String excerpt = ApprovedQuestions.excerpt(Map.of("blocks", List.of(Map.of("type", "paragraph", "runs", List.of(Map.of("text", "x".repeat(300)))))));
        assertThat(excerpt).hasSize(200).endsWith("…");
        assertThat(ApprovedQuestions.excerpt(null)).isEmpty();
        assertThat(ApprovedQuestions.excerpt(Map.of())).isEmpty();
    }
}
