package com.ivorovi.questions.internal.types;

import com.ivorovi.platform.web.ApiException;
import java.util.Map;
import java.util.Set;

final class Validation {

    private Validation() {}

    static ApiException invalid(String path, String what) {
        return ApiException.badRequest("invalid_question", path + ": " + what + ".");
    }

    static void allowKeys(Map<?, ?> m, String path, String... allowed) {
        Set<String> ok = Set.of(allowed);
        for (Object k : m.keySet()) if (!ok.contains(String.valueOf(k))) throw invalid(path, "unknown property '" + k + "'");
    }

    static Map<?, ?> map(Object v, String path) {
        if (v == null) return Map.of();
        if (!(v instanceof Map<?, ?> m)) throw invalid(path, "object expected");
        return m;
    }

    static int intIn(Object v, String path, int min, int max, int dflt) {
        if (v == null) return dflt;
        if (!(v instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue())) throw invalid(path, "whole number expected");
        int i = n.intValue();
        if (i < min || i > max) throw invalid(path, "between " + min + " and " + max);
        return i;
    }
}
