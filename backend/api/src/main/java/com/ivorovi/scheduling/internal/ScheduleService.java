package com.ivorovi.scheduling.internal;

import com.ivorovi.assessments.PublishedAssessments;
import com.ivorovi.exams.ExamSessions;
import com.ivorovi.platform.audit.AuditLog;
import com.ivorovi.platform.crypto.FieldCipher;
import com.ivorovi.platform.ids.Ids;
import com.ivorovi.platform.mail.EmailMessage;
import com.ivorovi.platform.mail.EmailSender;
import com.ivorovi.platform.outbox.Outbox;
import com.ivorovi.platform.security.Authz;
import com.ivorovi.platform.security.CurrentUser;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.platform.web.ApiException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * M2 scheduling and enrolment. A schedule binds one published assessment version (never a later one) to an exam portal,
 * an institution, a window and one exam session (codes and check-in live in the exams module). Enrolment is by bulk
 * upload (validated, previewable, idempotent) or single entry; invitations are emailed with the portal link. Extra time
 * needs a reason and is audited. Personal data never goes to logs or audit details (counts and ids only).
 */
@Service
public class ScheduleService {

    private static final Logger log = LoggerFactory.getLogger(ScheduleService.class);
    public static final String READ = "exam.schedule.read";
    public static final String MANAGE = "exam.schedule.manage";
    static final String EMAIL_FIELD = "exam_enrolment.email";
    static final Duration CHECK_IN_OPENS_BEFORE = Duration.ofMinutes(60);
    static final int MAX_ROWS = 5000;
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s,;]{1,64}@[^@\\s,;]{1,253}\\.[^@\\s,;]{2,}$");

    public record ScheduleInput(UUID versionId, UUID portalId, UUID orgUnitId, String title, Instant opensAt, Instant closesAt, Instant lateEntryUntil,
                                String instructions, Boolean calculator) {}

    @Schema(name = "ExamSchedule")
    public record Schedule(UUID id, String title, UUID orgUnitId, String orgUnitName, UUID versionId, UUID assessmentId, String assessmentCode,
                           int versionNo, String purpose, UUID portalId, String portalName, String portalUrl, UUID sessionId, Instant opensAt, Instant closesAt,
                           Instant lateEntryUntil, int durationMinutes, String instructions, boolean calculator, String status, int enrolled, int linked,
                           int invited, int checkedIn, String createdByName, Instant createdAt, long version) {}

    @Schema(name = "ExamEnrolment")
    public record Enrolment(UUID id, String email, String displayName, boolean linked, int extraMinutes, String extraReason, String invitationStatus,
                            Instant invitedAt, String status, boolean checkedIn, long version) {}

    @Schema(name = "EnrolmentUploadRow")
    public record RowIssue(int row, String message) {}

    @Schema(name = "EnrolmentUploadResult")
    public record UploadResult(boolean applied, int rows, int valid, int created, int alreadyEnrolled, List<RowIssue> errors) {}

    @Schema(name = "EnrolmentInvitationResult")
    public record InviteResult(int sent, int failed, int skipped) {}

    record Row(UUID id, UUID orgUnitId, UUID portalId, UUID sessionId, UUID versionId, String title, Instant opensAt, Instant closesAt, String status) {}

    private final JdbcClient jdbc;
    private final Authz authz;
    private final AuditLog audit;
    private final Outbox outbox;
    private final FieldCipher cipher;
    private final PublishedAssessments assessments;
    private final ExamSessions sessions;
    private final EmailSender mail;
    private final TransactionTemplate tx;

    ScheduleService(JdbcClient jdbc, Authz authz, AuditLog audit, Outbox outbox, FieldCipher cipher, PublishedAssessments assessments, ExamSessions sessions,
                    EmailSender mail, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.authz = authz;
        this.audit = audit;
        this.outbox = outbox;
        this.cipher = cipher;
        this.assessments = assessments;
        this.sessions = sessions;
        this.mail = mail;
        this.tx = tx;
    }

    private UUID me() {
        return CurrentUser.require().userId();
    }

    private Row row(UUID id, boolean lock) {
        Row r = jdbc.sql("SELECT id, org_unit_id, portal_id, session_id, version_id, title, opens_at, closes_at, status FROM exam_schedule WHERE id = ?"
                        + (lock ? " FOR UPDATE" : "")).param(id)
                .query((rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getObject(4, UUID.class),
                        rs.getObject(5, UUID.class), rs.getString(6), rs.getTimestamp(7).toInstant(), rs.getTimestamp(8).toInstant(), rs.getString(9)))
                .optional().orElseThrow(() -> ApiException.notFound("Schedule"));
        if (!authz.hasInOrgUnit(READ, r.orgUnitId())) throw ApiException.notFound("Schedule");
        return r;
    }

    private Row manageable(UUID id) {
        Row r = row(id, true);
        if (!authz.hasInOrgUnit(MANAGE, r.orgUnitId())) throw new AccessDeniedException("Missing permission " + MANAGE);
        if (!"SCHEDULED".equals(r.status())) throw ApiException.conflict("schedule_cancelled", "This schedule is cancelled.");
        return r;
    }

    // ================================================================================================ schedules

    @Transactional
    public Schedule create(ScheduleInput in) {
        if (!authz.hasInOrgUnit(MANAGE, in.orgUnitId())) throw new AccessDeniedException("Missing permission " + MANAGE);
        if (in.versionId() == null || in.portalId() == null) throw ApiException.badRequest("invalid_schedule", "Choose a published assessment and an exam portal.");
        PublishedAssessments.Published v = assessments.version(in.versionId());               // 404 unless readable and published at some point
        if (!"PUBLISHED".equals(v.state())) {
            throw ApiException.conflict("version_not_current", "Only the currently published version of an assessment can be scheduled.");
        }
        if (v.orgUnitId() != null) {
            boolean inside = in.orgUnitId() != null && jdbc.sql("SELECT count(*) FROM org_unit a, org_unit u WHERE a.id = ? AND u.id = ? AND u.path <@ a.path")
                    .params(v.orgUnitId(), in.orgUnitId()).query(Long.class).single() > 0;
            if (!inside) throw ApiException.badRequest("unit_not_eligible", "Schedule this assessment within its own institution.");
        }
        String title = in.title() == null || in.title().isBlank() ? v.title() : in.title().strip();
        if (title.length() > 200) throw ApiException.badRequest("invalid_title", "The title is longer than 200 characters.");
        if (in.opensAt() == null || in.closesAt() == null || !in.closesAt().isAfter(in.opensAt())) {
            throw ApiException.badRequest("invalid_window", "The examination must close after it opens.");
        }
        if (!in.closesAt().isAfter(Instant.now())) throw ApiException.badRequest("invalid_window", "The examination window is already over.");
        if (Duration.between(in.opensAt(), in.closesAt()).toMinutes() < v.durationMinutes()) {
            throw ApiException.badRequest("invalid_window", "The window is shorter than the examination's " + v.durationMinutes() + " minutes.");
        }
        if (in.lateEntryUntil() != null && (in.lateEntryUntil().isBefore(in.opensAt()) || in.lateEntryUntil().isAfter(in.closesAt()))) {
            throw ApiException.badRequest("invalid_late_entry", "Late entry must close within the window.");
        }
        if (in.instructions() != null && in.instructions().length() > 4000) throw ApiException.badRequest("too_long", "Instructions: at most 4,000 characters.");
        ExamSessions.Session s = sessions.create(in.portalId(), in.orgUnitId(), title, in.opensAt().minus(CHECK_IN_OPENS_BEFORE), in.closesAt());
        UUID id = Ids.newId();
        jdbc.sql("""
                INSERT INTO exam_schedule (id, tenant_id, org_unit_id, version_id, portal_id, session_id, title, opens_at, closes_at, late_entry_until,
                    duration_minutes, instructions, calculator, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(id, TenantContext.requireTenantId(), in.orgUnitId(), v.versionId(), in.portalId(), s.id(), title, Timestamp.from(in.opensAt()),
                        Timestamp.from(in.closesAt()), in.lateEntryUntil() == null ? null : Timestamp.from(in.lateEntryUntil()), v.durationMinutes(),
                        blank(in.instructions()), Boolean.TRUE.equals(in.calculator()), me()).update();
        assessments.reference(v.versionId(), "exam_schedule", id.toString());
        Map<String, String> d = new LinkedHashMap<>();
        d.put("versionId", v.versionId().toString());
        d.put("assessment", v.code());
        d.put("opensAt", in.opensAt().toString());
        d.put("closesAt", in.closesAt().toString());
        audit.recordSuccess("exam.schedule.created", "exam_schedule", id.toString(), d);
        outbox.append("exam_schedule", id.toString(), "exam.schedule.created", Map.of("scheduleId", id.toString(), "versionId", v.versionId().toString()));
        return get(id);
    }

    @Transactional
    public Schedule cancel(UUID id, String reason) {
        Row r = manageable(id);
        if (reason == null || reason.isBlank()) throw ApiException.badRequest("reason_required", "Give a reason for cancelling.");
        jdbc.sql("UPDATE exam_schedule SET status = 'CANCELLED', updated_at = now(), version = version + 1 WHERE id = ?").param(id).update();
        sessions.cancel(r.sessionId());
        audit.recordSuccess("exam.schedule.cancelled", "exam_schedule", id.toString(), Map.of("reason", reason.strip().length() > 300 ? reason.strip().substring(0, 300) : reason.strip()));
        return get(id);
    }

    @Transactional(readOnly = true)
    public List<Schedule> list() {
        List<UUID> ids = jdbc.sql("SELECT id, org_unit_id FROM exam_schedule ORDER BY opens_at DESC LIMIT 500")
                .query((rs, n) -> authz.hasInOrgUnit(READ, rs.getObject(2, UUID.class)) ? rs.getObject(1, UUID.class) : null).list()
                .stream().filter(java.util.Objects::nonNull).toList();
        return ids.stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public Schedule get(UUID id) {
        row(id, false);
        return view(id);
    }

    private Schedule view(UUID id) {
        return jdbc.sql("""
                SELECT s.id, s.title, s.org_unit_id, ou.name AS unit, s.version_id, v.assessment_id, a.code, v.version_no, a.purpose, s.portal_id, p.name AS portal,
                       p.portal_key, s.session_id, s.opens_at, s.closes_at, s.late_entry_until, s.duration_minutes, s.instructions, s.calculator, s.status,
                       (SELECT count(*) FROM exam_enrolment e WHERE e.schedule_id = s.id AND e.status = 'ACTIVE') AS enrolled,
                       (SELECT count(*) FROM exam_enrolment e WHERE e.schedule_id = s.id AND e.status = 'ACTIVE' AND e.candidate_id IS NOT NULL) AS linked,
                       (SELECT count(*) FROM exam_enrolment e WHERE e.schedule_id = s.id AND e.status = 'ACTIVE' AND e.invitation_status = 'SENT') AS invited,
                       (SELECT count(*) FROM check_in c WHERE c.session_id = s.session_id) AS checked_in,
                       u.display_name AS creator, s.created_at, s.version
                FROM exam_schedule s JOIN assessment_version v ON v.id = s.version_id JOIN assessment a ON a.id = v.assessment_id
                JOIN exam_portal p ON p.id = s.portal_id LEFT JOIN org_unit ou ON ou.id = s.org_unit_id LEFT JOIN user_account u ON u.id = s.created_by
                WHERE s.id = ?""").param(id)
                .query((rs, n) -> new Schedule(rs.getObject("id", UUID.class), rs.getString("title"), rs.getObject("org_unit_id", UUID.class), rs.getString("unit"),
                        rs.getObject("version_id", UUID.class), rs.getObject("assessment_id", UUID.class), rs.getString("code"), rs.getInt("version_no"),
                        rs.getString("purpose"), rs.getObject("portal_id", UUID.class), rs.getString("portal"), portalUrl(rs.getString("portal_key")),
                        rs.getObject("session_id", UUID.class), ts(rs, "opens_at"), ts(rs, "closes_at"), ts(rs, "late_entry_until"), rs.getInt("duration_minutes"),
                        rs.getString("instructions"), rs.getBoolean("calculator"), rs.getString("status"), rs.getInt("enrolled"), rs.getInt("linked"),
                        rs.getInt("invited"), rs.getInt("checked_in"), rs.getString("creator"), ts(rs, "created_at"), rs.getLong("version"))).single();
    }

    /** The portal link (not secret); unavailable to readers without exam.session.manage, who then see no link. */
    private String portalUrl(String key) {
        try {
            return sessions.portals().stream().filter(p -> key.equals(p.portalKey())).map(ExamSessions.Portal::url).findFirst().orElse(null);
        } catch (AccessDeniedException e) {
            return null;
        }
    }

    // ================================================================================================ enrolment

    @Transactional(readOnly = true)
    public List<Enrolment> enrolments(UUID scheduleId) {
        Row r = row(scheduleId, false);
        UUID tenant = TenantContext.requireTenantId();
        return jdbc.sql("""
                SELECT e.id, e.email_enc, e.display_name, e.candidate_id, e.extra_minutes, e.extra_reason, e.invitation_status, e.invited_at, e.status,
                       EXISTS (SELECT 1 FROM check_in c WHERE c.session_id = ? AND c.candidate_id = e.candidate_id) AS checked_in, e.version
                FROM exam_enrolment e WHERE e.schedule_id = ? ORDER BY e.created_at, e.id""").params(r.sessionId(), scheduleId)
                .query((rs, n) -> new Enrolment(rs.getObject(1, UUID.class), cipher.decrypt(rs.getString(2), EMAIL_FIELD, tenant), rs.getString(3),
                        rs.getObject(4) != null, rs.getInt(5), rs.getString(6), rs.getString(7), ts(rs, "invited_at"), rs.getString(9), rs.getBoolean(10),
                        rs.getLong(11))).list();
    }

    /**
     * Bulk enrolment from CSV text: {@code email[,name[,extra_minutes,extra_reason]]}, an optional header row, at most
     * 5,000 rows. With {@code apply = false} nothing is stored (preview). Re-uploading the same list adds nothing new.
     */
    @Transactional
    public UploadResult upload(UUID scheduleId, String csv, boolean apply) {
        Row r = manageable(scheduleId);
        if (csv == null || csv.isBlank()) throw ApiException.badRequest("empty_upload", "The file is empty.");
        String[] lines = csv.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        List<RowIssue> errors = new ArrayList<>();
        List<String[]> valid = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int rows = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty()) continue;
            if (i == 0 && line.toLowerCase(Locale.ROOT).startsWith("email")) continue;          // header
            rows++;
            if (rows > MAX_ROWS) throw ApiException.badRequest("too_many_rows", "At most " + MAX_ROWS + " candidates per upload.");
            List<String> f = csvFields(line);
            String email = f.isEmpty() ? "" : f.get(0).strip().toLowerCase(Locale.ROOT);
            String name = f.size() > 1 ? f.get(1).strip() : "";
            String extraText = f.size() > 2 ? f.get(2).strip() : "";
            String reason = f.size() > 3 ? f.get(3).strip() : "";
            int lineNo = i + 1;
            if (!EMAIL.matcher(email).matches()) { errors.add(new RowIssue(lineNo, "Not a valid email address.")); continue; }
            if (!seen.add(email)) { errors.add(new RowIssue(lineNo, "This email appears earlier in the file.")); continue; }
            if (name.length() > 200) { errors.add(new RowIssue(lineNo, "The name is longer than 200 characters.")); continue; }
            int extra = 0;
            if (!extraText.isEmpty()) {
                try {
                    extra = Integer.parseInt(extraText);
                } catch (NumberFormatException e) {
                    errors.add(new RowIssue(lineNo, "Extra time must be a whole number of minutes.")); continue;
                }
                if (extra < 0 || extra > 600) { errors.add(new RowIssue(lineNo, "Extra time must be 0–600 minutes.")); continue; }
                if (extra > 0 && reason.isEmpty()) { errors.add(new RowIssue(lineNo, "Extra time needs a reason.")); continue; }
                if (reason.length() > 500) { errors.add(new RowIssue(lineNo, "The reason is longer than 500 characters.")); continue; }
            }
            valid.add(new String[] {email, name, String.valueOf(extra), reason});
        }
        int created = 0;
        int already = 0;
        if (apply) {
            UUID tenant = TenantContext.requireTenantId();
            for (String[] v : valid) {
                String index = sessions.emailIndex(v[0]);
                UUID candidate = sessions.candidateByEmail(r.portalId(), v[0]).orElse(null);
                int extra = Integer.parseInt(v[2]);
                int n = jdbc.sql("""
                        INSERT INTO exam_enrolment (id, tenant_id, schedule_id, email_enc, email_index, display_name, candidate_id, extra_minutes, extra_reason, source, created_by)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'UPLOAD', ?) ON CONFLICT (schedule_id, email_index) DO NOTHING""")
                        .params(Ids.newId(), tenant, scheduleId, cipher.encrypt(v[0], EMAIL_FIELD, tenant), index, v[1].isEmpty() ? null : v[1], candidate, extra,
                                extra > 0 ? v[3] : null, me()).update();
                if (n > 0) created++; else already++;
            }
            audit.recordSuccess("exam.enrolment.uploaded", "exam_schedule", scheduleId.toString(), Map.of("rows", String.valueOf(rows),
                    "created", String.valueOf(created), "alreadyEnrolled", String.valueOf(already), "rejected", String.valueOf(errors.size())));
        }
        return new UploadResult(apply, rows, valid.size(), created, already, errors);
    }

    @Transactional
    public Enrolment setExtraTime(UUID scheduleId, UUID enrolmentId, int minutes, String reason, long version) {
        manageable(scheduleId);
        if (minutes < 0 || minutes > 600) throw ApiException.badRequest("invalid_extra_time", "Extra time must be 0–600 minutes.");
        if (minutes > 0 && (reason == null || reason.isBlank())) throw ApiException.badRequest("reason_required", "Extra time needs a reason.");
        int n = jdbc.sql("UPDATE exam_enrolment SET extra_minutes = ?, extra_reason = ?, version = version + 1 WHERE id = ? AND schedule_id = ? AND version = ?")
                .params(minutes, minutes > 0 ? reason.strip() : null, enrolmentId, scheduleId, version).update();
        if (n == 0) throw ApiException.conflict("concurrent_modification", "This enrolment was changed. Reload and try again.");
        audit.recordSuccess("exam.enrolment.extra_time", "exam_enrolment", enrolmentId.toString(), Map.of("schedule", scheduleId.toString(),
                "minutes", String.valueOf(minutes)));
        return enrolments(scheduleId).stream().filter(e -> e.id().equals(enrolmentId)).findFirst().orElseThrow();
    }

    @Transactional
    public void withdraw(UUID scheduleId, UUID enrolmentId) {
        manageable(scheduleId);
        int n = jdbc.sql("UPDATE exam_enrolment SET status = 'WITHDRAWN', version = version + 1 WHERE id = ? AND schedule_id = ? AND status = 'ACTIVE'")
                .params(enrolmentId, scheduleId).update();
        if (n == 0) throw ApiException.notFound("Enrolment");
        audit.recordSuccess("exam.enrolment.withdrawn", "exam_enrolment", enrolmentId.toString(), Map.of("schedule", scheduleId.toString()));
    }

    /** Emails every active enrolment not yet invited (or whose earlier email failed). Sending happens outside the transaction. */
    public InviteResult invite(UUID scheduleId) {
        record Target(UUID id, String email, String name) {}
        record Ctx(Schedule s, List<Target> targets, String org) {}
        Ctx c = tx.execute(t -> {
            manageable(scheduleId);
            UUID tenant = TenantContext.requireTenantId();
            List<Target> targets = jdbc.sql("""
                    SELECT id, email_enc, display_name FROM exam_enrolment WHERE schedule_id = ? AND status = 'ACTIVE' AND invitation_status IN ('NOT_SENT', 'FAILED')
                    ORDER BY created_at LIMIT 5000""").param(scheduleId)
                    .query((rs, n) -> new Target(rs.getObject(1, UUID.class), cipher.decrypt(rs.getString(2), EMAIL_FIELD, tenant), rs.getString(3))).list();
            String org = jdbc.sql("SELECT name FROM tenant WHERE id = ?").param(tenant).query(String.class).optional().orElse("your institution");
            return new Ctx(view(scheduleId), targets, org);
        });
        DateTimeFormatter when = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm z").withZone(ZoneId.of("Asia/Kolkata"));
        int sent = 0;
        int failed = 0;
        for (Target t : c.targets()) {
            String body = """
                    Hello%s,

                    %s has scheduled you for the examination "%s".

                    It opens %s and closes %s (%d minutes).
                    Sign in or create your exam account with this email address on the exam portal:
                    %s

                    You will need the session code given to you at the examination. If you were not expecting this email, you can ignore it.
                    """.formatted(t.name() == null ? "" : " " + t.name(), c.org(), c.s().title(), when.format(c.s().opensAt()), when.format(c.s().closesAt()),
                    c.s().durationMinutes(), c.s().portalUrl());
            String status;
            try {
                mail.send(new EmailMessage(t.email(), "Examination: " + c.s().title(), body));
                status = "SENT";
                sent++;
            } catch (RuntimeException e) {
                log.warn("Examination invitation {} could not be sent: {}", t.id(), e.getClass().getSimpleName());
                status = "FAILED";
                failed++;
            }
            String st = status;
            tx.executeWithoutResult(x -> jdbc.sql("UPDATE exam_enrolment SET invitation_status = ?, invited_at = CASE WHEN ? = 'SENT' THEN now() ELSE invited_at END, version = version + 1 WHERE id = ?")
                    .params(st, st, t.id()).update());
        }
        int s0 = sent;
        int f0 = failed;
        tx.executeWithoutResult(x -> audit.recordSuccess("exam.enrolment.invited", "exam_schedule", scheduleId.toString(),
                Map.of("sent", String.valueOf(s0), "failed", String.valueOf(f0))));
        return new InviteResult(sent, failed, 0);
    }

    /** Single-use session codes for this schedule's session (shown once; issued through the exams module). */
    @Transactional
    public List<ExamSessions.IssuedCode> issueCodes(UUID scheduleId, int count) {
        Row r = manageable(scheduleId);
        if (count < 1 || count > 2000) throw ApiException.badRequest("invalid_count", "Issue 1–2,000 codes at a time.");
        return sessions.issueCodes(r.sessionId(), count, r.opensAt().minus(CHECK_IN_OPENS_BEFORE), r.closesAt());
    }

    @Transactional(readOnly = true)
    public List<ExamSessions.Code> codes(UUID scheduleId) {
        return sessions.codes(row(scheduleId, false).sessionId());
    }

    // ================================================================================================ helpers

    /** Minimal CSV field splitting with double-quoted fields. */
    static List<String> csvFields(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quoted) {
                if (ch == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                else if (ch == '"') quoted = false;
                else cur.append(ch);
            } else if (ch == '"') quoted = true;
            else if (ch == ',') { out.add(cur.toString()); cur.setLength(0); }
            else cur.append(ch);
        }
        out.add(cur.toString());
        return out;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static Instant ts(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }
}
