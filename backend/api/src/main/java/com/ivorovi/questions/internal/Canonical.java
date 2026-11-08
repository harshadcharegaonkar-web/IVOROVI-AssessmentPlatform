package com.ivorovi.questions.internal;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.json.JsonMapper;

/** Canonical JSON (sorted keys, plain decimals) and SHA-256, for review and duplicate hashes. */
public final class Canonical {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private Canonical() {}

    static Object canonical(Object v) {
        if (v instanceof Map<?, ?> m) {
            TreeMap<String, Object> t = new TreeMap<>();
            m.forEach((k, val) -> t.put(String.valueOf(k), canonical(val)));
            return t;
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            for (Object o : l) out.add(canonical(o));
            return out;
        }
        if (v instanceof BigDecimal b) return b.stripTrailingZeros().toPlainString();
        if (v instanceof java.util.UUID u) return u.toString();
        return v;
    }

    public static String json(Object v) {
        return JSON.writeValueAsString(canonical(v));
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
