package com.ivorovi.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.ivorovi.platform.audit.AuditHasher;
import com.ivorovi.platform.audit.AuditOutcome;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditHasherTest {

    private final UUID tenant = UUID.randomUUID();
    private final Instant at = Instant.parse("2026-11-02T10:00:00.123456Z");

    private String hash(String prev, String action, Map<String, String> details) {
        return AuditHasher.hash(prev, tenant, 1, at, null, "sub", action, "t", "1", AuditOutcome.SUCCESS, details);
    }

    @Test
    void isDeterministicAndIndependentOfDetailOrder() {
        Map<String, String> a = new LinkedHashMap<>(); a.put("x", "1"); a.put("y", "2");
        Map<String, String> b = new LinkedHashMap<>(); b.put("y", "2"); b.put("x", "1");
        assertThat(hash(AuditHasher.GENESIS, "act", a)).isEqualTo(hash(AuditHasher.GENESIS, "act", b)).hasSize(64);
    }

    @Test
    void changesWithContentOrPredecessor() {
        String base = hash(AuditHasher.GENESIS, "act", Map.of("x", "1"));
        assertThat(hash(AuditHasher.GENESIS, "act", Map.of("x", "2"))).isNotEqualTo(base);
        assertThat(hash(AuditHasher.GENESIS, "other", Map.of("x", "1"))).isNotEqualTo(base);
        assertThat(hash("f".repeat(64), "act", Map.of("x", "1"))).isNotEqualTo(base);
    }

    @Test
    void fieldBoundariesAreUnambiguous() {
        assertThat(AuditHasher.hash(AuditHasher.GENESIS, tenant, 1, at, null, "ab", "c", null, null, AuditOutcome.SUCCESS, Map.of()))
                .isNotEqualTo(AuditHasher.hash(AuditHasher.GENESIS, tenant, 1, at, null, "a", "bc", null, null, AuditOutcome.SUCCESS, Map.of()));
    }
}
