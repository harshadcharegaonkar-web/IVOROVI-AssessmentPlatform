package com.ivorovi.students.internal;

import com.ivorovi.platform.cache.ValkeyJsonCache;
import io.swagger.v3.oas.annotations.media.Schema;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Student dashboard KPIs from the caller's authorized students only (scope unit inside the caller's units). Cached per
 * organization, read scope and student generation; every student change bumps the generation after commit.
 */
@Service
public class StudentOverviewService {

    @Schema(name = "StudentGroupCount")
    public record GroupCount(UUID id, String label, long count) {}

    @Schema(name = "StudentKpis")
    public record Kpis(long total, long active, long newlyAdmitted, long prospective, long suspended, long withdrawn, long graduated,
                       long documentsAwaitingScan, List<GroupCount> byProgram, List<GroupCount> byCourse, List<GroupCount> byBatch,
                       List<GroupCount> byAcademicYear) {}

    /** "Newly admitted": admissions confirmed with an admission date in the last 30 days. */
    static final int NEW_ADMISSION_DAYS = 30;
    private static final Duration TTL = Duration.ofMinutes(5);

    private final JdbcClient jdbc;
    private final StudentAccess access;
    private final ValkeyJsonCache cache;

    StudentOverviewService(JdbcClient jdbc, StudentAccess access, ValkeyJsonCache cache) {
        this.jdbc = jdbc;
        this.access = access;
        this.cache = cache;
    }

    @Transactional(readOnly = true)
    public Kpis kpis() {
        access.requireAnyRead();
        String key = "ivorovi:students-kpis:t:" + StudentAccess.tenant() + ":g:" + access.generation() + ":s:" + hash(access.scopeKey());
        return cache.get("students-kpis", key, Kpis.class).orElseGet(() -> {
            Kpis k = compute();
            cache.put("students-kpis", key, k, TTL);
            return k;
        });
    }

    private Kpis compute() {
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE s.status <> 'CANCELLED'");
        if (!access.appendVisibility("su.path", where, params)) return new Kpis(0, 0, 0, 0, 0, 0, 0, 0, List.of(), List.of(), List.of(), List.of());
        String from = " FROM student s JOIN org_unit su ON su.id = s.scope_unit_id";
        var row = jdbc.sql("""
                SELECT count(*), count(*) FILTER (WHERE s.status IN ('ENROLLED', 'ACTIVE')), count(*) FILTER (WHERE s.status = 'PROSPECTIVE'),
                       count(*) FILTER (WHERE s.status = 'SUSPENDED'), count(*) FILTER (WHERE s.status = 'WITHDRAWN'),
                       count(*) FILTER (WHERE s.status IN ('GRADUATED', 'ALUMNI')),
                       count(*) FILTER (WHERE EXISTS (SELECT 1 FROM admission_record a WHERE a.student_id = s.id AND a.status = 'CONFIRMED'
                                                       AND a.admission_date >= current_date - ?)),
                       coalesce(sum((SELECT count(*) FROM student_document d WHERE d.student_id = s.id AND d.status = 'ACTIVE' AND d.scan_status = 'PENDING')), 0)
                """ + from + where).params(prepend(NEW_ADMISSION_DAYS, params))
                .query((rs, n) -> new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getLong(8)})
                .single();
        return new Kpis(row[0], row[1], row[6], row[2], row[3], row[4], row[5], row[7],
                group("e.program_id", "p.name", "JOIN program p ON p.id = e.program_id", where, params),
                group("e.course_id", "c.name || ' (' || p.code || ')'", "JOIN course c ON c.id = e.course_id JOIN program p ON p.id = c.program_id", where, params),
                group("e.batch_id", "b.code", "JOIN batch b ON b.id = e.batch_id", where, params),
                group("e.academic_year_id", "y.name", "JOIN academic_year y ON y.id = e.academic_year_id", where, params));
    }

    /** Current enrolments of visible, enrolled/active/suspended students grouped by a dimension (top 10). */
    private List<GroupCount> group(String idExpr, String labelExpr, String join, StringBuilder where, List<Object> params) {
        return jdbc.sql("SELECT " + idExpr + ", " + labelExpr + ", count(*) FROM student s JOIN org_unit su ON su.id = s.scope_unit_id "
                        + "JOIN enrolment e ON e.student_id = s.id AND e.is_current " + join + where + " AND s.status IN ('ENROLLED', 'ACTIVE', 'SUSPENDED') "
                        + "GROUP BY 1, 2 ORDER BY 3 DESC, 2 LIMIT 10")
                .params(params).query((rs, n) -> new GroupCount(rs.getObject(1, UUID.class), rs.getString(2), rs.getLong(3))).list();
    }

    private static List<Object> prepend(Object first, List<Object> rest) {
        List<Object> out = new ArrayList<>();
        out.add(first);
        out.addAll(rest);
        return out;
    }

    private static String hash(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))).substring(0, 24);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
