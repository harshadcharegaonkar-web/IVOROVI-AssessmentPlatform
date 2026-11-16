package com.ivorovi.assessments.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Add-on C: generator version 2 keeps sections in order, shuffles only within a section and never repeats a question. */
class PaperGeneratorSectionsTest {

    private static final UUID VERSION = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final List<String> ABCD = List.of("A", "B", "C", "D");

    private static UUID id(int n) {
        return UUID.fromString(String.format("00000000-0000-7000-8000-%012d", n));
    }

    private static byte[] seed(int n) {
        byte[] s = new byte[32];
        s[0] = (byte) n;
        s[1] = (byte) (n >> 8);
        s[31] = 9;
        return s;
    }

    private static List<PaperGenerator.Candidate> pool(int from, int size) {
        List<PaperGenerator.Candidate> out = new ArrayList<>();
        for (int i = from; i < from + size; i++) out.add(new PaperGenerator.Candidate(id(i), id(100_000 + i), ABCD, true));
        return out;
    }

    private static PaperGenerator.Fixed fixed(int n, int section) {
        return new PaperGenerator.Fixed(id(n), id(100_000 + n), BigDecimal.ONE, BigDecimal.ZERO, ABCD, true, section);
    }

    private static PaperGenerator.Rule rule(int n, int count, List<PaperGenerator.Candidate> pool, int section) {
        return new PaperGenerator.Rule(id(900_000 + n), count, BigDecimal.TWO, BigDecimal.ZERO, pool, section);
    }

    private static final List<PaperGenerator.Fixed> FIXED = List.of(fixed(1, 2), fixed(2, 1), fixed(3, 2), fixed(4, 1));
    private static final List<PaperGenerator.Rule> RULES = List.of(rule(1, 4, pool(10, 20), 1), rule(2, 3, pool(40, 10), 2), rule(3, 2, pool(60, 5), 3));

    @Test
    void sectionsStayInOrderAndQuestionsShuffleOnlyWithinTheirSection() {
        Set<List<UUID>> firstSectionOrders = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            var p = PaperGenerator.generateSectioned(VERSION, List.of(1, 2, 3), FIXED, RULES, true, true, seed(i));
            assertThat(p.generatorVersion()).isEqualTo(2);
            assertThat(p.entries()).hasSize(4 + 4 + 3 + 2);
            assertThat(p.entries()).extracting(PaperGenerator.Entry::sectionNo).containsExactly(1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 3, 3);
            assertThat(p.entries().subList(0, 6)).extracting(PaperGenerator.Entry::questionVersionId).contains(id(2), id(4));
            assertThat(p.entries().subList(6, 11)).extracting(PaperGenerator.Entry::questionVersionId).contains(id(1), id(3));
            assertThat(p.entries().stream().map(PaperGenerator.Entry::questionVersionId).distinct().count()).isEqualTo(13);
            firstSectionOrders.add(p.entries().subList(0, 6).stream().map(PaperGenerator.Entry::questionVersionId).toList());
        }
        assertThat(firstSectionOrders.size()).isGreaterThan(50);          // shuffled within the section
    }

    @Test
    void theDeclaredSectionOrderIsTheOrderOfThePaper() {
        var p = PaperGenerator.generateSectioned(VERSION, List.of(3, 1, 2), FIXED, RULES, false, false, seed(1));
        assertThat(p.entries()).extracting(PaperGenerator.Entry::sectionNo).containsExactly(3, 3, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2);
        // without question shuffling, fixed questions come first in their section, in their order
        assertThat(p.entries().get(2).questionVersionId()).isEqualTo(id(2));
        assertThat(p.entries().get(3).questionVersionId()).isEqualTo(id(4));
    }

    @Test
    void reproducibleAndDomainSeparatedFromVersionOne() {
        var a = PaperGenerator.generateSectioned(VERSION, List.of(1, 2, 3), FIXED, RULES, true, true, seed(5));
        var b = PaperGenerator.generateSectioned(VERSION, List.of(1, 2, 3), FIXED, RULES, true, true, seed(5));
        assertThat(a).isEqualTo(b);
        // one section holding everything: same draws as v1 would make, but a different hash (v2 records sections)
        var single = FIXED.stream().map(f -> new PaperGenerator.Fixed(f.questionVersionId(), f.questionId(), f.marks(), f.negativeMarks(), f.optionIds(),
                f.optionsShufflable(), 1)).toList();
        var rulesOne = RULES.stream().map(r -> new PaperGenerator.Rule(r.ruleId(), r.count(), r.marks(), r.negativeMarks(), r.pool(), 1)).toList();
        var v2 = PaperGenerator.generateSectioned(VERSION, List.of(1), single, rulesOne, false, false, seed(5));
        var v1 = PaperGenerator.generate(VERSION, single, rulesOne, false, false, seed(5));
        assertThat(v2.entries()).extracting(PaperGenerator.Entry::questionVersionId).containsExactlyElementsOf(v1.entries().stream().map(PaperGenerator.Entry::questionVersionId).toList());
        assertThat(v2.hash()).isNotEqualTo(v1.hash());
        assertThat(PaperGenerator.toJson(v2.entries())).allSatisfy(m -> assertThat(m).containsEntry("section", 1));
    }

    @Test
    void aQuestionInTwoSectionsPoolsIsDrawnAtMostOnce() {
        var overlapping = List.of(rule(1, 5, pool(10, 8), 1), rule(2, 5, pool(10, 10), 2));   // 10 distinct questions in total
        for (int i = 0; i < 200; i++) {
            var p = PaperGenerator.generateSectioned(VERSION, List.of(1, 2), List.of(), overlapping, true, true, seed(i));
            assertThat(p.entries().stream().map(PaperGenerator.Entry::questionVersionId).distinct().count()).isEqualTo(10);
        }
    }

    @Test
    void fixedQuestionsOfAnySectionAreExcludedFromEveryPool() {
        var p = PaperGenerator.generateSectioned(VERSION, List.of(1, 2), List.of(fixed(10, 2)), List.of(rule(1, 3, pool(10, 4), 1)), false, false, seed(2));
        assertThat(p.entries().subList(0, 3)).extracting(PaperGenerator.Entry::questionVersionId).doesNotContain(id(10));
    }

    @Test
    void undeclaredSectionsAndExhaustedPoolsAreRefused() {
        assertThatThrownBy(() -> PaperGenerator.generateSectioned(VERSION, List.of(1), List.of(fixed(1, 2)), List.of(), false, false, seed(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaperGenerator.generateSectioned(VERSION, List.of(1, 1), List.of(), List.of(), false, false, seed(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaperGenerator.generateSectioned(VERSION, List.of(1), List.of(), List.of(rule(1, 5, pool(10, 4), 1)), false, false, seed(1)))
                .isInstanceOf(PaperGenerator.PoolExhausted.class);
    }
}
