package com.ivorovi.proctoring.internal;

import com.ivorovi.exams.CandidateNotices;
import com.ivorovi.platform.audit.AuditLog;
import com.ivorovi.platform.config.DeploymentProperties;
import com.ivorovi.platform.ids.Ids;
import com.ivorovi.platform.outbox.Outbox;
import com.ivorovi.platform.scanning.MalwareScanner;
import com.ivorovi.platform.security.Authz;
import com.ivorovi.platform.security.CurrentUser;
import com.ivorovi.platform.storage.ObjectStorage;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.platform.web.ApiException;
import com.ivorovi.scheduling.CandidateIncidents;
import com.ivorovi.scheduling.Schedules;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Proctoring of attempts of the existing examination engine (add-on B). Staff configure a schedule's level and review
 * flags; candidates consent, then send browser signals and (at level SNAPSHOTS) webcam snapshots. Rules raise flags; a
 * person decides every flag. Nothing here changes an attempt, a score or a result: an incident or a termination request
 * goes through the scheduling module's invigilation workflow (same permissions, four-eyes termination).
 */
@Service
public class ProctoringService implements CandidateNotices {

    public static final String CONFIGURE = "proctoring.configure";
    public static final String REVIEW = "proctoring.review";
    public static final String EVIDENCE = "proctoring.evidence.view";
    static final String SCHEDULE_READ = "exam.schedule.read";
    static final Set<String> LEVELS = Set.of("NONE", "BASIC", "SNAPSHOTS");
    static final Set<String> DECISIONS = Set.of("DISMISS", "WARN", "INCIDENT", "TERMINATION_REQUESTED");
    static final Set<String> SIGNAL_KINDS = Set.of("FOCUS_LOST", "TAB_HIDDEN", "FULLSCREEN_EXIT", "COPY", "PASTE", "MULTI_DISPLAY", "CAMERA_UNAVAILABLE");
    static final int MAX_BATCH = 50;
    static final int MAX_SIGNALS_PER_ATTEMPT = 2000;
    static final int MAX_SNAPSHOT_BYTES = 200 * 1024;
    static final int MAX_SNAPSHOTS_PER_ATTEMPT = 400;

    /**
     * The notice shown before a proctored attempt. DRAFT wording pending counsel's DPDP review (P-4): the UI labels it as a
     * draft. The version is recorded with each consent together with the hash of exactly the text shown.
     */
    public static final String CONSENT_VERSION = "draft-2026-11-16";

    static String consentText(String level) {
        String common = """
                This examination is proctored. While you take it, the examination page records when it loses focus or is hidden, \
                when full-screen mode is left, copy and paste attempts and, where your browser reports it, a second display. \
                These records only raise flags: a member of staff reviews every flag and decides. Nothing is decided automatically, \
                and no flag changes your answers, your score or your result.""";
        String snapshots = """
                 Your webcam takes a still photo at the start and at intervals. Photos are kept as evidence for a limited time \
                and are then deleted. Only examination controllers can view them, and every view is recorded. No audio, no video \
                and no screen content is recorded, and no biometric analysis is made.""";
        String end = """
                 You can decline. If you decline, you cannot start this proctored examination; contact the examination office.""";
        return common + ("SNAPSHOTS".equals(level) ? snapshots : "") + end;
    }

    // ------------------------------------------------------------------------------------------------ API types

    /** An attempt whose ownership by the calling candidate the delivery layer has verified. */
    public record CandidateAttempt(UUID attemptId, UUID scheduleId, UUID enrolmentId, String status) {}

    @Schema(name = "ProctoringSetting")
    public record Setting(UUID scheduleId, String level, int snapshotIntervalSeconds, int retentionDays, String consentVersion, boolean editable,
                          String updatedByName, Instant updatedAt, long version) {}

    public record SettingInput(String level, Integer snapshotIntervalSeconds, Integer retentionDays, Long version) {}

    @Schema(name = "ProctoringCandidateView", description = "What a candidate sees before and during a proctored attempt.")
    public record CandidateView(String level, int snapshotIntervalSeconds, int retentionDays, String consentVersion, boolean consentDraft, String consentText,
                                String consentHash, String consent) {}

