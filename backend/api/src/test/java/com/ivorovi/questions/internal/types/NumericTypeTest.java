package com.ivorovi.questions.internal.types;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ivorovi.platform.web.ApiException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Numeric evaluation: exact decimals, tolerance boundaries, precision, rounding/rejection, negatives, scientific notation, partial marks. */
class NumericTypeTest {

    private final NumericType type = new NumericType();
    private static final BigDecimal MARKS = new BigDecimal("4");
    private static final BigDecimal NEG = new BigDecimal("1");

    private QuestionType.Grade mark(int precision, Map<String, Object> rawKey, String response) {
        QuestionType.Body body = type.body(Map.of("precision", precision));
        Map<String, Object> key = type.key(rawKey, body);
        return type.mark(body.body(), key, Map.of(), MARKS, NEG, response);
    }

    private static Map<String, Object> key(String value, String tolType, String tol) {
        Map<String, Object> k = new LinkedHashMap<>();
        k.put("value", value);
        k.put("tolerance", Map.of("type", tolType, "value", tol));
        return k;
    }

    @Test
    void exactDecimalsDoNotSufferFromBinaryFloatingPoint() {
        // 0.1 + 0.2 = 0.30000000000000004 in binary floating point; here 0.3 must match a key of 0.3 with zero tolerance
        assertThat(mark(10, key("0.3", "ABSOLUTE", "0"), "0.3").outcome()).isEqualTo(QuestionType.Outcome.CORRECT);
        assertThat(mark(10, Map.of("value", "0.3000000001"), "0.3").outcome()).isEqualTo(QuestionType.Outcome.INCORRECT);
    }

