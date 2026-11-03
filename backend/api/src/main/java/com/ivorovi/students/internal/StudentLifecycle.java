package com.ivorovi.students.internal;

import static com.ivorovi.students.internal.StudentAccess.actor;
import static com.ivorovi.students.internal.StudentAccess.conflict;
import static com.ivorovi.students.internal.StudentAccess.invalid;
import static com.ivorovi.students.internal.StudentAccess.tenant;

import com.ivorovi.platform.ids.Ids;
import com.ivorovi.students.internal.StudentService.Status;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The student lifecycle model, enforced on the server for every status change (see {@link StudentService}). */
@Component
class StudentLifecycle {

    static final Map<Status, Set<Status>> TRANSITIONS = Map.of(
            Status.PROSPECTIVE, EnumSet.of(Status.ADMITTED, Status.CANCELLED),
            Status.ADMITTED, EnumSet.of(Status.ENROLLED, Status.WITHDRAWN, Status.CANCELLED),
            Status.ENROLLED, EnumSet.of(Status.ACTIVE, Status.SUSPENDED, Status.WITHDRAWN),
            Status.ACTIVE, EnumSet.of(Status.SUSPENDED, Status.WITHDRAWN, Status.GRADUATED),
            Status.SUSPENDED, EnumSet.of(Status.ACTIVE, Status.WITHDRAWN),
            Status.GRADUATED, EnumSet.of(Status.ALUMNI),
            Status.WITHDRAWN, EnumSet.noneOf(Status.class),
            Status.CANCELLED, EnumSet.noneOf(Status.class),
            Status.ALUMNI, EnumSet.noneOf(Status.class));

    private final JdbcClient jdbc;
    private final StudentAccess access;

    StudentLifecycle(JdbcClient jdbc, StudentAccess access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    /** Validated status change with history (previous and new status, effective date, reason, actor, timestamp). */
    void transition(StudentAccess.StudentRef ref, Status to, LocalDate effective, String reason) {
        Status from = Status.valueOf(jdbc.sql("SELECT status FROM student WHERE id = ?").param(ref.id()).query(String.class).single());
        if (from == to) throw conflict("invalid_status_transition", "The student is already " + to.name().toLowerCase() + ".");
        if (!TRANSITIONS.get(from).contains(to)) {
            throw conflict("invalid_status_transition", "A student cannot move from " + from.name().toLowerCase() + " to " + to.name().toLowerCase() + ".");
        }
        LocalDate previous = jdbc.sql("SELECT status_effective_on FROM student WHERE id = ?").param(ref.id()).query(LocalDate.class).single();
        if (effective.isBefore(previous)) {
            throw invalid("invalid_effective_date", "The effective date cannot be before the current status took effect (" + previous + ").");
        }
        if (effective.isAfter(LocalDate.now().plusYears(1))) throw invalid("invalid_effective_date", "The effective date is too far in the future.");
        jdbc.sql("UPDATE student SET status = ?, status_effective_on = ?, updated_at = now(), updated_by = ?, version = version + 1 WHERE id = ?")
                .params(to.name(), effective, actor(), ref.id()).update();
        jdbc.sql("INSERT INTO student_status_history (id, tenant_id, student_id, from_status, to_status, effective_on, reason, actor_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                .params(Ids.newId(), tenant(), ref.id(), from.name(), to.name(), effective, reason, actor()).update();
        access.changed("student.status_changed", ref, "Status " + from.name().toLowerCase() + " → " + to.name().toLowerCase(),
                Map.of("from", from.name(), "to", to.name(), "effectiveOn", effective.toString()));
    }
}
