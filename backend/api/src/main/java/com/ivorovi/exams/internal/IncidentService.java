package com.ivorovi.exams.internal;

import com.ivorovi.approvals.ApprovalHandler;
import com.ivorovi.approvals.ApprovalService;
import com.ivorovi.exams.ExamsProperties;
import com.ivorovi.notifications.NotificationService;
import com.ivorovi.platform.audit.AuditLog;
import com.ivorovi.platform.audit.AuditOutcome;
import com.ivorovi.platform.crypto.FieldCipher;
import com.ivorovi.platform.ids.Ids;
import com.ivorovi.platform.outbox.Outbox;
import com.ivorovi.platform.security.Authz;
import com.ivorovi.platform.security.CurrentUser;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.platform.web.ApiException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Platform-outage incidents and candidate compensation (C-3). An incident's impact on an organization lists the affected
 * candidates (present or checked in while the outage lasted). Compensation is proposed by someone holding
 * {@code exam.incident.manage} and applied only after a second person holding {@code exam.incident.decide} approves it in
 * the four-eyes framework. Server time is never changed outside this workflow; every step is audited and candidates are
 * told by email.
 */
@Service
public class IncidentService implements ApprovalHandler {

    public static final String MANAGE = "exam.incident.manage";
    public static final String DECIDE = "exam.incident.decide";
    public static final String APPROVAL_TYPE = "exam.compensation";
    static final Duration AFFECTED_LEAD = Duration.ofMinutes(2);
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    @Schema(name = "ExamIncidentCandidate")
    public record Affected(UUID candidateId, String displayName, String email, UUID sessionId, String sessionName) {}

    @Schema(name = "ExamCompensation")
    public record Compensation(UUID id, String action, Integer extensionMinutes, String rationale, String status, UUID approvalId, UUID proposedBy,
                               String proposedByName, Instant proposedAt, Instant decidedAt, long applied) {}

    @Schema(name = "ExamIncident")
    public record Incident(UUID id, String source, UUID platformIncidentId, Instant startedAt, Instant endedAt, String description, String status,
                           long affected, Instant createdAt, long version, List<Affected> candidates, List<Compensation> compensations) {}

    private final JdbcClient jdbc;
    private final Authz authz;
    private final AuditLog audit;
    private final Outbox outbox;
    private final FieldCipher cipher;
    private final NotificationService notifications;
    private final ApprovalService approvals;
    private final ExamsProperties props;

    IncidentService(JdbcClient jdbc, Authz authz, AuditLog audit, Outbox outbox, FieldCipher cipher, NotificationService notifications,
                    @Lazy ApprovalService approvals, ExamsProperties props) {
        this.jdbc = jdbc;
        this.authz = authz;
        this.audit = audit;
        this.outbox = outbox;
        this.cipher = cipher;
        this.notifications = notifications;
        this.approvals = approvals;
        this.props = props;
    }

    // ================================================================================================ reading

    @Transactional(readOnly = true)
    public List<Incident> list() {
        requireAny();
        return jdbc.sql(SELECT + " ORDER BY i.started_at DESC LIMIT 200").query((rs, n) -> incident(rs, false)).list();
    }

    @Transactional(readOnly = true)
    public Incident get(UUID id) {
        requireAny();
        return find(id, true);
    }

    private static final String SELECT = """
            SELECT i.*, (SELECT count(*) FROM exam_incident_candidate c WHERE c.impact_id = i.id) AS affected FROM exam_incident_impact i""";

    private Incident find(UUID id, boolean detail) {
        return jdbc.sql(SELECT + " WHERE i.id = ?").param(id).query((rs, n) -> incident(rs, detail)).optional()
                .orElseThrow(() -> ApiException.notFound("Incident"));
    }