    @ParameterizedTest(name = "abs tolerance 0.05 around 9.81: {0} -> {1}")
    @CsvSource({"9.81,CORRECT", "9.86,CORRECT", "9.76,CORRECT", "9.87,INCORRECT", "9.75,INCORRECT", "9.860,CORRECT"})
    void absoluteToleranceIsInclusiveAtBothBoundaries(String response, QuestionType.Outcome expected) {
        assertThat(mark(3, key("9.81", "ABSOLUTE", "0.05"), response).outcome()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "1% tolerance around 200: {0} -> {1}")
    @CsvSource({"198,CORRECT", "202,CORRECT", "197.99,INCORRECT", "202.01,INCORRECT", "200,CORRECT"})
    void percentageToleranceIsInclusive(String response, QuestionType.Outcome expected) {
        assertThat(mark(2, key("200", "PERCENT", "1"), response).outcome()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "1% tolerance around -50: {0} -> {1}")
    @CsvSource({"-50.5,CORRECT", "-49.5,CORRECT", "-50.51,INCORRECT", "50,INCORRECT", "-49.49,INCORRECT"})
    void negativeValuesUseTheMagnitudeForPercentTolerance(String response, QuestionType.Outcome expected) {
        assertThat(mark(2, key("-50", "PERCENT", "1"), response).outcome()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"6.02e23", "6.02E23", "602000000000000000000000", "60.2e22", "+6.02e+23"})
    void scientificNotationIsEquivalentToThePlainValue(String response) {
        assertThat(mark(0, key("6.02e23", "ABSOLUTE", "0"), response).outcome()).isEqualTo(QuestionType.Outcome.CORRECT);
    }

    @Test
    void smallScientificValues() {
        assertThat(mark(10, key("1.6e-9", "PERCENT", "0.5"), "1.6E-9").outcome()).isEqualTo(QuestionType.Outcome.CORRECT);
        assertThat(mark(10, key("1.6e-9", "PERCENT", "0.5"), "0.0000000016").outcome()).isEqualTo(QuestionType.Outcome.CORRECT);
    }

    @Test
    void roundHalfUpToTheConfiguredPrecision() {
        // precision 2: 3.145 -> 3.15, 3.144 -> 3.14 ; key 3.15 exact
        assertThat(mark(2, key("3.15", "ABSOLUTE", "0"), "3.145").outcome()).isEqualTo(QuestionType.Outcome.CORRECT);
        assertThat(mark(2, key("3.15", "ABSOLUTE", "0"), "3.1449").outcome()).isEqualTo(QuestionType.Outcome.INCORRECT);
        // negative half-up rounds away from zero: -2.345 -> -2.35
        assertThat(mark(2, key("-2.35", "ABSOLUTE", "0"), "-2.345").outcome()).isEqualTo(QuestionType.Outcome.CORRECT);
    }

    @Test
    void rejectExcessPrecisionMarksExtraDecimalsWrongButAllowsTrailingZeros() {
        Map<String, Object> k = key("3.14", "ABSOLUTE", "0");
        k.put("rounding", "REJECT_EXCESS_PRECISION");
        QuestionType.Grade g = mark(2, k, "3.141");
        assertThat(g.outcome()).isEqualTo(QuestionType.Outcome.INCORRECT);
        assertThat(g.awarded()).isEqualByComparingTo("-1");
        assertThat(mark(2, k, "3.140").outcome()).isEqualTo(QuestionType.Outcome.CORRECT);
        assertThat(mark(2, k, "3.14").outcome()).isEqualTo(QuestionType.Outcome.CORRECT);
    }

    @Test
    void alternativesAreAccepted() {
        Map<String, Object> k = key("0.5", "ABSOLUTE", "0");
        k.put("alternatives", List.of("50"));
        assertThat(mark(2, k, "50").outcome()).isEqualTo(QuestionType.Outcome.CORRECT);
        assertThat(mark(2, k, "0.5").outcome()).isEqualTo(QuestionType.Outcome.CORRECT);
        assertThat(mark(2, k, "5").outcome()).isEqualTo(QuestionType.Outcome.INCORRECT);
    }

    @Test
    void partialBandAwardsAPercentageAndItsBoundaryIsInclusive() {
        Map<String, Object> k = key("100", "ABSOLUTE", "1");
        k.put("partial", Map.of("tolerance", Map.of("type", "ABSOLUTE", "value", "5"), "percent", 50));
        QuestionType.Grade full = mark(1, k, "101");
        assertThat(full.outcome()).isEqualTo(QuestionType.Outcome.CORRECT);
        assertThat(full.awarded()).isEqualByComparingTo("4");
        QuestionType.Grade half = mark(1, k, "105");
        assertThat(half.outcome()).isEqualTo(QuestionType.Outcome.PARTIAL);
        assertThat(half.awarded()).isEqualByComparingTo("2");
        QuestionType.Grade wrong = mark(1, k, "105.1");
        assertThat(wrong.outcome()).isEqualTo(QuestionType.Outcome.INCORRECT);
        assertThat(wrong.awarded()).isEqualByComparingTo("-1");
    }

    @Test
    void partialBandMustBeWiderAndOfTheSameType() {
        Map<String, Object> k = key("100", "ABSOLUTE", "1");
        k.put("partial", Map.of("tolerance", Map.of("type", "ABSOLUTE", "value", "1"), "percent", 50));
        assertThatThrownBy(() -> mark(1, k, "100")).isInstanceOf(ApiException.class);
        k.put("partial", Map.of("tolerance", Map.of("type", "PERCENT", "value", "10"), "percent", 50));
        assertThatThrownBy(() -> mark(1, k, "100")).isInstanceOf(ApiException.class);
    }

    @ParameterizedTest(name = "invalid response \"{0}\"")
    @ValueSource(strings = {"abc", "1,5", "1.2.3", "0x1F", "NaN", "Infinity", "1e1234", "--1", "1e", " 1 2", "١٢"})
    void unparsableResponsesAreInvalidAndEarnNothing(String response) {
        QuestionType.Grade g = mark(2, key("12", "ABSOLUTE", "0"), response);
        assertThat(g.outcome()).isEqualTo(QuestionType.Outcome.INVALID);
        assertThat(g.awarded()).isEqualByComparingTo("0");
    }

    @Test
    void blankIsUnanswered() {
        assertThat(mark(2, key("12", "ABSOLUTE", "0"), " ").outcome()).isEqualTo(QuestionType.Outcome.UNANSWERED);
        assertThat(mark(2, key("12", "ABSOLUTE", "0"), null).outcome()).isEqualTo(QuestionType.Outcome.UNANSWERED);
    }

    @Test
    void keyValidation() {
        assertThatThrownBy(() -> type.key(Map.of("value", "1/2"), type.body(Map.of()))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> type.key(Map.of("value", "1", "tolerance", Map.of("type", "ABSOLUTE", "value", "-1")), type.body(Map.of())))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> type.key(Map.of("value", "1", "tolerance", Map.of("type", "PERCENT", "value", "101")), type.body(Map.of())))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> type.key(Map.of("value", "1", "alternatives", List.of("1", "2", "3", "4", "5", "6")), type.body(Map.of())))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> type.key(Map.of("value", "1", "script", "x"), type.body(Map.of()))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> type.body(Map.of("precision", 11))).isInstanceOf(ApiException.class);
    }

    @Test
    void deliveryProjectionHasNoKey() {
        QuestionType.Body body = type.body(Map.of("precision", 2, "unit", "m/s"));
        assertThat(type.deliveryProjection(body.body())).containsOnlyKeys("precision", "unit");
    }
}