    @Schema(name = "ProctoringFlag")
    public record Flag(UUID id, UUID attemptId, UUID enrolmentId, String candidateName, String rule, String description, String severity, Instant firstAt,
                       Instant lastAt, int signalCount, String status, Decision decision) {}

    @Schema(name = "ProctoringDecision")
    public record Decision(String decision, String reason, String warningText, UUID incidentId, UUID approvalId, String decidedByName, Instant decidedAt) {}

    @Schema(name = "ProctoringSignal")
    public record SignalView(String kind, Instant at, Integer durationMs) {}

    @Schema(name = "ProctoringSnapshot")
    public record SnapshotView(UUID id, Instant takenAt, String scanStatus, boolean available, Instant expiresAt) {}

    @Schema(name = "ProctoringFlagDetail")
    public record FlagDetail(Flag flag, List<SignalView> signals, List<SnapshotView> snapshots, boolean evidenceVisible) {}

    public record SignalInput(String kind, Instant at, Integer durationMs) {}

    public record DecisionInput(String decision, String reason, String warningText) {}

    @Schema(name = "ProctoringScheduleSummary")
    public record Summary(String level, int open, int high, int decided, int consentsGiven, int consentsDeclined) {}

    record Sched(UUID id, UUID orgUnitId, Instant opensAt) {}

    private final JdbcClient jdbc;
    private final Authz authz;
    private final AuditLog audit;
    private final Outbox outbox;
    private final Schedules schedules;
    private final CandidateIncidents incidents;
    private final ObjectStorage storage;
    private final ObjectProvider<MalwareScanner> scanner;
    private final DeploymentProperties deployment;

    ProctoringService(JdbcClient jdbc, Authz authz, AuditLog audit, Outbox outbox, Schedules schedules, CandidateIncidents incidents, ObjectStorage storage,
                      ObjectProvider<MalwareScanner> scanner, DeploymentProperties deployment) {
        this.jdbc = jdbc;
        this.authz = authz;
        this.audit = audit;
        this.outbox = outbox;
        this.schedules = schedules;
        this.incidents = incidents;
        this.storage = storage;
        this.scanner = scanner;
        this.deployment = deployment;
    }

    // ================================================================================================ staff: settings

    private Sched schedule(UUID scheduleId, String... anyOf) {
        Schedules.Ref r = schedules.find(scheduleId).orElseThrow(() -> ApiException.notFound("Schedule"));
        for (String p : anyOf) if (authz.hasInOrgUnit(p, r.orgUnitId())) return new Sched(r.id(), r.orgUnitId(), r.opensAt());
        if (authz.hasInOrgUnit(SCHEDULE_READ, r.orgUnitId())) throw new AccessDeniedException("Missing permission " + String.join(" or ", anyOf));
        throw ApiException.notFound("Schedule");
    }

    @Transactional(readOnly = true)
    public Setting setting(UUID scheduleId) {
        Sched s = schedule(scheduleId, CONFIGURE, REVIEW);
        return settingOf(s);
    }

    private Setting settingOf(Sched s) {
        boolean editable = Instant.now().isBefore(s.opensAt()) && authz.hasInOrgUnit(CONFIGURE, s.orgUnitId());
        return jdbc.sql("""
                SELECT p.level, p.snapshot_interval_s, p.retention_days, p.consent_version, u.display_name, p.updated_at, p.version
                FROM proctoring_setting p LEFT JOIN user_account u ON u.id = p.updated_by WHERE p.schedule_id = ?""").param(s.id())
                .query((rs, n) -> new Setting(s.id(), rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getString(4), editable, rs.getString(5),
                        rs.getTimestamp(6).toInstant(), rs.getLong(7))).optional()
                .orElse(new Setting(s.id(), "NONE", 120, 30, CONSENT_VERSION, editable, null, null, -1));
    }

