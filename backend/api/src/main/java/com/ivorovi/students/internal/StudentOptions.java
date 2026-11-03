package com.ivorovi.students.internal;

import static com.ivorovi.students.internal.StudentAccess.actor;
import static com.ivorovi.students.internal.StudentAccess.conflict;
import static com.ivorovi.students.internal.StudentAccess.tenant;

import com.ivorovi.platform.ids.Ids;
import com.ivorovi.platform.security.Permission;
import com.ivorovi.platform.web.ApiException;
import com.ivorovi.students.internal.StudentService.Option;
import com.ivorovi.students.internal.StudentService.OptionKind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Configurable option lists: built-in admission types, admission categories and document categories, plus custom
 * options defined by the organization (System Settings permission). Custom options can be deactivated, never deleted,
 * so historical records keep their codes.
 */
@Component
class StudentOptions {

    static final Map<OptionKind, Map<String, String>> BUILT_IN = Map.of(
            OptionKind.ADMISSION_TYPE, ordered("REGULAR", "Regular admission", "LATERAL_ENTRY", "Lateral entry", "TRANSFER", "Transfer admission"),
            OptionKind.ADMISSION_CATEGORY, ordered("GENERAL", "General", "RESERVED", "Reserved category", "MANAGEMENT", "Management quota",
                    "SPONSORED", "Sponsored", "INTERNATIONAL", "International", "OTHER", "Other"),
            OptionKind.DOCUMENT_CATEGORY, ordered("IDENTITY_PROOF", "Identity proof", "ACADEMIC_CERTIFICATE", "Previous academic certificate",
                    "TRANSFER_CERTIFICATE", "Transfer certificate", "MIGRATION_CERTIFICATE", "Migration certificate", "PHOTOGRAPH", "Photograph",
                    "OTHER", "Other document"));

    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,39}$");

    private final JdbcClient jdbc;
    private final StudentAccess access;

    StudentOptions(JdbcClient jdbc, StudentAccess access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    List<Option> all() {
        List<Option> out = new ArrayList<>();
        for (OptionKind k : OptionKind.values()) BUILT_IN.get(k).forEach((code, label) -> out.add(new Option(k, code, label, true, true)));
        out.addAll(jdbc.sql("SELECT kind, code, label, active FROM student_option ORDER BY kind, label")
                .query((rs, n) -> new Option(OptionKind.valueOf(rs.getString(1)), rs.getString(2), rs.getString(3), false, rs.getBoolean(4))).list());
        return out;
    }

    String label(OptionKind kind, String code) {
        if (code == null) return null;
        String b = BUILT_IN.get(kind).get(code);
        if (b != null) return b;
        return jdbc.sql("SELECT label FROM student_option WHERE kind = ? AND code = ?").params(kind.name(), code).query(String.class).optional().orElse(code);
    }

    /** A code is valid when it is built in or an active custom option of the organization. */
    void require(OptionKind kind, String code, String field) {
        if (code == null || code.isBlank()) throw ApiException.badRequest("invalid_" + field, "Choose a value.");
        boolean ok = BUILT_IN.get(kind).containsKey(code) || jdbc.sql("SELECT count(*) FROM student_option WHERE kind = ? AND code = ? AND active")
                .params(kind.name(), code).query(Long.class).single() > 0;
        if (!ok) throw ApiException.badRequest("invalid_" + field, "Choose one of the configured values.");
    }

    Option add(OptionKind kind, String code, String label) {
        access.requireAt(Permission.TENANT_SETTINGS_MANAGE.key(), null);
        if (kind == null) throw ApiException.badRequest("invalid_kind", "Choose the list.");
        String c = code == null ? "" : code.trim().toUpperCase();
        if (!CODE.matcher(c).matches()) throw ApiException.badRequest("invalid_code", "Use 2–40 capital letters, digits and _ (starting with a letter).");
        if (BUILT_IN.get(kind).containsKey(c)) throw conflict("code_in_use", "This code is built in.");
        String l = StudentService.required("label", label, 100);
        try {
            jdbc.sql("INSERT INTO student_option (id, tenant_id, kind, code, label, created_by) VALUES (?, ?, ?, ?, ?, ?)")
                    .params(Ids.newId(), tenant(), kind.name(), c, l, actor()).update();
        } catch (DuplicateKeyException e) {
            throw conflict("code_in_use", "This code already exists.");
        }
        return new Option(kind, c, l, false, true);
    }

    void setActive(OptionKind kind, String code, boolean active) {
        access.requireAt(Permission.TENANT_SETTINGS_MANAGE.key(), null);
        int n = jdbc.sql("UPDATE student_option SET active = ? WHERE kind = ? AND code = ?").params(active, kind.name(), code).update();
        if (n == 0) throw ApiException.notFound("Option");
    }

    private static Map<String, String> ordered(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return java.util.Collections.unmodifiableMap(m);
    }
}
