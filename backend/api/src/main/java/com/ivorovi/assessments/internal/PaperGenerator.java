package com.ivorovi.assessments.internal;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Deterministic paper generation (Q-2.2-4). Given the same inputs and seed it always produces the same paper and hash.
 * Randomness: HMAC-SHA-256(seed, counter) as a deterministic stream, unbiased integers by rejection sampling. Fixed
 * questions first (in order), then each rule draws without replacement from its pool (sorted, excluding questions
 * already used), then optional question shuffle; option order is shuffled per choice question when allowed.
 */
public final class PaperGenerator {

    public static final int VERSION = 1;

    public record Fixed(UUID questionVersionId, UUID questionId, BigDecimal marks, BigDecimal negativeMarks, List<String> optionIds, boolean optionsShufflable) {}

    public record Candidate(UUID questionVersionId, UUID questionId, List<String> optionIds, boolean optionsShufflable) {}

    public record Rule(UUID ruleId, int count, BigDecimal marks, BigDecimal negativeMarks, List<Candidate> pool) {}

    public record Entry(UUID questionVersionId, BigDecimal marks, BigDecimal negativeMarks, List<String> options, String source) {}

    public record Paper(List<Entry> entries, BigDecimal totalMarks, String hash) {}

    /** Thrown when a rule's pool cannot supply its draws (validation prevents this for publishable versions). */
    public static final class PoolExhausted extends RuntimeException {
        public PoolExhausted(String message) {
            super(message);
        }
    }

    private PaperGenerator() {}

    /** Deterministic random stream keyed by the seed. */
    static final class Stream {
        private final Mac mac;
        private long counter;
        private ByteBuffer block = ByteBuffer.allocate(0);

        Stream(byte[] seed) {
            try {
                mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(seed, "HmacSHA256"));
            } catch (NoSuchAlgorithmException | InvalidKeyException e) {
                throw new IllegalStateException(e);
            }
        }

        private int nextIntRaw() {
            if (block.remaining() < 4) {
                block = ByteBuffer.wrap(mac.doFinal(ByteBuffer.allocate(8).putLong(counter++).array()));
            }
            return block.getInt();
        }

        /** Uniform integer in [0, bound). */
        int nextInt(int bound) {
            if (bound <= 0) throw new IllegalArgumentException("bound");
            long range = 1L << 32;
            long limit = range - (range % bound);
            while (true) {
                long v = Integer.toUnsignedLong(nextIntRaw());
                if (v < limit) return (int) (v % bound);
            }
        }
    }

    public static Paper generate(UUID versionId, List<Fixed> fixed, List<Rule> rules, boolean shuffleQuestions, boolean shuffleOptions, byte[] seed) {
        Stream rnd = new Stream(seed);
        List<Entry> entries = new ArrayList<>();
        List<List<String>> optionLists = new ArrayList<>();
        List<Boolean> shufflable = new ArrayList<>();
        Set<UUID> used = new HashSet<>();
        for (Fixed f : fixed) {
            used.add(f.questionId());
            entries.add(new Entry(f.questionVersionId(), f.marks(), f.negativeMarks(), null, "FIXED"));
            optionLists.add(f.optionIds());
            shufflable.add(f.optionsShufflable());
        }
        for (Rule r : rules) {
            List<Candidate> pool = new ArrayList<>(r.pool().stream().filter(c -> !used.contains(c.questionId())).toList());
            pool.sort((a, b) -> a.questionVersionId().compareTo(b.questionVersionId()));
            if (pool.size() < r.count()) throw new PoolExhausted("Rule " + r.ruleId() + " needs " + r.count() + " questions; only " + pool.size() + " remain");
            for (int i = 0; i < r.count(); i++) {                      // partial Fisher–Yates
                int j = i + rnd.nextInt(pool.size() - i);
                Candidate c = pool.get(j);
                pool.set(j, pool.get(i));
                pool.set(i, c);
                used.add(c.questionId());
                entries.add(new Entry(c.questionVersionId(), r.marks(), r.negativeMarks(), null, "RULE:" + r.ruleId()));
                optionLists.add(c.optionIds());
                shufflable.add(c.optionsShufflable());
            }
        }
        // option order per question (recorded even when unchanged)
        List<Entry> withOptions = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            List<String> opts = optionLists.get(i) == null ? List.of() : new ArrayList<>(optionLists.get(i));
            if (shuffleOptions && shufflable.get(i)) shuffle(opts, rnd);
            Entry e = entries.get(i);
            withOptions.add(new Entry(e.questionVersionId(), e.marks(), e.negativeMarks(), opts.isEmpty() ? null : List.copyOf(opts), e.source()));
        }
        if (shuffleQuestions) shuffle(withOptions, rnd);
        BigDecimal total = withOptions.stream().map(Entry::marks).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Paper(List.copyOf(withOptions), total, hash(versionId, withOptions));
    }

    private static <T> void shuffle(List<T> list, Stream rnd) {
        for (int i = list.size() - 1; i > 0; i--) {
            int j = rnd.nextInt(i + 1);
            T t = list.get(i);
            list.set(i, list.get(j));
            list.set(j, t);
        }
    }

    /** Canonical item list as stored in the paper row. */
    public static List<Map<String, Object>> toJson(List<Entry> entries) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Entry e : entries) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("questionVersionId", e.questionVersionId().toString());
            m.put("marks", e.marks().stripTrailingZeros().toPlainString());
            m.put("negativeMarks", e.negativeMarks().stripTrailingZeros().toPlainString());
            if (e.options() != null) m.put("options", e.options());
            m.put("source", e.source());
            out.add(m);
        }
        return out;
    }

    static String hash(UUID versionId, List<Entry> entries) {
        StringBuilder sb = new StringBuilder("ivorovi.paper.v").append(VERSION).append('|').append(versionId);
        for (Map<String, Object> m : toJson(entries)) sb.append('|').append(new TreeMap<>(m));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
