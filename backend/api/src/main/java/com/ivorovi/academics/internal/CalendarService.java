package com.ivorovi.academics.internal;

import static com.ivorovi.academics.internal.StructureService.actor;
import static com.ivorovi.academics.internal.StructureService.details;
import static com.ivorovi.academics.internal.StructureService.diff;
import static com.ivorovi.academics.internal.StructureService.tenant;
import static com.ivorovi.academics.internal.StructureService.version;

import com.ivorovi.academics.internal.StructureService.Page;
import com.ivorovi.platform.ids.Ids;
import com.ivorovi.platform.security.Permission;
import com.ivorovi.platform.web.ApiException;
import com.ivorovi.tenancy.AcademicCalendarPolicy;
import io.swagger.v3.oas.annotations.media.Schema;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Academic calendar: academic years (organization-wide or for one university or college) and any number of terms
 * (semesters, trimesters, quarters…) inside each year. Rules:
 * <ul>
 *   <li>years of the same owner may not overlap unless the organization setting allows it;</li>
 *   <li>terms lie inside their year and never overlap each other;</li>
 *   <li>registration closes by the end of the term; examinations fall inside the term;</li>
 *   <li>status moves PLANNED → ACTIVE → COMPLETED (re-opening needs a reason); a term can only be active in an
 *       active year and a year can only complete when none of its terms is active.</li>
 * </ul>
 */
@Service
public class CalendarService {

    public enum Status { PLANNED, ACTIVE, COMPLETED }

    @Schema(name = "AcademicYear")
    public record YearView(UUID id, String code, String name, UUID ownerOrgUnitId, String ownerName, LocalDate startDate, LocalDate endDate,
                           Status status, long termCount, long batchCount, Instant updatedAt, long version, boolean canManage) {}

    @Schema(name = "AcademicTerm")
    public record TermView(UUID id, UUID academicYearId, String academicYearCode, String academicYearName, UUID ownerOrgUnitId,
                           int sequence, String name, LocalDate startDate, LocalDate endDate, LocalDate registrationStart,
                           LocalDate registrationEnd, LocalDate examStart, LocalDate examEnd, Status status, long offeringCount,
                           Instant updatedAt, long version, boolean canManage) {}

    @Schema(name = "AcademicYearDetail")
    public record YearDetail(YearView year, List<TermView> terms, boolean overlappingYearsAllowed) {}

    public record YearCommand(String code, String name, UUID ownerOrgUnitId, LocalDate startDate, LocalDate endDate, Long version) {}

    public record TermCommand(Integer sequence, String name, LocalDate startDate, LocalDate endDate, LocalDate registrationStart,
                              LocalDate registrationEnd, LocalDate examStart, LocalDate examEnd, Long version) {}

    static final String CALENDAR = Permission.ACADEMIC_CALENDAR_MANAGE.key();
    private static final Set<String> INSTITUTIONS = Set.of("UNIVERSITY", "COLLEGE");

    private final JdbcClient jdbc;
    private final AcademicScope scope;
    private final AcademicCalendarPolicy policy;

    CalendarService(JdbcClient jdbc, AcademicScope scope, AcademicCalendarPolicy policy) {
        this.jdbc = jdbc;
        this.scope = scope;
        this.policy = policy;
    }

    // ================================================================================================ years

    private static final String YEAR_FROM = "academic_year y LEFT JOIN org_unit ou ON ou.id = y.owner_org_unit_id";
    private static final String YEAR_SELECT = """
            SELECT y.*, ou.name AS owner_name,
                   (SELECT count(*) FROM academic_term t WHERE t.academic_year_id = y.id) AS term_count,
                   (SELECT count(*) FROM batch b WHERE b.academic_year_id = y.id) AS batch_count
            FROM\s""" + YEAR_FROM;
    private static final Map<String, String> YEAR_SORTS = Map.of("startDate", "y.start_date", "name", "y.name", "code", "y.code",
            "status", "y.status", "updatedAt", "y.updated_at");