    /** Sets the level before the window opens; once candidates may start, the level never changes (they consented to it). */
    @Transactional
    public Setting configure(UUID scheduleId, SettingInput in) {
        Sched s = schedule(scheduleId, CONFIGURE);
        if (!authz.hasInOrgUnit(CONFIGURE, s.orgUnitId())) throw new AccessDeniedException("Missing permission " + CONFIGURE);
        if (!Instant.now().isBefore(s.opensAt())) {
            throw ApiException.conflict("window_open", "The examination window has opened: candidates consented to the current proctoring level, so it cannot change.");
        }
        String level = in.level() == null ? "" : in.level().strip().toUpperCase();
        if (!LEVELS.contains(level)) throw ApiException.badRequest("invalid_level", "Proctoring level: NONE, BASIC or SNAPSHOTS.");
        int interval = in.snapshotIntervalSeconds() == null ? 120 : in.snapshotIntervalSeconds();
        if (interval < 60 || interval > 600) throw ApiException.badRequest("invalid_interval", "Snapshots every 60–600 seconds.");
        int retention = in.retentionDays() == null ? 30 : in.retentionDays();
        if (retention < 7 || retention > 90) throw ApiException.badRequest("invalid_retention", "Evidence is kept 7–90 days.");
        Setting current = settingOf(s);
        long expected = in.version() == null ? -1 : in.version();
        if (expected != current.version()) throw ApiException.conflict("concurrent_modification", "The proctoring settings were changed. Reload and try again.");
        UUID me = CurrentUser.require().userId();
        jdbc.sql("""
                INSERT INTO proctoring_setting (tenant_id, schedule_id, level, snapshot_interval_s, retention_days, consent_version, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (schedule_id) DO UPDATE SET level = EXCLUDED.level, snapshot_interval_s = EXCLUDED.snapshot_interval_s,
                    retention_days = EXCLUDED.retention_days, consent_version = EXCLUDED.consent_version, updated_by = EXCLUDED.updated_by,
                    updated_at = now(), version = proctoring_setting.version + 1""")
                .params(TenantContext.requireTenantId(), s.id(), level, interval, retention, CONSENT_VERSION, me).update();
        audit.recordSuccess("proctoring.configured", "exam_schedule", s.id().toString(), Map.of("level", level, "snapshotIntervalSeconds", String.valueOf(interval),
                "retentionDays", String.valueOf(retention)));
        return settingOf(s);
    }

    record Level(String level, int interval, int retention) {}

    private Level levelOf(UUID scheduleId) {
        return jdbc.sql("SELECT level, snapshot_interval_s, retention_days FROM proctoring_setting WHERE schedule_id = ?").param(scheduleId)
                .query((rs, n) -> new Level(rs.getString(1), rs.getInt(2), rs.getInt(3))).optional().orElse(new Level("NONE", 120, 30));
    }

    // ================================================================================================ candidate

    @Transactional(readOnly = true)
    public CandidateView candidateView(CandidateAttempt a) {
        Level l = levelOf(a.scheduleId());
        String consent = jdbc.sql("SELECT decision FROM proctoring_consent WHERE attempt_id = ?").param(a.attemptId()).query(String.class).optional().orElse(null);
        String text = "NONE".equals(l.level()) ? null : consentText(l.level());
        return new CandidateView(l.level(), l.interval(), l.retention(), CONSENT_VERSION, true, text, text == null ? null : sha256(text.getBytes(StandardCharsets.UTF_8)),
                consent);
    }

    /** Records the candidate's decision on exactly the text shown (its hash must match the current text). Once only. */
    @Transactional
    public CandidateView consent(CandidateAttempt a, boolean given, String textHash) {
        Level l = levelOf(a.scheduleId());
        if ("NONE".equals(l.level())) throw ApiException.conflict("not_proctored", "This examination is not proctored.");
        String expected = sha256(consentText(l.level()).getBytes(StandardCharsets.UTF_8));
        if (!expected.equals(textHash)) throw ApiException.conflict("consent_text_changed", "The notice has changed. Reload it and read it again.");
        boolean exists = jdbc.sql("SELECT EXISTS (SELECT 1 FROM proctoring_consent WHERE attempt_id = ?)").param(a.attemptId()).query(Boolean.class).single();
        if (exists) throw ApiException.conflict("consent_recorded", "Your decision is already recorded.");
        UUID id = Ids.newId();
        jdbc.sql("""
                INSERT INTO proctoring_consent (id, tenant_id, attempt_id, schedule_id, enrolment_id, level, consent_version, text_hash, decision)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""").params(id, TenantContext.requireTenantId(), a.attemptId(), a.scheduleId(), a.enrolmentId(), l.level(),
                CONSENT_VERSION, expected, given ? "GIVEN" : "DECLINED").update();
        audit.recordSuccess(given ? "proctoring.consent_given" : "proctoring.consent_declined", "exam_attempt", a.attemptId().toString(),
                Map.of("schedule", a.scheduleId().toString(), "level", l.level(), "consentVersion", CONSENT_VERSION));
        return candidateView(a);
    }

