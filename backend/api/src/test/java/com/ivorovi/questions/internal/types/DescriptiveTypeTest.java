package com.ivorovi.questions.internal.types;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ivorovi.platform.web.ApiException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Descriptive type (contract C-1): word limit, model answer, rubric validation, manual marking mode, no key in delivery. */
class DescriptiveTypeTest {

    private final DescriptiveType type = new DescriptiveType();

    @Test
    void isRegisteredAndMarkedByPeople() {
        assertThat(QuestionType.all()).extracting(QuestionType::code).contains("DESCRIPTIVE");
        assertThat(type.markingMode()).isEqualTo(QuestionType.MarkingMode.MANUAL);
        assertThat(new ShortAnswerType().markingMode()).isEqualTo(QuestionType.MarkingMode.AUTOMATIC);
        assertThatThrownBy(() -> type.mark(Map.of(), Map.of(), Map.of(), BigDecimal.TEN, BigDecimal.ZERO, "text")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void bodyHoldsOnlyTheWordLimitAndThatIsAllCandidatesSee() {
        assertThat(type.body(Map.of()).body()).isEqualTo(Map.of("maxWords", 500));
        assertThat(type.body(Map.of("maxWords", 150)).body()).isEqualTo(Map.of("maxWords", 150));
        for (Object bad : List.of(9, 2501, 12.5, "x")) assertThatThrownBy(() -> type.body(Map.of("maxWords", bad))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> type.body(Map.of("modelAnswer", "leak"))).isInstanceOf(ApiException.class);
        assertThat(type.deliveryProjection(Map.of("maxWords", 150))).isEqualTo(Map.of("maxWords", 150));
    }

    @Test
    void theModelAnswerIsOptionalKeyMaterial() {
        QuestionType.Body body = type.body(Map.of());
        assertThat(type.key(Map.of(), body)).isEmpty();
        assertThat(type.key(Map.of("modelAnswer", "  Stacks are LIFO.  "), body)).isEqualTo(Map.of("modelAnswer", "Stacks are LIFO."));
        assertThatThrownBy(() -> type.key(Map.of("modelAnswer", "x".repeat(20_001)), body)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> type.key(Map.of("answers", List.of()), body)).isInstanceOf(ApiException.class);
    }

    @Test
    void aRubricHasNamedCriteriaWhoseWeightsAddUpTo100() {
        assertThat(type.marking(Map.of())).isEmpty();
        Map<String, Object> m = type.marking(Map.of("rubric", List.of(
                Map.of("title", "Correctness", "description", "Facts are right", "weight", 60), Map.of("title", "Clarity", "weight", 40))));
        @SuppressWarnings("unchecked") List<Map<String, Object>> rubric = (List<Map<String, Object>>) m.get("rubric");
        assertThat(rubric).extracting(c -> c.get("id")).containsExactly("C1", "C2");
        assertThat(rubric.get(1)).doesNotContainKey("description");
        for (List<?> bad : List.<List<?>>of(
                List.<Map<String, Object>>of(Map.of("title", "A", "weight", 60)),                                       // sums to 60
                List.of(Map.of("title", "A", "weight", 50), Map.of("title", "a", "weight", 50)),                       // duplicate
                List.of(Map.of("title", "", "weight", 100)),                                                           // no title
                List.of(Map.<String, Object>of("title", "A")),                                                          // no weight
                List.of(Map.of("title", "A", "weight", 100, "maxMarks", 5)),                                           // unknown property
                List.<Map<String, Object>>of()))                                                                        // empty
            assertThatThrownBy(() -> type.marking(Map.of("rubric", bad))).isInstanceOf(ApiException.class);
    }

    @Test
    void responsesAreTextWithinTheWordLimit() {
        Map<String, Object> body = Map.of("maxWords", 10);
        assertThat(type.acceptsResponse(body, "one two three")).isTrue();
        assertThat(type.acceptsResponse(body, "  spaced\n\nout   words  ")).isTrue();
        assertThat(type.acceptsResponse(body, "w ".repeat(10))).isTrue();
        assertThat(type.acceptsResponse(body, "w ".repeat(11))).isFalse();
        assertThat(type.acceptsResponse(body, 42)).isFalse();
        assertThat(type.acceptsResponse(body, List.of("a"))).isFalse();
        assertThat(DescriptiveType.words("")).isZero();
    }
}