    private Incident incident(ResultSet rs, boolean detail) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        return new Incident(id, rs.getString("source"), rs.getObject("platform_incident_id", UUID.class), rs.getTimestamp("started_at").toInstant(),
                ts(rs, "ended_at"), rs.getString("description"), rs.getString("status"), rs.getLong("affected"), rs.getTimestamp("created_at").toInstant(),
                rs.getLong("version"), detail ? affected(id) : List.of(), detail ? compensations(id) : List.of());
    }

    private List<Affected> affected(UUID impact) {
        UUID tenant = TenantContext.requireTenantId();
        return jdbc.sql("""
                SELECT c.candidate_id, ca.display_name, ca.email_enc, c.session_id, s.name FROM exam_incident_candidate c
                JOIN candidate_account ca ON ca.id = c.candidate_id JOIN readiness_session s ON s.id = c.session_id
                WHERE c.impact_id = ? ORDER BY ca.display_name NULLS LAST, c.candidate_id""").param(impact)
                .query((rs, n) -> new Affected(rs.getObject(1, UUID.class), rs.getString(2), cipher.decrypt(rs.getString(3), ExamAdminService.EMAIL_FIELD, tenant),
                        rs.getObject(4, UUID.class), rs.getString(5))).list();
    }

    private List<Compensation> compensations(UUID impact) {
        return jdbc.sql("""
                SELECT ec.*, u.display_name AS by_name, (SELECT count(*) FROM candidate_compensation cc WHERE cc.compensation_id = ec.id) AS applied
                FROM exam_compensation ec LEFT JOIN user_account u ON u.id = ec.proposed_by WHERE ec.impact_id = ? ORDER BY ec.proposed_at DESC""")
                .param(impact).query((rs, n) -> new Compensation(rs.getObject("id", UUID.class), rs.getString("action"), (Integer) rs.getObject("extension_minutes"),
                        rs.getString("rationale"), rs.getString("status"), rs.getObject("approval_id", UUID.class), rs.getObject("proposed_by", UUID.class),
                        rs.getString("by_name"), rs.getTimestamp("proposed_at").toInstant(), ts(rs, "decided_at"), rs.getLong("applied"))).list();
    }

    // ================================================================================================ detection (platform) and reporting (institution)

    /** Opens the organization's impact while a platform outage is ongoing (sessions open now). Tenant context bound. */
    @Transactional
    public UUID platformOutageStarted(UUID platformIncidentId, Instant startedAt) {
        boolean openSessions = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM readiness_session WHERE status = 'SCHEDULED' AND starts_at <= now() AND ends_at >= CAST(? AS timestamptz))""")
                .param(Timestamp.from(startedAt)).query(Boolean.class).single();
        if (!openSessions) return null;
        UUID id = Ids.newId();
        int n = jdbc.sql("""
                INSERT INTO exam_incident_impact (id, tenant_id, platform_incident_id, source, started_at, description)
                VALUES (?, ?, ?, 'PLATFORM', ?, ?) ON CONFLICT (tenant_id, platform_incident_id) DO NOTHING""")
                .params(id, TenantContext.requireTenantId(), platformIncidentId, Timestamp.from(startedAt),
                        "Exam delivery service unavailable (detected automatically by the platform).").update();
        if (n == 0) return null;
        audit.record("exam.incident.opened", "exam_incident_impact", id.toString(), AuditOutcome.SUCCESS, Map.of("source", "PLATFORM",
                "platformIncident", platformIncidentId.toString()));
        outbox.append("exam_incident_impact", id.toString(), "exam.incident.started", Map.of("startedAt", WHEN.format(startedAt)));
        return id;
    }

    /** Closes the window of a platform outage for this organization and records the affected candidates. */
    @Transactional
    public UUID platformOutageResolved(UUID platformIncidentId, Instant startedAt, Instant endedAt) {
        UUID tenant = TenantContext.requireTenantId();
        List<UUID[]> affected = affectedCandidates(startedAt, endedAt, null);
        UUID existing = jdbc.sql("SELECT id FROM exam_incident_impact WHERE platform_incident_id = ?").param(platformIncidentId).query(UUID.class).optional().orElse(null);
        if (existing == null && affected.isEmpty()) return null;
        UUID id = existing == null ? Ids.newId() : existing;
        if (existing == null) {
            jdbc.sql("""
                    INSERT INTO exam_incident_impact (id, tenant_id, platform_incident_id, source, started_at, ended_at, description)
                    VALUES (?, ?, ?, 'PLATFORM', ?, ?, ?)""").params(id, tenant, platformIncidentId, Timestamp.from(startedAt), Timestamp.from(endedAt),
                    "Exam delivery service unavailable (detected automatically by the platform).").update();
        } else {
            jdbc.sql("UPDATE exam_incident_impact SET ended_at = ?, version = version + 1 WHERE id = ?").params(Timestamp.from(endedAt), id).update();
        }
        storeAffected(tenant, id, affected);
        audit.record("exam.incident.detected", "exam_incident_impact", id.toString(), AuditOutcome.SUCCESS, Map.of("source", "PLATFORM",
                "platformIncident", platformIncidentId.toString(), "candidates", String.valueOf(affected.size()),
                "startedAt", startedAt.toString(), "endedAt", endedAt.toString()));
        outbox.append("exam_incident_impact", id.toString(), "exam.incident.detected", Map.of("startedAt", WHEN.format(startedAt),
                "endedAt", WHEN.format(endedAt), "candidates", affected.size()));
        return id;
    }

    /** An institution reports an outage it observed (e.g. its own network failed, or a platform problem the detector missed). */
    @Transactional
    public Incident report(Instant startedAt, Instant endedAt, String description, UUID sessionId) {
        authz.require(MANAGE, null);
        if (endedAt.isBefore(startedAt)) throw ApiException.badRequest("invalid_window", "The end must not be before the start.");
        if (endedAt.isAfter(Instant.now().plusSeconds(60))) throw ApiException.badRequest("invalid_window", "Report an outage once it has ended.");
        if (Duration.between(startedAt, endedAt).toHours() > 24) throw ApiException.badRequest("invalid_window", "At most 24 hours per report.");
        UUID tenant = TenantContext.requireTenantId();
        UUID id = Ids.newId();
        jdbc.sql("""
                INSERT INTO exam_incident_impact (id, tenant_id, source, started_at, ended_at, description, reported_by)
                VALUES (?, ?, 'REPORTED', ?, ?, ?, ?)""").params(id, tenant, Timestamp.from(startedAt), Timestamp.from(endedAt), description.trim(),
                CurrentUser.require().userId()).update();
        List<UUID[]> affected = affectedCandidates(startedAt, endedAt, sessionId);
        storeAffected(tenant, id, affected);
        audit.recordSuccess("exam.incident.reported", "exam_incident_impact", id.toString(), Map.of("candidates", String.valueOf(affected.size()),
                "startedAt", startedAt.toString(), "endedAt", endedAt.toString()));
        return find(id, true);
    }

    /** Candidates present (heartbeat) or checked in to a session whose window overlapped the outage. */
    List<UUID[]> affectedCandidates(Instant start, Instant end, UUID onlySession) {
        return jdbc.sql("""
                SELECT DISTINCT candidate_id, session_id FROM (
                    SELECT p.candidate_id, p.session_id FROM delivery_presence p JOIN readiness_session s ON s.id = p.session_id
                    WHERE p.first_seen_at <= CAST(:end AS timestamptz) AND p.last_seen_at >= CAST(:start AS timestamptz) - CAST(:lead AS interval)
                    UNION
                    SELECT ci.candidate_id, ci.session_id FROM check_in ci JOIN readiness_session s ON s.id = ci.session_id
                    WHERE s.status = 'SCHEDULED' AND s.starts_at <= CAST(:end AS timestamptz) AND s.ends_at >= CAST(:start AS timestamptz)
                      AND ci.checked_in_at <= CAST(:end AS timestamptz)
                ) a WHERE CAST(:session AS uuid) IS NULL OR a.session_id = CAST(:session AS uuid)""")
                .param("start", Timestamp.from(start)).param("end", Timestamp.from(end)).param("lead", AFFECTED_LEAD.toSeconds() + " seconds")
                .param("session", onlySession)
                .query((rs, n) -> new UUID[] {rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)}).list();
    }

    private void storeAffected(UUID tenant, UUID impact, List<UUID[]> affected) {
        for (UUID[] a : affected) {
            jdbc.sql("INSERT INTO exam_incident_candidate (tenant_id, impact_id, candidate_id, session_id) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING")
                    .params(tenant, impact, a[0], a[1]).update();
        }
    }

    // ================================================================================================ compensation (four-eyes)

    @Transactional
    public Incident propose(UUID impactId, String action, Integer extensionMinutes, String rationale, long version) {
        authz.require(MANAGE, null);
        Incident i = find(impactId, false);
        if (i.endedAt() == null) throw ApiException.conflict("incident_ongoing", "Propose compensation once the incident has ended.");
        if (!"OPEN".equals(i.status())) throw ApiException.conflict("incident_not_open", "A compensation is already pending or decided for this incident.");
        if (i.version() != version) throw ApiException.conflict("concurrent_modification", "This incident changed. Reload and try again.");
        Integer minutes = switch (action) {
            case "EXTEND_SESSION" -> {
                if (extensionMinutes == null || extensionMinutes < 1 || extensionMinutes > 240) {
                    throw ApiException.badRequest("invalid_extension", "Give an extension between 1 and 240 minutes.");
                }
                yield extensionMinutes;
            }
            case "NEW_CHECK_IN", "NO_ACTION" -> null;
            default -> throw ApiException.badRequest("invalid_action", "Unknown compensation action.");
        };
        if (!"NO_ACTION".equals(action) && i.affected() == 0) {
            throw ApiException.badRequest("no_affected_candidates", "No candidate was affected; close the incident with “no action”.");
        }
        UUID id = Ids.newId();
        UUID tenant = TenantContext.requireTenantId();
        jdbc.sql("INSERT INTO exam_compensation (id, tenant_id, impact_id, action, extension_minutes, rationale, proposed_by) VALUES (?, ?, ?, ?, ?, ?, ?)")
                .params(id, tenant, impactId, action, minutes, rationale.trim(), CurrentUser.require().userId()).update();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("impactId", impactId.toString());
        payload.put("action", action);
        if (minutes != null) payload.put("extensionMinutes", minutes);
        payload.put("affectedCandidates", i.affected());
        payload.put("window", WHEN.format(i.startedAt()) + " – " + WHEN.format(i.endedAt()));
        payload.put("rationale", rationale.trim());
        String summary = describe(action, minutes) + " for " + i.affected() + " candidate(s) affected " + WHEN.format(i.startedAt()) + " – " + WHEN.format(i.endedAt());
        UUID approval = approvals.request(APPROVAL_TYPE, "exam_compensation", id.toString(), summary, payload, DECIDE, null, Duration.ofDays(2));
        jdbc.sql("UPDATE exam_compensation SET approval_id = ? WHERE id = ?").params(approval, id).update();
        jdbc.sql("UPDATE exam_incident_impact SET status = 'COMPENSATION_PENDING', version = version + 1 WHERE id = ?").param(impactId).update();
        audit.recordSuccess("exam.compensation.proposed", "exam_compensation", id.toString(), Map.of("impact", impactId.toString(), "action", action,
                "approval", approval.toString()));
        return find(impactId, true);
    }

    @Override
    public String type() {
        return APPROVAL_TYPE;
    }

    @Override
    public String label() {
        return "Candidate compensation";
    }

    @Override
    public boolean unchanged(ApprovalService.Request request) {
        return jdbc.sql("SELECT status FROM exam_compensation WHERE id = CAST(? AS uuid)").param(request.subjectId()).query(String.class).optional()
                .map("PROPOSED"::equals).orElse(false);
    }

    /** Applies the approved compensation: entitlements per affected candidate, email to each candidate, incident closed. */
    @Override
    public void onApproved(ApprovalService.Request request) {
        UUID tenant = TenantContext.requireTenantId();
        record C(UUID id, UUID impact, String action, Integer minutes) {}
        C c = jdbc.sql("SELECT id, impact_id, action, extension_minutes FROM exam_compensation WHERE id = CAST(? AS uuid) FOR UPDATE")
                .param(request.subjectId()).query((rs, n) -> new C(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        (Integer) rs.getObject(4))).single();
        jdbc.sql("UPDATE exam_compensation SET status = 'APPROVED', decided_at = now() WHERE id = ?").param(c.id()).update();
        if ("NO_ACTION".equals(c.action())) {
            jdbc.sql("UPDATE exam_incident_impact SET status = 'CLOSED_NO_ACTION', version = version + 1 WHERE id = ?").param(c.impact()).update();
            audit.recordSuccess("exam.compensation.applied", "exam_compensation", c.id().toString(), Map.of("action", c.action(), "candidates", "0"));
            outbox.append("exam_compensation", c.id().toString(), "exam.compensation.decided", Map.of("decision", "APPROVED", "impactId", c.impact().toString(),
                    "incident", c.impact().toString().substring(0, 8)));
            return;
        }
        record T(UUID candidate, UUID session, String sessionName, Instant sessionEnd, String email, String portalKey) {}
        List<T> targets = jdbc.sql("""
                SELECT ic.candidate_id, ic.session_id, s.name, s.ends_at, ca.email_enc, p.portal_key FROM exam_incident_candidate ic
                JOIN readiness_session s ON s.id = ic.session_id JOIN candidate_account ca ON ca.id = ic.candidate_id JOIN exam_portal p ON p.id = s.portal_id
                WHERE ic.impact_id = ?""").param(c.impact()).query((rs, n) -> new T(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                rs.getTimestamp(4).toInstant(), cipher.decrypt(rs.getString(5), ExamAdminService.EMAIL_FIELD, tenant), rs.getString(6))).list();
        Instant now = Instant.now();
        for (T t : targets) {
            // a candidate-bound, single-use entitlement; there is no secret to send, the candidate uses it from the signed-in portal
            Instant until = "EXTEND_SESSION".equals(c.action())
                    ? max(t.sessionEnd(), now).plus(Duration.ofMinutes(c.minutes()))
                    : max(t.sessionEnd(), now).plus(Duration.ofHours(48));
            UUID code = Ids.newId();
            jdbc.sql("""
                    INSERT INTO session_code (id, tenant_id, session_id, code_hash, hint, valid_from, valid_until, issued_for)
                    VALUES (?, ?, ?, ?, '—', ?, ?, ?)""").params(code, tenant, t.session(), cipher.blindIndex(code + ":bound", ExamAdminService.CODE_PURPOSE),
                    Timestamp.from(now), Timestamp.from(until), t.candidate()).update();
            jdbc.sql("""
                    INSERT INTO candidate_compensation (id, tenant_id, compensation_id, candidate_id, session_id, extension_minutes, new_code_id, notified_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, now())""").params(Ids.newId(), tenant, c.id(), t.candidate(), t.session(), c.minutes(), code).update();
            notifications.email(t.email(), "exams", "Compensation for an exam service interruption",
                    "An interruption of the exam service affected your session \"" + t.sessionName() + "\". Your institution approved: "
                            + describe(c.action(), c.minutes()) + ", available until " + WHEN.format(until) + ".\n\n"
                            + "Sign in at " + props.examAppBaseUrl() + "/p/" + t.portalKey() + " to use it. No code is needed.\n\n"
                            + "This message was sent because your exam account is registered with the institution. It contains no sign-in link or code.");
        }
        jdbc.sql("UPDATE exam_incident_impact SET status = 'COMPENSATED', version = version + 1 WHERE id = ?").param(c.impact()).update();
        audit.recordSuccess("exam.compensation.applied", "exam_compensation", c.id().toString(), Map.of("action", c.action(),
                "candidates", String.valueOf(targets.size()), "approval", request.id().toString()));
        outbox.append("exam_compensation", c.id().toString(), "exam.compensation.decided", Map.of("decision", "APPROVED", "impactId", c.impact().toString(),
                "incident", c.impact().toString().substring(0, 8)));
    }

    @Override
    public void onRejected(ApprovalService.Request request) {
        reopen(request, "REJECTED");
    }

    @Override
    public void onClosed(ApprovalService.Request request) {
        reopen(request, "CANCELLED");
    }

    private void reopen(ApprovalService.Request request, String status) {
        UUID impact = jdbc.sql("UPDATE exam_compensation SET status = ?, decided_at = now() WHERE id = CAST(? AS uuid) AND status = 'PROPOSED' RETURNING impact_id")
                .params(status, request.subjectId()).query(UUID.class).optional().orElse(null);
        if (impact == null) return;
        jdbc.sql("UPDATE exam_incident_impact SET status = 'OPEN', version = version + 1 WHERE id = ? AND status = 'COMPENSATION_PENDING'").param(impact).update();
        audit.record("exam.compensation." + status.toLowerCase(), "exam_compensation", request.subjectId(), AuditOutcome.SUCCESS, Map.of("impact", impact.toString()));
        if ("REJECTED".equals(status)) {
            outbox.append("exam_compensation", request.subjectId(), "exam.compensation.decided", Map.of("decision", "REJECTED", "impactId", impact.toString(),
                    "incident", impact.toString().substring(0, 8)));
        }
    }

    // ================================================================================================ helpers

    static String describe(String action, Integer minutes) {
        return switch (action) {
            case "EXTEND_SESSION" -> "an extra " + minutes + " minutes and a new check-in";
            case "NEW_CHECK_IN" -> "a new check-in to the session";
            default -> "no compensation (incident closed)";
        };
    }

    private void requireAny() {
        if (!authz.has(MANAGE) && !authz.has(DECIDE)) throw new AccessDeniedException("Missing permission " + MANAGE);
    }

    private static Instant max(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private static Instant ts(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }
}