    private void requireCapture(CandidateAttempt a, Level l) {
        if ("NONE".equals(l.level())) throw ApiException.conflict("not_proctored", "This examination is not proctored.");
        if (!"IN_PROGRESS".equals(a.status())) throw ApiException.conflict("attempt_not_running", "This examination has ended.");
        boolean given = jdbc.sql("SELECT EXISTS (SELECT 1 FROM proctoring_consent WHERE attempt_id = ? AND decision = 'GIVEN')").param(a.attemptId())
                .query(Boolean.class).single();
        if (!given) throw ApiException.conflict("consent_required", "Nothing is recorded without your consent.");
    }

    /** Stores a batch of browser signals and re-evaluates the rules; returns the number stored. */
    @Transactional
    public int signals(CandidateAttempt a, List<SignalInput> batch) {
        Level l = levelOf(a.scheduleId());
        requireCapture(a, l);
        if (batch == null || batch.isEmpty()) return 0;
        if (batch.size() > MAX_BATCH) throw ApiException.badRequest("batch_too_large", "At most " + MAX_BATCH + " signals at a time.");
        int stored = jdbc.sql("SELECT count(*) FROM proctoring_signal WHERE attempt_id = ?").param(a.attemptId()).query(Integer.class).single();
        if (stored + batch.size() > MAX_SIGNALS_PER_ATTEMPT) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_signals", "Too many signals for this examination.");
        Instant now = Instant.now();
        UUID tenant = TenantContext.requireTenantId();
        for (SignalInput s : batch) {
            if (s == null || s.kind() == null) throw ApiException.badRequest("invalid_signal", "Unknown signal.");
            String kind = s.kind().strip().toUpperCase();
            if (!SIGNAL_KINDS.contains(kind)) {
                throw ApiException.badRequest("invalid_signal", "Unknown signal.");
            }
            // the client's clock is only used within bounds: never in the future, never before a day ago
            Instant at = s.at() == null || s.at().isAfter(now) || s.at().isBefore(now.minus(Duration.ofDays(1))) ? now : s.at();
            Integer duration = s.durationMs() == null ? null : Math.max(0, Math.min(86_400_000, s.durationMs()));
            jdbc.sql("INSERT INTO proctoring_signal (id, tenant_id, attempt_id, kind, client_at, duration_ms) VALUES (?, ?, ?, ?, ?, ?)")
                    .params(Ids.newId(), tenant, a.attemptId(), kind, Timestamp.from(at), duration).update();
        }
        evaluate(a, l);
        return batch.size();
    }

    /** Raises or updates flags from all of the attempt's signals (rules are deterministic, so re-evaluation is idempotent). */
    void evaluate(CandidateAttempt a, Level l) {
        List<ProctoringRules.Signal> all = jdbc.sql("SELECT kind, client_at, duration_ms FROM proctoring_signal WHERE attempt_id = ? ORDER BY client_at").param(a.attemptId())
                .query((rs, n) -> new ProctoringRules.Signal(rs.getString(1), rs.getTimestamp(2).toInstant(), (Integer) rs.getObject(3))).list();
        for (ProctoringRules.Hit h : ProctoringRules.evaluate(all, "SNAPSHOTS".equals(l.level()))) {
            UUID id = Ids.newId();
            UUID existing = jdbc.sql("SELECT id FROM proctoring_flag WHERE attempt_id = ? AND rule_code = ?").params(a.attemptId(), h.rule()).query(UUID.class).optional().orElse(null);
            if (existing == null) {
                jdbc.sql("""
                        INSERT INTO proctoring_flag (id, tenant_id, attempt_id, schedule_id, enrolment_id, rule_code, rule_version, severity, first_at, last_at, signal_count)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""").params(id, TenantContext.requireTenantId(), a.attemptId(), a.scheduleId(), a.enrolmentId(), h.rule(),
                        ProctoringRules.VERSION, h.severity().name(), Timestamp.from(h.firstAt()), Timestamp.from(h.lastAt()), h.count()).update();
                holdEvidence(a.attemptId(), true);
                audit.recordSuccess("proctoring.flag_raised", "proctoring_flag", id.toString(), Map.of("schedule", a.scheduleId().toString(), "rule", h.rule(),
                        "severity", h.severity().name()));
                outbox.append("proctoring_flag", id.toString(), "proctoring.flag.raised", Map.of("flagId", id.toString(), "scheduleId", a.scheduleId().toString(),
                        "rule", h.rule(), "severity", h.severity().name()));
            } else {
                jdbc.sql("UPDATE proctoring_flag SET last_at = greatest(last_at, ?), signal_count = greatest(signal_count, ?) WHERE id = ?")
                        .params(Timestamp.from(h.lastAt()), h.count(), existing).update();
            }
        }
    }

