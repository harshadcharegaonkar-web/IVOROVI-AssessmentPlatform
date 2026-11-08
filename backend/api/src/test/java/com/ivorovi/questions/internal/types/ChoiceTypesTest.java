package com.ivorovi.questions.internal.types;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ivorovi.platform.web.ApiException;
import com.ivorovi.questions.internal.ContentFormat;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChoiceTypesTest {

    private static Map<String, Object> option(String id, String source) {
        return Map.of("id", id, "content", ContentFormat.fromSource(source));
    }

    @Test
    void formulasDifferingByOneSymbolAreDifferentOptionsButIdenticalOptionsAreRefused() {
        var single = new ChoiceTypes.Single();
        assertThat(single.body(Map.of("options", List.of(option("A", "$g t$"), option("B", "$g / t$"), option("C", "$g + t$")))).optionIds())
                .containsExactly("A", "B", "C");
        assertThatThrownBy(() -> single.body(Map.of("options", List.of(option("A", "$x^2$"), option("B", "$x^2$"))))).isInstanceOf(ApiException.class);
    }

    @Test
    void duplicateDetectionKeepsMathematicalSymbols() {
        assertThat(ContentFormat.normalize("x/2")).isNotEqualTo(ContentFormat.normalize("x 2"));
        assertThat(ContentFormat.normalize("Which planet is largest?")).isEqualTo(ContentFormat.normalize("which  planet is largest"));
    }

    @Test
    void multipleChoicePartialAndAllOrNothing() {
        var multi = new ChoiceTypes.Multiple();
        var body = multi.body(Map.of("options", List.of(option("A", "2"), option("B", "4"), option("C", "5"))));
        var key = multi.key(Map.of("correct", List.of("A", "C")), body);
        var partial = multi.marking(Map.of("mode", "PARTIAL"));
        assertThat(multi.mark(body.body(), key, partial, new BigDecimal("2"), BigDecimal.ZERO, List.of("A")).outcome()).isEqualTo(QuestionType.Outcome.PARTIAL);
        assertThat(multi.mark(body.body(), key, multi.marking(Map.of()), new BigDecimal("2"), BigDecimal.ZERO, List.of("A")).outcome())
                .isEqualTo(QuestionType.Outcome.INCORRECT);
        assertThat(multi.mark(body.body(), key, partial, new BigDecimal("2"), BigDecimal.ZERO, List.of("A", "C")).outcome()).isEqualTo(QuestionType.Outcome.CORRECT);
    }
}
