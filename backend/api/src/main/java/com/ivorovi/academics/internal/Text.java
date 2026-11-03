package com.ivorovi.academics.internal;

import com.ivorovi.platform.web.ApiException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.regex.Pattern;

/** Input normalisation shared by the academic services. */
final class Text {

    private static final Pattern CODE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._/-]{0,39}$");

    private Text() {}

    static String code(String raw) {
        String c = raw == null ? "" : raw.trim();
        if (!CODE.matcher(c).matches() || c.length() > 30) {
            throw ApiException.badRequest("invalid_code", "Use up to 30 letters, digits and . _ / - (starting with a letter or digit).");
        }
        return c;
    }

    static String batchCode(String raw) {
        String c = raw == null ? "" : raw.trim();
        if (!CODE.matcher(c).matches()) {
            throw ApiException.badRequest("invalid_code", "Use up to 40 letters, digits and . _ / - (starting with a letter or digit).");
        }
        return c;
    }

    static String required(String field, String raw, int max) {
        String v = optional(raw, max);
        if (v == null) throw ApiException.badRequest("invalid_" + field, "This field is required.");
        return v;
    }

    static String optional(String raw, int max) {
        if (raw == null || raw.isBlank()) return null;
        String v = raw.trim();
        if (v.length() > max) throw ApiException.badRequest("too_long", "Use at most " + max + " characters.");
        return v;
    }

    static String like(String q) {
        return "%" + q.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    static Instant instant(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }
}
