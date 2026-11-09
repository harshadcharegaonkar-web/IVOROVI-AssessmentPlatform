package com.ivorovi.assessments.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Q-2.2-4: papers are reproducible from their seed, draws are unbiased, rules never repeat a question, `shuffle:false` is honoured. */
class PaperGeneratorTest {

    private static final UUID VERSION = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final List<String> ABCD = List.of("A", "B", "C", "D");

    private static byte[] seed(int n) {
        byte[] s = new byte[32];
        s[0] = (byte) n;
        s[1] = (byte) (n >> 8);
        s[31] = 7;
        return s;
    }

    private static UUID id(int n) {
        return UUID.fromString(String.format("00000000-0000-7000-8000-%012d", n));
    }

    private static List<PaperGenerator.Candidate> pool(int from, int size) {
        List<PaperGenerator.Candidate> out = new ArrayList<>();
        for (int i = from; i < from + size; i++) out.add(new PaperGenerator.Candidate(id(i), id(100_000 + i), ABCD, true));
        return out;
    }

    private static PaperGenerator.Rule rule(int n, int count, List<PaperGenerator.Candidate> pool) {
        return new PaperGenerator.Rule(id(900_000 + n), count, BigDecimal.ONE, BigDecimal.ZERO, pool);
    }

    @Test
    void theSameSeedReproducesTheSamePaper() {
        var fixed = List.of(new PaperGenerator.Fixed(id(1), id(100_001), BigDecimal.TWO, BigDecimal.ZERO, ABCD, true));
        var rules = List.of(rule(1, 5, pool(10, 40)));
        var a = PaperGenerator.generate(VERSION, fixed, rules, true, true, seed(1));
        var b = PaperGenerator.generate(VERSION, fixed, List.of(rule(1, 5, new ArrayList<>(pool(10, 40)).reversed())), true, true, seed(1));
        assertThat(a.entries()).isEqualTo(b.entries());                // pool order does not matter (sorted by id)
        assertThat(a.hash()).isEqualTo(b.hash()).hasSize(64);
        assertThat(a.totalMarks()).isEqualByComparingTo("7");
    }

    @Test
    void differentSeedsGiveDifferentPapers() {
        var rules = List.of(rule(1, 10, pool(10, 200)));
        Set<String> hashes = new HashSet<>();
        for (int i = 0; i < 50; i++) hashes.add(PaperGenerator.generate(VERSION, List.of(), rules, true, true, seed(i)).hash());
        assertThat(hashes).hasSize(50);
    }

    @Test
    void theHashBindsTheVersion() {
        var rules = List.of(rule(1, 3, pool(10, 10)));
        assertThat(PaperGenerator.generate(VERSION, List.of(), rules, false, false, seed(3)).hash())
                .isNotEqualTo(PaperGenerator.generate(id(2), List.of(), rules, false, false, seed(3)).hash());
    }

    @Test
    void drawsAreApproximatelyUniform() {
        // 4 000 papers drawing 1 of 8: each question expected 500 times; a biased draw (e.g. modulo bias) would skew this
        var rules = List.of(rule(1, 1, pool(10, 8)));
        Map<UUID, Integer> counts = new HashMap<>();
        for (int i = 0; i < 4000; i++) {
            var p = PaperGenerator.generate(VERSION, List.of(), rules, false, false, seed(i));
            counts.merge(p.entries().getFirst().questionVersionId(), 1, Integer::sum);
        }
        assertThat(counts).hasSize(8);
        double chi = counts.values().stream().mapToDouble(c -> Math.pow(c - 500.0, 2) / 500.0).sum();
        assertThat(chi).isLessThan(24.3);                              // chi-square, 7 d.f., p = 0.001
    }

    @Test
    void rulesNeverRepeatAQuestionAndFixedQuestionsAreExcludedFromPools() {
        var fixed = List.of(new PaperGenerator.Fixed(id(10), id(100_010), BigDecimal.ONE, BigDecimal.ZERO, ABCD, true));
        var rules = List.of(rule(1, 5, pool(10, 6)), rule(2, 5, pool(10, 11)));   // overlapping pools; 11 distinct, 1 fixed
        for (int i = 0; i < 200; i++) {
            var p = PaperGenerator.generate(VERSION, fixed, rules, true, true, seed(i));
            assertThat(p.entries()).hasSize(11);
            assertThat(p.entries().stream().map(PaperGenerator.Entry::questionVersionId).distinct().count()).isEqualTo(11);
        }
    }

    @Test
    void optionsAreShuffledOnlyWhenAllowed() {
        var fixed = List.of(
                new PaperGenerator.Fixed(id(1), id(100_001), BigDecimal.ONE, BigDecimal.ZERO, ABCD, true),
                new PaperGenerator.Fixed(id(2), id(100_002), BigDecimal.ONE, BigDecimal.ZERO, ABCD, false));   // "shuffle": false
        boolean firstMoved = false;
        for (int i = 0; i < 50; i++) {
            var p = PaperGenerator.generate(VERSION, fixed, List.of(), false, true, seed(i));
            assertThat(p.entries().get(0).options()).containsExactlyInAnyOrderElementsOf(ABCD);
            assertThat(p.entries().get(1).options()).containsExactlyElementsOf(ABCD);
            firstMoved |= !p.entries().get(0).options().equals(ABCD);
        }
        assertThat(firstMoved).isTrue();
        // option shuffling off for the assessment: every order stays as authored, but is still recorded
        var off = PaperGenerator.generate(VERSION, fixed, List.of(), false, false, seed(1));
        assertThat(off.entries().get(0).options()).containsExactlyElementsOf(ABCD);
    }

    @Test
    void questionOrderIsKeptWithoutQuestionShuffling() {
        var fixed = List.of(
                new PaperGenerator.Fixed(id(1), id(100_001), BigDecimal.ONE, BigDecimal.ZERO, null, true),
                new PaperGenerator.Fixed(id(2), id(100_002), BigDecimal.ONE, BigDecimal.ZERO, null, true),
                new PaperGenerator.Fixed(id(3), id(100_003), BigDecimal.ONE, BigDecimal.ZERO, null, true));
        var p = PaperGenerator.generate(VERSION, fixed, List.of(), false, true, seed(9));
        assertThat(p.entries()).extracting(PaperGenerator.Entry::questionVersionId).containsExactly(id(1), id(2), id(3));
        assertThat(p.entries()).allSatisfy(e -> assertThat(e.options()).isNull());
    }

    @Test
    void anExhaustedPoolIsRefused() {
        assertThatThrownBy(() -> PaperGenerator.generate(VERSION, List.of(), List.of(rule(1, 5, pool(10, 4))), false, false, seed(1)))
                .isInstanceOf(PaperGenerator.PoolExhausted.class);
    }

    @Test
    void storedJsonCarriesEverythingNeededToDeliverAndScore() {
        var p = PaperGenerator.generate(VERSION, List.of(), List.of(rule(1, 2, pool(10, 5))), false, true, seed(4));
        List<Map<String, Object>> json = PaperGenerator.toJson(p.entries());
        assertThat(json).hasSize(2).allSatisfy(m -> assertThat(m).containsKeys("questionVersionId", "marks", "negativeMarks", "options", "source"));
        assertThat(json.getFirst().get("source").toString()).startsWith("RULE:");
    }
}
