package com.ivorovi.scheduling;

import com.ivorovi.platform.security.Authz;
import com.ivorovi.platform.web.ApiException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Module API: the facts other modules need about a schedule (demo programme, add-on B). No candidate data. */
@Component
public class Schedules {

    /** A schedule: its institution, the published assessment version, the window and whether it is cancelled. */
    public record Ref(UUID id, UUID orgUnitId, UUID versionId, String title, Instant opensAt, Instant closesAt, String status) {}

    private final JdbcClient jdbc;
    private final Authz authz;

    Schedules(JdbcClient jdbc, Authz authz) {
        this.jdbc = jdbc;
        this.authz = authz;
    }

    /** The schedule (current tenant, RLS) without an authorization check: callers check their own permission at {@link Ref#orgUnitId()}. */
    @Transactional(readOnly = true)
    public java.util.Optional<Ref> find(UUID scheduleId) {
        return jdbc.sql("SELECT id, org_unit_id, version_id, title, opens_at, closes_at, status FROM exam_schedule WHERE id = ?").param(scheduleId)
                .query((rs, n) -> new Ref(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getString(4),
                        rs.getTimestamp(5).toInstant(), rs.getTimestamp(6).toInstant(), rs.getString(7))).optional();
    }

    /** The schedule if the caller holds {@code permission} at its institution; 404 otherwise (as for any schedule they cannot see). */
    @Transactional(readOnly = true)
    public Ref ref(UUID scheduleId, String permission) {
        Ref r = find(scheduleId).orElseThrow(() -> ApiException.notFound("Schedule"));
        if (!authz.hasInOrgUnit(permission, r.orgUnitId())) throw ApiException.notFound("Schedule");
        return r;
    }
}