    /** Stores one webcam snapshot as evidence (level SNAPSHOTS, consent given): JPEG only, at most 200 KB, malware-scanned. */
    @Transactional
    public SnapshotView snapshot(CandidateAttempt a, byte[] content, Instant takenAt) {
        Level l = levelOf(a.scheduleId());
        requireCapture(a, l);
        if (!"SNAPSHOTS".equals(l.level())) throw ApiException.conflict("snapshots_off", "This examination takes no webcam snapshots.");
        if (content == null || content.length == 0) throw ApiException.badRequest("empty_file", "The snapshot is empty.");
        if (content.length > MAX_SNAPSHOT_BYTES) throw ApiException.badRequest("snapshot_too_large", "Snapshots may be at most 200 KB.");
        if (content.length < 3 || (content[0] & 0xFF) != 0xFF || (content[1] & 0xFF) != 0xD8 || (content[2] & 0xFF) != 0xFF) {
            throw ApiException.badRequest("not_jpeg", "Snapshots are JPEG images.");
        }
        int count = jdbc.sql("SELECT count(*) FROM proctoring_snapshot WHERE attempt_id = ?").param(a.attemptId()).query(Integer.class).single();
        if (count >= MAX_SNAPSHOTS_PER_ATTEMPT) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_snapshots", "Too many snapshots for this examination.");
        MalwareScanner sc = scanner.getIfAvailable();
        boolean devOrTest = deployment.environment() == DeploymentProperties.Environment.DEVELOPMENT || deployment.environment() == DeploymentProperties.Environment.TEST;
        if (sc == null && !devOrTest) {
            audit.recordDenied("proctoring.snapshot_refused", Map.of("attempt", a.attemptId().toString(), "reason", "no_malware_scanner"));
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "scanning_unavailable", "Snapshots are unavailable: no malware scanner is configured.");
        }
        String status;
        if (sc == null) {
            status = "NOT_SCANNED_DEV";
        } else {
            MalwareScanner.Result r;
            try {
                r = sc.scan(content);
            } catch (RuntimeException e) {
                r = new MalwareScanner.Result(MalwareScanner.Verdict.ERROR, e.getClass().getSimpleName());
            }
            status = r.verdict().name();
        }
        UUID tenant = TenantContext.requireTenantId();
        UUID id = Ids.newId();
        Instant now = Instant.now();
        Instant taken = takenAt == null || takenAt.isAfter(now) || takenAt.isBefore(now.minus(Duration.ofHours(12))) ? now : takenAt;
        String key = "t/" + tenant + "/proctoring/" + a.attemptId() + "/" + id;
        storage.put(key, new ByteArrayInputStream(content), content.length, "image/jpeg");
        boolean held = jdbc.sql("SELECT EXISTS (SELECT 1 FROM proctoring_flag WHERE attempt_id = ?)").param(a.attemptId()).query(Boolean.class).single();
        jdbc.sql("""
                INSERT INTO proctoring_snapshot (id, tenant_id, attempt_id, object_key, sha256, bytes, scan_status, taken_at, expires_at, hold)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""").params(id, tenant, a.attemptId(), key, sha256(content), content.length, status, Timestamp.from(taken),
                Timestamp.from(taken.plus(Duration.ofDays(l.retention()))), held).update();
        audit.recordSuccess("proctoring.snapshot_stored", "proctoring_snapshot", id.toString(), Map.of("attempt", a.attemptId().toString(), "scan", status,
                "bytes", String.valueOf(content.length)));
        return new SnapshotView(id, taken, status, available(status), taken.plus(Duration.ofDays(l.retention())));
    }

    private void holdEvidence(UUID attemptId, boolean hold) {
        jdbc.sql("UPDATE proctoring_snapshot SET hold = ? WHERE attempt_id = ? AND deleted_at IS NULL AND hold <> ?").params(hold, attemptId, hold).update();
    }

    private static boolean available(String scan) {
        return "CLEAN".equals(scan) || "NOT_SCANNED_DEV".equals(scan);
    }

    // ================================================================================================ staff: review

    private static final String FLAG_SELECT = """
            SELECT f.id, f.attempt_id, f.enrolment_id, e.display_name, f.rule_code, f.severity, f.first_at, f.last_at, f.signal_count, f.status,
                   d.decision, d.reason, d.warning_text, d.incident_id, d.approval_id, u.display_name, d.decided_at
            FROM proctoring_flag f JOIN exam_enrolment e ON e.id = f.enrolment_id
            LEFT JOIN proctoring_flag_decision d ON d.flag_id = f.id LEFT JOIN user_account u ON u.id = d.decided_by""";

    private static Flag flagRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Decision d = rs.getString(11) == null ? null : new Decision(rs.getString(11), rs.getString(12), rs.getString(13), rs.getObject(14, UUID.class),
                rs.getObject(15, UUID.class), rs.getString(16), rs.getTimestamp(17).toInstant());
        String rule = rs.getString(5);
        return new Flag(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getString(4), rule,
                ProctoringRules.RULES.stream().filter(r -> r.code().equals(rule)).map(ProctoringRules.Rule::description).findFirst().orElse(rule), rs.getString(6),
                rs.getTimestamp(7).toInstant(), rs.getTimestamp(8).toInstant(), rs.getInt(9), rs.getString(10), d);
    }

    /** The review queue of a schedule: open flags first (high severity first), then decided ones. */
    @Transactional(readOnly = true)
    public List<Flag> flags(UUID scheduleId, String status) {
        schedule(scheduleId, REVIEW);
        String st = status == null || status.isBlank() ? null : status.strip().toUpperCase();
        return jdbc.sql(FLAG_SELECT + """
                 WHERE f.schedule_id = ? AND (CAST(? AS text) IS NULL OR f.status = CAST(? AS text))
                ORDER BY f.status = 'DECIDED', CASE f.severity WHEN 'HIGH' THEN 0 WHEN 'MEDIUM' THEN 1 ELSE 2 END, f.first_at""")
                .params(scheduleId, st, st).query((rs, n) -> flagRow(rs)).list();
    }

    record FlagRef(UUID id, UUID scheduleId, UUID attemptId, UUID enrolmentId, String status, String rule) {}

    private FlagRef flagRef(UUID flagId, boolean lock) {
        return jdbc.sql("SELECT id, schedule_id, attempt_id, enrolment_id, status, rule_code FROM proctoring_flag WHERE id = ?" + (lock ? " FOR UPDATE" : "")).param(flagId)
                .query((rs, n) -> new FlagRef(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getObject(4, UUID.class),
                        rs.getString(5), rs.getString(6))).optional().orElseThrow(() -> ApiException.notFound("Flag"));
    }

    @Transactional(readOnly = true)
    public FlagDetail flag(UUID flagId) {
        FlagRef f = flagRef(flagId, false);
        Sched s = schedule(f.scheduleId(), REVIEW);
        Flag flag = jdbc.sql(FLAG_SELECT + " WHERE f.id = ?").param(flagId).query((rs, n) -> flagRow(rs)).single();
        List<SignalView> signals = jdbc.sql("SELECT kind, client_at, duration_ms FROM proctoring_signal WHERE attempt_id = ? ORDER BY client_at LIMIT 500").param(f.attemptId())
                .query((rs, n) -> new SignalView(rs.getString(1), rs.getTimestamp(2).toInstant(), (Integer) rs.getObject(3))).list();
        boolean evidence = authz.hasInOrgUnit(EVIDENCE, s.orgUnitId());
        List<SnapshotView> snaps = jdbc.sql("SELECT id, taken_at, scan_status, deleted_at, expires_at FROM proctoring_snapshot WHERE attempt_id = ? ORDER BY taken_at")
                .param(f.attemptId()).query((rs, n) -> new SnapshotView(rs.getObject(1, UUID.class), rs.getTimestamp(2).toInstant(), rs.getString(3),
                        rs.getTimestamp(4) == null && available(rs.getString(3)), rs.getTimestamp(5).toInstant())).list();
        return new FlagDetail(flag, signals, snaps, evidence);
    }

    /**
     * A person's decision on a flag (once, with a reason). WARN shows the text to the candidate with the next heartbeat;
     * INCIDENT records a permanent candidate incident; TERMINATION_REQUESTED asks for the four-eyes termination (someone
     * else with exam.attempt.terminate decides). DISMISS and WARN release the evidence hold once no flag is open.
     */
    @Transactional
    public FlagDetail decide(UUID flagId, DecisionInput in) {
        FlagRef f = flagRef(flagId, true);
        Sched s = schedule(f.scheduleId(), REVIEW);
        if (!"OPEN".equals(f.status())) throw ApiException.conflict("flag_decided", "This flag has already been decided.");
        String decision = in.decision() == null ? "" : in.decision().strip().toUpperCase();
        if (!DECISIONS.contains(decision)) throw ApiException.badRequest("invalid_decision", "Decision: DISMISS, WARN, INCIDENT or TERMINATION_REQUESTED.");
        String reason = in.reason() == null ? "" : in.reason().strip();
        if (reason.isEmpty() || reason.length() > 1000) throw ApiException.badRequest("reason_required", "Give the reason for the decision (at most 1,000 characters).");
        String warning = null;
        if ("WARN".equals(decision)) {
            warning = in.warningText() == null ? "" : in.warningText().strip();
            if (warning.isEmpty() || warning.length() > 300) throw ApiException.badRequest("warning_required", "Write the warning the candidate sees (at most 300 characters).");
        }
        UUID incident = null;
        UUID approval = null;
        String note = "Proctoring flag " + ProctoringRules.rule(f.rule()).description().toLowerCase() + ". " + reason;
        if ("INCIDENT".equals(decision)) incident = incidents.record(f.scheduleId(), f.enrolmentId(), "SUSPECTED_MALPRACTICE", truncate(note, 1000));
        if ("TERMINATION_REQUESTED".equals(decision)) approval = incidents.requestTermination(f.scheduleId(), f.enrolmentId(), truncate(note, 1000));
        UUID id = Ids.newId();
        jdbc.sql("""
                INSERT INTO proctoring_flag_decision (id, tenant_id, flag_id, decision, reason, warning_text, incident_id, approval_id, decided_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""").params(id, TenantContext.requireTenantId(), flagId, decision, reason, warning, incident, approval,
                CurrentUser.require().userId()).update();
        jdbc.sql("UPDATE proctoring_flag SET status = 'DECIDED' WHERE id = ?").param(flagId).update();
        boolean open = jdbc.sql("SELECT EXISTS (SELECT 1 FROM proctoring_flag WHERE attempt_id = ? AND status = 'OPEN')").param(f.attemptId()).query(Boolean.class).single();
        boolean escalated = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM proctoring_flag_decision d JOIN proctoring_flag x ON x.id = d.flag_id
                               WHERE x.attempt_id = ? AND d.decision IN ('INCIDENT', 'TERMINATION_REQUESTED'))""").param(f.attemptId()).query(Boolean.class).single();
        if (!open && !escalated) holdEvidence(f.attemptId(), false);
        Map<String, String> d = new LinkedHashMap<>();
        d.put("schedule", s.id().toString());
        d.put("rule", f.rule());
        d.put("decision", decision);
        if (incident != null) d.put("incident", incident.toString());
        if (approval != null) d.put("approval", approval.toString());
        audit.recordSuccess("proctoring.flag_decided", "proctoring_flag", flagId.toString(), d);
        return flag(flagId);
    }

    /** A snapshot image for a holder of proctoring.evidence.view; every view and refusal is audited. */
    @Transactional
    public byte[] evidence(UUID snapshotId) {
        record Snap(UUID attemptId, String key, String scan, Instant deleted) {}
        Snap sn = jdbc.sql("SELECT attempt_id, object_key, scan_status, deleted_at FROM proctoring_snapshot WHERE id = ?").param(snapshotId)
                .query((rs, n) -> new Snap(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toInstant()))
                .optional().orElseThrow(() -> ApiException.notFound("Snapshot"));
        UUID scheduleId = jdbc.sql("SELECT schedule_id FROM proctoring_consent WHERE attempt_id = ?").param(sn.attemptId()).query(UUID.class).single();
        Schedules.Ref r = schedules.find(scheduleId).orElseThrow(() -> ApiException.notFound("Snapshot"));
        if (!authz.hasInOrgUnit(EVIDENCE, r.orgUnitId())) {
            audit.recordDenied("proctoring.evidence_view_denied", Map.of("snapshot", snapshotId.toString()));
            if (authz.hasInOrgUnit(REVIEW, r.orgUnitId())) throw new AccessDeniedException("Missing permission " + EVIDENCE);
            throw ApiException.notFound("Snapshot");
        }
        if (sn.deleted() != null) throw new ApiException(HttpStatus.GONE, "evidence_deleted", "This snapshot was deleted at the end of its retention period.");
        if (!available(sn.scan())) throw ApiException.conflict("evidence_quarantined", "This snapshot did not pass the malware scan and is not shown.");
        byte[] bytes = storage.get(sn.key()).orElseThrow(() -> ApiException.notFound("Snapshot"));
        audit.recordSuccess("proctoring.evidence_viewed", "proctoring_snapshot", snapshotId.toString(), Map.of("schedule", scheduleId.toString()));
        return bytes;
    }

    // ================================================================================================ summary, notices, retention

    @Transactional(readOnly = true)
    public Summary summary(UUID scheduleId) {
        schedule(scheduleId, REVIEW, CONFIGURE);
        Level l = levelOf(scheduleId);
        return jdbc.sql("""
                SELECT count(*) FILTER (WHERE status = 'OPEN'), count(*) FILTER (WHERE status = 'OPEN' AND severity = 'HIGH'), count(*) FILTER (WHERE status = 'DECIDED'),
                       (SELECT count(*) FROM proctoring_consent WHERE schedule_id = ? AND decision = 'GIVEN'),
                       (SELECT count(*) FROM proctoring_consent WHERE schedule_id = ? AND decision = 'DECLINED')
                FROM proctoring_flag WHERE schedule_id = ?""").params(scheduleId, scheduleId, scheduleId)
                .query((rs, n) -> new Summary(l.level(), rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5))).single();
    }

    /** Warnings a reviewer addressed to the candidate (exams.CandidateNotices): shown with the attempt heartbeat. */
    @Override
    public List<Notice> forAttempt(UUID attemptId) {
        return jdbc.sql("""
                SELECT d.id, d.warning_text, d.decided_at FROM proctoring_flag_decision d JOIN proctoring_flag f ON f.id = d.flag_id
                WHERE f.attempt_id = ? AND d.decision = 'WARN' ORDER BY d.decided_at""").param(attemptId)
                .query((rs, n) -> new Notice(rs.getObject(1, UUID.class), rs.getString(2), rs.getTimestamp(3).toInstant())).list();
    }

    /** Deletes expired, unheld snapshot images of the current tenant; the record stays with deleted_at set. */
    @Transactional
    public int deleteExpired(Instant now) {
        List<Map.Entry<UUID, String>> due = new ArrayList<>();
        jdbc.sql("SELECT id, object_key FROM proctoring_snapshot WHERE deleted_at IS NULL AND NOT hold AND expires_at <= ? ORDER BY expires_at LIMIT 500")
                .param(Timestamp.from(now)).query((rs, n) -> due.add(Map.entry(rs.getObject(1, UUID.class), rs.getString(2)))).list();
        for (Map.Entry<UUID, String> e : due) {
            storage.delete(e.getValue());
            jdbc.sql("UPDATE proctoring_snapshot SET deleted_at = ? WHERE id = ?").params(Timestamp.from(now), e.getKey()).update();
        }
        if (!due.isEmpty()) audit.recordSuccess("proctoring.evidence_deleted", "proctoring_snapshot", "retention", Map.of("count", String.valueOf(due.size()), "reason", "retention"));
        return due.size();
    }

    static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }
}
