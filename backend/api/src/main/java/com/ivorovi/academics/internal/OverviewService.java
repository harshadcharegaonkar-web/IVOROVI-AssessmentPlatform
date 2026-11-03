package com.ivorovi.academics.internal;

import com.ivorovi.platform.cache.ValkeyJsonCache;
import com.ivorovi.platform.tenancy.TenantContext;
import io.swagger.v3.oas.annotations.media.Schema;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Academic overview: KPI counts and the Program → Course → Batch tree, both limited to what the caller may read.
 * KPIs are cached per organization, read scope and academic generation (every academic change bumps the
 * generation after commit, so a cached value is never served after a change).
 */
@Service
public class OverviewService {

    @Schema(name = "AcademicKpis")
    public record Kpis(long programs, long courses, long subjects, long activeAcademicYears, long activeTerms, long activeBatches,
                       long offerings, long offeringsWithoutFaculty, long facultyAssignments, long facultyTeaching) {}

    @Schema(name = "AcademicTreeBatch")
    public record TreeBatch(UUID id, String code, String name, int admissionYear, String section, String status, long offerings) {}

    @Schema(name = "AcademicTreeCourse")
    public record TreeCourse(UUID id, String code, String name, String departmentName, String status, long subjects, List<TreeBatch> batches) {}

    @Schema(name = "AcademicTreeProgram")
    public record TreeProgram(UUID id, String code, String name, String qualificationLevel, String ownerName, String status, int totalTerms,
                              List<TreeCourse> courses) {}

    @Schema(name = "AcademicTree")
    public record Tree(List<TreeProgram> programs, boolean truncated) {}

    static final int TREE_LIMIT = 200;
    private static final Duration TTL = Duration.ofMinutes(5);

    private final JdbcClient jdbc;
    private final AcademicScope scope;
    private final ValkeyJsonCache cache;

    OverviewService(JdbcClient jdbc, AcademicScope scope, ValkeyJsonCache cache) {
        this.jdbc = jdbc;
        this.scope = scope;
        this.cache = cache;
    }

    @Transactional(readOnly = true)
    public Kpis kpis() {
        scope.requireAnyRead();
        UUID tenant = TenantContext.requireTenantId();
        String key = "ivorovi:academics-kpis:t:" + tenant + ":g:" + scope.generation() + ":s:" + hash(scope.scopeKey());
        return cache.get("academics-kpis", key, Kpis.class).orElseGet(() -> {
            Kpis k = compute();
            cache.put("academics-kpis", key, k, TTL);
            return k;
        });
    }

    private Kpis compute() {
        return new Kpis(
                count("program p JOIN org_unit ou ON ou.id = p.owner_org_unit_id", "p.status = 'ACTIVE'", "ou.path"),
                count("course c JOIN org_unit d ON d.id = c.department_id", "c.status = 'ACTIVE'", "d.path"),
                count("subject s JOIN org_unit ou ON ou.id = s.owner_org_unit_id", "s.status = 'ACTIVE'", "ou.path"),
                count("academic_year y LEFT JOIN org_unit ou ON ou.id = y.owner_org_unit_id", "y.status = 'ACTIVE'", "ou.path"),
                count("academic_term t JOIN academic_year y ON y.id = t.academic_year_id LEFT JOIN org_unit ou ON ou.id = y.owner_org_unit_id",
                        "t.status = 'ACTIVE'", "ou.path"),
                count("batch b JOIN course c ON c.id = b.course_id JOIN org_unit d ON d.id = c.department_id", "b.status = 'ACTIVE'", "d.path"),
                count("subject_offering o JOIN course c ON c.id = o.course_id JOIN org_unit d ON d.id = c.department_id",
                        "o.status IN ('PLANNED', 'ACTIVE')", "d.path"),
                count("subject_offering o JOIN course c ON c.id = o.course_id JOIN org_unit d ON d.id = c.department_id",
                        "o.status IN ('PLANNED', 'ACTIVE') AND NOT EXISTS (SELECT 1 FROM faculty_assignment f WHERE f.offering_id = o.id AND f.ended_at IS NULL)",
                        "d.path"),
                count("faculty_assignment f JOIN subject_offering o ON o.id = f.offering_id JOIN course c ON c.id = o.course_id JOIN org_unit d ON d.id = c.department_id",
                        "f.ended_at IS NULL AND o.status IN ('PLANNED', 'ACTIVE')", "d.path"),
                countDistinct("f.staff_profile_id",
                        "faculty_assignment f JOIN subject_offering o ON o.id = f.offering_id JOIN course c ON c.id = o.course_id JOIN org_unit d ON d.id = c.department_id",
                        "f.ended_at IS NULL AND o.status IN ('PLANNED', 'ACTIVE')", "d.path"));
    }