    @Transactional(readOnly = true)
    public Page<YearView> years(String q, Status status, UUID ownerOrgUnitId, int page, int size, String sort, boolean asc) {
        scope.requireAnyRead();
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (status != null) { where.append(" AND y.status = ?"); params.add(status.name()); }
        if (ownerOrgUnitId != null) { where.append(" AND y.owner_org_unit_id = ?"); params.add(ownerOrgUnitId); }
        StructureService.search(q, where, params, "y.name", "y.code");
        if (!scope.appendVisibility("ou.path", where, params)) return new Page<>(List.of(), 0);
        return pageOf(YEAR_SELECT, YEAR_FROM, where, params, page, size, YEAR_SORTS.getOrDefault(sort, "y.start_date"), asc, "y.id", this::mapYear);
    }

    @Transactional(readOnly = true)
    public YearDetail year(UUID id) {
        YearView y = loadYear(id);
        scope.requireRead(y.ownerOrgUnitId());
        return new YearDetail(y, termsOf(id), policy.allowOverlappingAcademicYears());
    }

    @Transactional
    public UUID createYear(YearCommand c) {
        AcademicScope.Unit owner = owner(c.ownerOrgUnitId());
        UUID ownerId = owner == null ? null : owner.id();
        scope.requireManage(CALENDAR, ownerId);
        String code = Text.code(c.code());
        String name = Text.required("name", c.name(), 100);
        dates(c.startDate(), c.endDate(), "year");
        lockOwner(ownerId);
        requireNoYearOverlap(ownerId, c.startDate(), c.endDate(), null);
        UUID id = Ids.newId();
        try {
            jdbc.sql("""
                    INSERT INTO academic_year (id, tenant_id, owner_org_unit_id, code, name, start_date, end_date, created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(id, tenant(), ownerId, code, name, c.startDate(), c.endDate(), actor(), actor()).update();
        } catch (DuplicateKeyException e) {
            throw AcademicScope.conflict("code_in_use", "Another academic year " + (owner == null ? "of the organization" : "of " + owner.name())
                    + " already uses the code " + code + ".");
        }
        scope.changed("academics.year.created", "academic_year", id, details("code", code, "name", name,
                "owner", owner == null ? "organization" : owner.name(), "startDate", c.startDate().toString(), "endDate", c.endDate().toString()));
        return id;
    }

    @Transactional
    public void updateYear(UUID id, YearCommand c) {
        scope.lock("academic_year", id);
        YearView before = loadYear(id);
        scope.requireManage(CALENDAR, before.ownerOrgUnitId());
        version(c.version(), before.version());
        if (!Objects.equals(c.ownerOrgUnitId(), before.ownerOrgUnitId()) && c.ownerOrgUnitId() != null) {
            throw ApiException.badRequest("owner_immutable", "An academic year cannot move to another institution.");
        }
        if (before.status() == Status.COMPLETED) throw AcademicScope.conflict("year_completed", "A completed academic year cannot be changed. Re-open it first.");
        String code = Text.code(c.code());
        String name = Text.required("name", c.name(), 100);
        dates(c.startDate(), c.endDate(), "year");
        lockOwner(before.ownerOrgUnitId());
        requireNoYearOverlap(before.ownerOrgUnitId(), c.startDate(), c.endDate(), id);
        long outside = jdbc.sql("SELECT count(*) FROM academic_term WHERE academic_year_id = ? AND (start_date < ? OR end_date > ?)")
                .params(id, c.startDate(), c.endDate()).query(Long.class).single();
        if (outside > 0) throw AcademicScope.invalid("terms_outside_year", outside + " term(s) fall outside the new dates. Change them first.");
        try {
            jdbc.sql("UPDATE academic_year SET code = ?, name = ?, start_date = ?, end_date = ?, updated_at = now(), updated_by = ?, version = version + 1 WHERE id = ?")
                    .params(code, name, c.startDate(), c.endDate(), actor(), id).update();
        } catch (DuplicateKeyException e) {
            throw AcademicScope.conflict("code_in_use", "Another academic year of this owner already uses the code " + code + ".");
        }
        Map<String, String> d = details("code", code);
        diff(d, "name", before.name(), name);
        diff(d, "startDate", before.startDate().toString(), c.startDate().toString());
        diff(d, "endDate", before.endDate().toString(), c.endDate().toString());
        scope.changed("academics.year.updated", "academic_year", id, d);
    }

    @Transactional
    public void setYearStatus(UUID id, Status status, long version, String reason) {
        scope.lock("academic_year", id);
        YearView y = loadYear(id);
        scope.requireManage(CALENDAR, y.ownerOrgUnitId());
        version(version, y.version());
        transition(y.status(), status, reason, "academic year");
        if (status != Status.ACTIVE) {
            long active = jdbc.sql("SELECT count(*) FROM academic_term WHERE academic_year_id = ? AND status = 'ACTIVE'").param(id).query(Long.class).single();
            if (active > 0) throw AcademicScope.conflict("terms_active", "Complete or reset the " + active + " active term(s) of this year first.");
        }
        jdbc.sql("UPDATE academic_year SET status = ?, updated_at = now(), updated_by = ?, version = version + 1 WHERE id = ?").params(status.name(), actor(), id).update();
        scope.changed("academics.year." + status.name().toLowerCase(), "academic_year", id,
                details("code", y.code(), "from", y.status().name(), "to", status.name(), "reason", reason));
    }

    private AcademicScope.Unit owner(UUID id) {
        return id == null ? null : scope.activeUnit(id, INSTITUTIONS, "owner", "a university or college");
    }

    /** Serialises calendar changes of one owner so the overlap check cannot race. */
    private void lockOwner(UUID ownerId) {
        jdbc.sql("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(?, 0))) l")
                .param("academic-year:" + tenant() + ":" + (ownerId == null ? "org" : ownerId)).query(Integer.class).single();
    }

    private void requireNoYearOverlap(UUID ownerId, LocalDate start, LocalDate end, UUID except) {
        if (policy.allowOverlappingAcademicYears()) return;
        List<String> clashes = jdbc.sql("""
                SELECT name FROM academic_year
                WHERE owner_org_unit_id IS NOT DISTINCT FROM ? AND id IS DISTINCT FROM ? AND start_date <= ? AND end_date >= ?
                ORDER BY start_date LIMIT 3""")
                .params(ownerId, except, end, start).query(String.class).list();
        if (!clashes.isEmpty()) {
            throw AcademicScope.invalid("year_overlap", "These dates overlap " + String.join(", ", clashes)
                    + ". Overlapping academic years are switched off in System Settings.");
        }
    }

    YearView loadYear(UUID id) {
        return jdbc.sql(YEAR_SELECT + " WHERE y.id = ?").param(id).query(this::mapYear).optional().orElseThrow(() -> ApiException.notFound("Academic year"));
    }

    private YearView mapYear(ResultSet rs, int n) throws SQLException {
        UUID owner = rs.getObject("owner_org_unit_id", UUID.class);
        return new YearView(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"), owner, rs.getString("owner_name"),
                rs.getObject("start_date", LocalDate.class), rs.getObject("end_date", LocalDate.class), Status.valueOf(rs.getString("status")),
                rs.getLong("term_count"), rs.getLong("batch_count"), Text.instant(rs, "updated_at"), rs.getLong("version"),
                scope.canManage(CALENDAR, owner));
    }

    // ================================================================================================ terms

    private static final String TERM_FROM = "academic_term t JOIN academic_year y ON y.id = t.academic_year_id LEFT JOIN org_unit ou ON ou.id = y.owner_org_unit_id";
    private static final String TERM_SELECT = """
            SELECT t.*, y.code AS year_code, y.name AS year_name, y.owner_org_unit_id,
                   (SELECT count(*) FROM subject_offering o WHERE o.term_id = t.id AND o.status <> 'CANCELLED') AS offering_count
            FROM\s""" + TERM_FROM;
    private static final Map<String, String> TERM_SORTS = Map.of("startDate", "t.start_date", "name", "t.name", "sequence", "t.sequence",
            "status", "t.status", "year", "y.start_date");

    @Transactional(readOnly = true)
    public Page<TermView> terms(String q, UUID yearId, Status status, int page, int size, String sort, boolean asc) {
        scope.requireAnyRead();
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (yearId != null) { where.append(" AND t.academic_year_id = ?"); params.add(yearId); }
        if (status != null) { where.append(" AND t.status = ?"); params.add(status.name()); }
        StructureService.search(q, where, params, "t.name", "y.name", "y.code");
        if (!scope.appendVisibility("ou.path", where, params)) return new Page<>(List.of(), 0);
        return pageOf(TERM_SELECT, TERM_FROM, where, params, page, size, TERM_SORTS.getOrDefault(sort, "t.start_date"), asc, "t.id", this::mapTerm);
    }

    @Transactional(readOnly = true)
    public TermView term(UUID id) {
        TermView t = loadTerm(id);
        scope.requireRead(t.ownerOrgUnitId());
        return t;
    }

    @Transactional
    public UUID createTerm(UUID yearId, TermCommand c) {
        scope.lock("academic_year", yearId);
        YearView year = loadYear(yearId);
        scope.requireManage(CALENDAR, year.ownerOrgUnitId());
        if (year.status() == Status.COMPLETED) throw AcademicScope.conflict("year_completed", year.name() + " is completed.");
        TermFields f = termFields(c, year, null);
        UUID id = Ids.newId();
        try {
            jdbc.sql("""
                    INSERT INTO academic_term (id, tenant_id, academic_year_id, sequence, name, start_date, end_date, registration_start, registration_end,
                        exam_start, exam_end, created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(id, tenant(), yearId, f.sequence, f.name, c.startDate(), c.endDate(), c.registrationStart(), c.registrationEnd(),
                            c.examStart(), c.examEnd(), actor(), actor()).update();
        } catch (DuplicateKeyException e) {
            throw AcademicScope.conflict("sequence_in_use", year.name() + " already has a term number " + f.sequence + ".");
        }
        scope.changed("academics.term.created", "academic_term", id, details("year", year.code(), "sequence", String.valueOf(f.sequence),
                "name", f.name, "startDate", c.startDate().toString(), "endDate", c.endDate().toString()));
        return id;
    }

    @Transactional
    public void updateTerm(UUID id, TermCommand c) {
        TermView before = loadTerm(id);
        scope.lock("academic_year", before.academicYearId());
        scope.lock("academic_term", id);
        before = loadTerm(id);
        YearView year = loadYear(before.academicYearId());
        scope.requireManage(CALENDAR, year.ownerOrgUnitId());
        version(c.version(), before.version());
        if (before.status() == Status.COMPLETED) throw AcademicScope.conflict("term_completed", "A completed term cannot be changed. Re-open it first.");
        TermFields f = termFields(c, year, id);
        try {
            jdbc.sql("""
                    UPDATE academic_term SET sequence = ?, name = ?, start_date = ?, end_date = ?, registration_start = ?, registration_end = ?,
                        exam_start = ?, exam_end = ?, updated_at = now(), updated_by = ?, version = version + 1 WHERE id = ?""")
                    .params(f.sequence, f.name, c.startDate(), c.endDate(), c.registrationStart(), c.registrationEnd(), c.examStart(), c.examEnd(),
                            actor(), id).update();
        } catch (DuplicateKeyException e) {
            throw AcademicScope.conflict("sequence_in_use", year.name() + " already has a term number " + f.sequence + ".");
        }
        Map<String, String> d = details("year", year.code(), "sequence", String.valueOf(f.sequence));
        diff(d, "name", before.name(), f.name);
        diff(d, "startDate", before.startDate().toString(), c.startDate().toString());
        diff(d, "endDate", before.endDate().toString(), c.endDate().toString());
        diff(d, "registration", window(before.registrationStart(), before.registrationEnd()), window(c.registrationStart(), c.registrationEnd()));
        diff(d, "exams", window(before.examStart(), before.examEnd()), window(c.examStart(), c.examEnd()));
        scope.changed("academics.term.updated", "academic_term", id, d);
    }

    @Transactional
    public void setTermStatus(UUID id, Status status, long version, String reason) {
        scope.lock("academic_term", id);
        TermView t = loadTerm(id);
        YearView year = loadYear(t.academicYearId());
        scope.requireManage(CALENDAR, year.ownerOrgUnitId());
        version(version, t.version());
        transition(t.status(), status, reason, "term");
        if (status == Status.ACTIVE && year.status() != Status.ACTIVE) {
            throw AcademicScope.invalid("year_not_active", "Activate " + year.name() + " before activating its terms.");
        }
        if (status != Status.COMPLETED && year.status() == Status.COMPLETED) {
            throw AcademicScope.invalid("year_completed", year.name() + " is completed.");
        }
        jdbc.sql("UPDATE academic_term SET status = ?, updated_at = now(), updated_by = ?, version = version + 1 WHERE id = ?").params(status.name(), actor(), id).update();
        scope.changed("academics.term." + status.name().toLowerCase(), "academic_term", id,
                details("year", year.code(), "term", t.name(), "from", t.status().name(), "to", status.name(), "reason", reason));
    }

    private record TermFields(int sequence, String name) {}

    private TermFields termFields(TermCommand c, YearView year, UUID except) {
        if (c.sequence() == null || c.sequence() < 1 || c.sequence() > 12) throw ApiException.badRequest("invalid_sequence", "Use a term number from 1 to 12.");
        String name = Text.required("name", c.name(), 100);
        dates(c.startDate(), c.endDate(), "term");
        if (c.startDate().isBefore(year.startDate()) || c.endDate().isAfter(year.endDate())) {
            throw AcademicScope.invalid("term_outside_year", "The term must lie within " + year.name() + " (" + year.startDate() + " to " + year.endDate() + ").");
        }
        if ((c.registrationStart() == null) != (c.registrationEnd() == null)) {
            throw ApiException.badRequest("invalid_registration_window", "Give both registration dates or neither.");
        }
        if (c.registrationStart() != null) {
            if (c.registrationEnd().isBefore(c.registrationStart())) {
                throw ApiException.badRequest("invalid_registration_window", "Registration must close on or after it opens.");
            }
            if (c.registrationEnd().isAfter(c.endDate())) {
                throw AcademicScope.invalid("invalid_registration_window", "Registration must close by the end of the term.");
            }
            if (c.registrationStart().isBefore(year.startDate().minusYears(1))) {
                throw AcademicScope.invalid("invalid_registration_window", "Registration cannot open more than a year before the academic year.");
            }
        }
        if ((c.examStart() == null) != (c.examEnd() == null)) throw ApiException.badRequest("invalid_exam_window", "Give both examination dates or neither.");
        if (c.examStart() != null) {
            if (c.examEnd().isBefore(c.examStart())) throw ApiException.badRequest("invalid_exam_window", "Examinations must end on or after they start.");
            if (c.examStart().isBefore(c.startDate()) || c.examEnd().isAfter(c.endDate())) {
                throw AcademicScope.invalid("invalid_exam_window", "Examinations must fall within the term.");
            }
        }
        List<String> clashes = jdbc.sql("""
                SELECT name FROM academic_term WHERE academic_year_id = ? AND id IS DISTINCT FROM ? AND start_date <= ? AND end_date >= ?
                ORDER BY sequence LIMIT 3""").params(year.id(), except, c.endDate(), c.startDate()).query(String.class).list();
        if (!clashes.isEmpty()) throw AcademicScope.invalid("term_overlap", "These dates overlap " + String.join(", ", clashes) + ".");
        return new TermFields(c.sequence(), name);
    }

    List<TermView> termsOf(UUID yearId) {
        return jdbc.sql(TERM_SELECT + " WHERE t.academic_year_id = ? ORDER BY t.sequence").param(yearId).query(this::mapTerm).list();
    }

    TermView loadTerm(UUID id) {
        return jdbc.sql(TERM_SELECT + " WHERE t.id = ?").param(id).query(this::mapTerm).optional().orElseThrow(() -> ApiException.notFound("Term"));
    }

    private TermView mapTerm(ResultSet rs, int n) throws SQLException {
        UUID owner = rs.getObject("owner_org_unit_id", UUID.class);
        return new TermView(rs.getObject("id", UUID.class), rs.getObject("academic_year_id", UUID.class), rs.getString("year_code"),
                rs.getString("year_name"), owner, rs.getInt("sequence"), rs.getString("name"), rs.getObject("start_date", LocalDate.class),
                rs.getObject("end_date", LocalDate.class), rs.getObject("registration_start", LocalDate.class),
                rs.getObject("registration_end", LocalDate.class), rs.getObject("exam_start", LocalDate.class), rs.getObject("exam_end", LocalDate.class),
                Status.valueOf(rs.getString("status")), rs.getLong("offering_count"), Text.instant(rs, "updated_at"), rs.getLong("version"),
                scope.canManage(CALENDAR, owner));
    }

    // ================================================================================================ helpers

    private static void dates(LocalDate start, LocalDate end, String what) {
        if (start == null || end == null) throw ApiException.badRequest("invalid_dates", "Give the start and end dates of the " + what + ".");
        if (!end.isAfter(start)) throw ApiException.badRequest("invalid_dates", "The " + what + " must end after it starts.");
        if (start.plusYears(3).isBefore(end)) throw ApiException.badRequest("invalid_dates", "The " + what + " cannot be longer than three years.");
    }

    /** PLANNED → ACTIVE → COMPLETED; ACTIVE → PLANNED and COMPLETED → ACTIVE are corrections that need a reason. */
    private static void transition(Status from, Status to, String reason, String what) {
        if (to == null) throw ApiException.badRequest("invalid_status", "Choose a status.");
        if (from == to) throw AcademicScope.conflict("invalid_status_transition", "The " + what + " is already " + to.name().toLowerCase() + ".");
        boolean forward = (from == Status.PLANNED && to == Status.ACTIVE) || (from == Status.ACTIVE && to == Status.COMPLETED);
        boolean correction = (from == Status.ACTIVE && to == Status.PLANNED) || (from == Status.COMPLETED && to == Status.ACTIVE);
        if (!forward && !correction) {
            throw AcademicScope.conflict("invalid_status_transition", "A " + what + " cannot go from " + from.name().toLowerCase() + " to " + to.name().toLowerCase() + ".");
        }
        if (correction && (reason == null || reason.isBlank())) throw ApiException.badRequest("reason_required", "Give a reason for this correction.");
    }

    private static String window(LocalDate a, LocalDate b) {
        return a == null ? "none" : a + " to " + b;
    }

    private <T> Page<T> pageOf(String select, String from, StringBuilder where, List<Object> params, int page, int size, String order, boolean asc,
                               String tiebreak, org.springframework.jdbc.core.RowMapper<T> mapper) {
        long total = jdbc.sql("SELECT count(*) FROM " + from + where).params(params).query(Long.class).single();
        List<Object> p = new ArrayList<>(params);
        p.add(size);
        p.add((long) page * size);
        return new Page<>(jdbc.sql(select + where + " ORDER BY " + order + (asc ? " ASC" : " DESC") + " NULLS LAST, " + tiebreak + " LIMIT ? OFFSET ?")
                .params(p).query(mapper).list(), total);
    }
}
