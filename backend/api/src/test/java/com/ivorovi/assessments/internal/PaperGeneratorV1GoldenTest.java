package com.ivorovi.assessments.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Generator version 1 must regenerate every existing paper bit-identically (condition of the add-on C approval). The
 * hashes below were produced by the generator on main before sections existed (commit 68ed941) from the fixture inputs
 * of {@link PaperGeneratorTest}; they must never change.
 */
class PaperGeneratorV1GoldenTest {

    private static final List<String> ABCD = List.of("A", "B", "C", "D");

    private static UUID id(int n) {
        return UUID.fromString(String.format("00000000-0000-7000-8000-%012d", n));
    }

    private static byte[] seed(int n) {
        byte[] s = new byte[32];
        s[0] = (byte) n;
        s[1] = (byte) (n >> 8);
        s[31] = 7;
        return s;
    }

    private static List<PaperGenerator.Candidate> pool(int from, int size) {
        List<PaperGenerator.Candidate> out = new ArrayList<>();
        for (int i = from; i < from + size; i++) out.add(new PaperGenerator.Candidate(id(i), id(100_000 + i), ABCD, true));
        return out;
    }

    static final List<PaperGenerator.Fixed> FIXED = List.of(
            new PaperGenerator.Fixed(id(1), id(100_001), BigDecimal.TWO, BigDecimal.ZERO, ABCD, true),
            new PaperGenerator.Fixed(id(2), id(100_002), new BigDecimal("1.50"), new BigDecimal("0.25"), ABCD, false),
            new PaperGenerator.Fixed(id(3), id(100_003), BigDecimal.ONE, BigDecimal.ZERO, null, true));

    static final List<PaperGenerator.Rule> RULES = List.of(
            new PaperGenerator.Rule(id(900_001), 5, BigDecimal.ONE, BigDecimal.ZERO, pool(10, 40)),
            new PaperGenerator.Rule(id(900_002), 3, BigDecimal.TWO, new BigDecimal("0.5"), pool(60, 12)));

    @ParameterizedTest(name = "shuffleQuestions={0} shuffleOptions={1} seed={2}")
    @CsvSource({
            "false, false, 1, 1f41e62e569a9aafd5740737a0db509b7c4404192428a4dc00a769d2a7dda4ab",
            "false, false, 2, 12eadbcb7d24bfc3903f95780b8561f8974f2cd7090c51dc1fb8a90c30b526ee",
            "false, false, 300, 4063b301636ad647123f4ab94ead43df6a6fc54a834f8a0171a9b858c249683b",
            "false, true, 1, cbe9b95218e2399e88403e2f9c97c25e70dd2893d3a86a07bd08037714dd66f8",
            "false, true, 2, 357bcfd6f18dbc46cf8176593abb5d2af5069804f0c36dd154b8849bc74174de",
            "false, true, 300, 74d2274f19caf202bdb40454ab3c43e3b3efe7b53a70518480791eb564b1e518",
            "true, false, 1, 7fdef8391f89f1bd7114de85d6841b1d5caaa41e24e10407df0222ef3715eef8",
            "true, false, 2, d730975c568b81a1ac0e4f897ece9ec017351e1d46a0b0d711d3a4cf0aaacba3",
            "true, false, 300, f8257f32632baf2b9471868a443241123f7d01b7003c92ec5abd1383d0e8e70b",
            "true, true, 1, e5c1af859fe630622c7560f09c89418dc3f4d6992e5a232bbb691bc1a3070e56",
            "true, true, 2, 98704a6eba645441fb770ed606016c53eb98a0c461122a51cd6252c5f71552b5",
            "true, true, 300, a143747805ee1ee6992bfa70a3ed5932961935c7f4fde4746fa523f513bc633a"})
    void versionOnePapersRegenerateBitIdentically(boolean shuffleQuestions, boolean shuffleOptions, int seed, String expected) {
        var paper = PaperGenerator.generate(id(1_000_001), FIXED, RULES, shuffleQuestions, shuffleOptions, seed(seed));
        assertThat(paper.hash()).isEqualTo(expected);
        assertThat(paper.generatorVersion()).isEqualTo(1);
        assertThat(paper.entries()).allSatisfy(e -> assertThat(e.sectionNo()).isNull());
        assertThat(PaperGenerator.toJson(paper.entries())).allSatisfy(m -> assertThat(m).doesNotContainKey("section"));
    }

    @Test
    void sectionNumbersOnFixturesDoNotChangeAVersionOnePaper() {
        // the v1 entry point ignores sections entirely: the same inputs tagged with sections still produce the v1 paper
        var tagged = FIXED.stream().map(f -> new PaperGenerator.Fixed(f.questionVersionId(), f.questionId(), f.marks(), f.negativeMarks(), f.optionIds(),
                f.optionsShufflable(), 2)).toList();
        assertThat(PaperGenerator.generate(id(1_000_001), tagged, RULES, true, true, seed(1)).hash())
                .isEqualTo("e5c1af859fe630622c7560f09c89418dc3f4d6992e5a232bbb691bc1a3070e56");
    }
}