    private long count(String from, String condition, String pathExpr) {
        return countDistinct("*", from, condition, pathExpr);
    }

    private long countDistinct(String expr, String from, String condition, String pathExpr) {
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE ").append(condition);
        if (!scope.appendVisibility(pathExpr, where, params)) return 0;
        String what = "*".equals(expr) ? "count(*)" : "count(DISTINCT " + expr + ")";
        return jdbc.sql("SELECT " + what + " FROM " + from + where).params(params).query(Long.class).single();
    }

    /** Programs with their courses and batches; inactive entries only when asked for. */
    @Transactional(readOnly = true)
    public Tree tree(UUID ownerOrgUnitId, boolean includeInactive) {
        scope.requireAnyRead();
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (!includeInactive) where.append(" AND p.status = 'ACTIVE'");
        if (ownerOrgUnitId != null) { where.append(" AND p.owner_org_unit_id = ?"); params.add(ownerOrgUnitId); }
        if (!scope.appendVisibility("ou.path", where, params)) return new Tree(List.of(), false);
        params.add(TREE_LIMIT + 1);
        record P(UUID id, String code, String name, String level, String owner, String status, int terms) {}
        List<P> programs = jdbc.sql("SELECT p.id, p.code, p.name, p.qualification_level, ou.name, p.status, p.total_terms FROM program p "
                        + "JOIN org_unit ou ON ou.id = p.owner_org_unit_id" + where + " ORDER BY ou.name, p.name, p.id LIMIT ?")
                .params(params).query((rs, n) -> new P(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getInt(7))).list();
        boolean truncated = programs.size() > TREE_LIMIT;
        if (truncated) programs = programs.subList(0, TREE_LIMIT);
        if (programs.isEmpty()) return new Tree(List.of(), false);
        UUID[] ids = programs.stream().map(P::id).toArray(UUID[]::new);

        List<Object> cp = new ArrayList<>();
        cp.add(ids);
        StringBuilder cw = new StringBuilder(" WHERE c.program_id = ANY(?)");
        if (!includeInactive) cw.append(" AND c.status = 'ACTIVE'");
        scope.appendVisibility("d.path", cw, cp);
        record C(UUID id, UUID program, String code, String name, String dept, String status, long subjects) {}
        List<C> courses = jdbc.sql("""
                SELECT c.id, c.program_id, c.code, c.name, d.name, c.status,
                       (SELECT count(DISTINCT e.subject_id) FROM curriculum_entry e WHERE e.course_id = c.id)
                FROM course c JOIN org_unit d ON d.id = c.department_id""" + cw + " ORDER BY c.name, c.id")
                .params(cp).query((rs, n) -> new C(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getLong(7))).list();

        Map<UUID, List<TreeBatch>> batches = new LinkedHashMap<>();
        if (!courses.isEmpty()) {
            String bw = includeInactive ? "" : " AND b.status IN ('PLANNED', 'ACTIVE')";
            jdbc.sql("""
                    SELECT b.course_id, b.id, b.code, b.name, b.admission_year, b.section, b.status,
                           (SELECT count(*) FROM subject_offering o WHERE o.batch_id = b.id AND o.status <> 'CANCELLED')
                    FROM batch b WHERE b.course_id = ANY(?)""" + bw + " ORDER BY b.admission_year DESC, b.code")
                    .param(courses.stream().map(C::id).toArray(UUID[]::new))
                    .query((rs, n) -> {
                        batches.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>()).add(new TreeBatch(rs.getObject(2, UUID.class),
                                rs.getString(3), rs.getString(4), rs.getInt(5), rs.getString(6), rs.getString(7), rs.getLong(8)));
                        return null;
                    }).list();
        }
        Map<UUID, List<TreeCourse>> byProgram = new LinkedHashMap<>();
        for (C c : courses) {
            byProgram.computeIfAbsent(c.program(), k -> new ArrayList<>())
                    .add(new TreeCourse(c.id(), c.code(), c.name(), c.dept(), c.status(), c.subjects(), batches.getOrDefault(c.id(), List.of())));
        }
        return new Tree(programs.stream().map(p -> new TreeProgram(p.id(), p.code(), p.name(), p.level(), p.owner(), p.status(), p.terms(),
                byProgram.getOrDefault(p.id(), List.of()))).toList(), truncated);
    }

    private static String hash(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))).substring(0, 24);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
