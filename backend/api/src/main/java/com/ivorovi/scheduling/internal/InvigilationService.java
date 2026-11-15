package com.ivorovi.scheduling.internal;

import com.ivorovi.exams.ExamSessions;
import com.ivorovi.platform.audit.AuditLog;
import com.ivorovi.platform.crypto.FieldCipher;
import com.ivorovi.platform.ids.Ids;
import com.ivorovi.platform.security.Authz;
import com.ivorovi.platform.security.CurrentUser;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.platform.web.ApiException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Day 4 P3: invigilation of a scheduled examination. Invigilators (exam.invigilate: the EXAM_PROCTOR role, exam controllers
 * and administrators of the institution) see every enrolled candidate's state live and record incidents. The view shows
 * names and masked e-mails, never answers, keys or scores. Incidents are permanent; their notes are personal data and are
 * never written to audit details or logs (identifiers and category only).
 */
@Service
public class InvigilationService {

    public static final String INVIGILATE = "exam.invigilate";
    static final Duration ONLINE = Duration.ofSeconds(60);
    static final Set<String> CATEGORIES = Set.of("IDENTITY", "SUSPECTED_MALPRACTICE", "TECHNICAL", "DISRUPTION", "LATE_ARRIVAL", "OTHER");

    @Schema(name = "InvigilationCandidate")
    public record Candidate(UUID enrolmentId, String displayName, String email, String state, boolean online, Instant checkedInAt, Instant lastSeenAt,
                            Instant startedAt, Instant deadlineAt, Instant submittedAt, long secondsLeft, int extraMinutes, int questions, int answered,
                            Instant lastSavedAt, int incidents, int extendedMinutes, int interruptions, long interruptedSeconds, boolean terminationPending,
                            boolean extensionPending) {}

    @Schema(name = "InvigilationMonitor")
    public record Monitor(UUID scheduleId, String title, Instant opensAt, Instant closesAt, Instant serverTime, Map<String, Integer> counts,
                          List<Candidate> candidates, boolean canRecordIncidents, boolean canExtend, boolean canRequestTermination,
                          boolean extraTimeSecondPerson) {}

    @Schema(name = "CandidateIncident")
    public record Incident(UUID id, UUID enrolmentId, String candidateName, String category, String note, UUID correctsId, String recordedByName,
                           Instant recordedAt) {}

    public record IncidentInput(UUID enrolmentId, String category, String note, UUID correctsId) {}

    private record Sched(UUID id, UUID orgUnitId, UUID sessionId, String title, Instant opensAt, Instant closesAt, boolean secondPerson) {}

    private record Enr(UUID id, UUID candidateId, String name, String email) {}

    private final JdbcClient jdbc;
    private final Authz authz;
    private final AuditLog audit;
    private final FieldCipher cipher;
    private final ExamSessions sessions;

    InvigilationService(JdbcClient jdbc, Authz authz, AuditLog audit, FieldCipher cipher, ExamSessions sessions) {
        this.jdbc = jdbc;
        this.authz = authz;
        this.audit = audit;
        this.cipher = cipher;
        this.sessions = sessions;
    }

    /** The schedule, if the caller may invigilate it (404 otherwise, as for any schedule they cannot see). */
    private Sched schedule(UUID id) {
        Sched s = jdbc.sql("SELECT id, org_unit_id, session_id, title, opens_at, closes_at, extra_time_second_person FROM exam_schedule WHERE id = ?").param(id)
                .query((rs, n) -> new Sched(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getString(4),
                        rs.getTimestamp(5).toInstant(), rs.getTimestamp(6).toInstant(), rs.getBoolean(7))).optional().orElseThrow(() -> ApiException.notFound("Schedule"));
        if (!authz.hasInOrgUnit(INVIGILATE, s.orgUnitId())) {
            if (authz.hasInOrgUnit(ScheduleService.READ, s.orgUnitId())) throw new AccessDeniedException("Missing permission " + INVIGILATE);
            throw ApiException.notFound("Schedule");
        }
        return s;
    }

    static String mask(String email) {
        if (email == null) return null;
        int at = email.indexOf('@');
        if (at <= 0) return "***";
        return email.charAt(0) + "***" + email.substring(at);
    }

    private List<Enr> enrolments(UUID scheduleId) {
        UUID tenant = TenantContext.requireTenantId();
        return jdbc.sql("SELECT id, candidate_id, display_name, email_enc FROM exam_enrolment WHERE schedule_id = ? AND status = 'ACTIVE' ORDER BY display_name, created_at")
                .param(scheduleId).query((rs, n) -> new Enr(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        mask(cipher.decrypt(rs.getString(4), ScheduleService.EMAIL_FIELD, tenant)))).list();
    }

    /**
     * Candidate state, in the order an examination moves: NOT_LINKED (invited, no account yet), NOT_CHECKED_IN, CHECKED_IN,
     * IN_PROGRESS, SUBMITTED or AUTO_SUBMITTED.
     */
    static String state(UUID candidateId, ExamSessions.Progress p) {
        if (candidateId == null) return "NOT_LINKED";
        if (p == null) return "NOT_CHECKED_IN";
        if (p.attemptStatus() != null) return p.attemptStatus();
        return p.checkedInAt() != null ? "CHECKED_IN" : "NOT_CHECKED_IN";
    }

    @Transactional(readOnly = true)
    public Monitor monitor(UUID scheduleId) {
        Sched s = schedule(scheduleId);
        List<Enr> enr = enrolments(scheduleId);
        List<UUID> linked = enr.stream().map(Enr::candidateId).filter(java.util.Objects::nonNull).toList();
        Map<UUID, ExamSessions.Progress> progress = sessions.progress(scheduleId, s.sessionId(), s.orgUnitId(), linked).stream()
                .collect(Collectors.toMap(ExamSessions.Progress::candidateId, Function.identity()));
        Map<UUID, Integer> incidents = new java.util.HashMap<>();
        jdbc.sql("SELECT enrolment_id, count(*) FROM exam_candidate_incident WHERE schedule_id = ? GROUP BY enrolment_id").param(scheduleId)
                .query((rs, n) -> incidents.put(rs.getObject(1, UUID.class), rs.getInt(2))).list();
        Instant now = Instant.now();
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (String k : List.of("NOT_LINKED", "NOT_CHECKED_IN", "CHECKED_IN", "IN_PROGRESS", "SUBMITTED", "AUTO_SUBMITTED")) counts.put(k, 0);
        int online = 0;
        List<Candidate> out = new ArrayList<>();
        for (Enr e : enr) {
            ExamSessions.Progress p = e.candidateId() == null ? null : progress.get(e.candidateId());
            String state = state(e.candidateId(), p);
            boolean on = p != null && p.lastSeenAt() != null && p.lastSeenAt().isAfter(now.minus(ONLINE));
            long left = p != null && "IN_PROGRESS".equals(p.attemptStatus()) && p.deadlineAt() != null ? Math.max(0, Duration.between(now, p.deadlineAt()).toSeconds()) : 0;
            counts.merge(state, 1, Integer::sum);
            if (on) online++;
            out.add(new Candidate(e.id(), e.name(), e.email(), state, on, p == null ? null : p.checkedInAt(), p == null ? null : p.lastSeenAt(),
                    p == null ? null : p.startedAt(), p == null ? null : p.deadlineAt(), p == null ? null : p.submittedAt(), left,
                    p == null ? 0 : p.extraMinutes(), p == null ? 0 : p.questions(), p == null ? 0 : p.answered(), p == null ? null : p.lastSavedAt(),
                    incidents.getOrDefault(e.id(), 0), p == null ? 0 : p.extendedMinutes(), p == null ? 0 : p.interruptions(),
                    p == null ? 0 : p.interruptedSeconds(), p != null && p.terminationPending(), p != null && p.extensionPending()));
        }
        counts.put("ONLINE", online);
        return new Monitor(s.id(), s.title(), s.opensAt(), s.closesAt(), now, counts, out, true,
                authz.hasInOrgUnit(com.ivorovi.exams.ExamSessions.EXTEND, s.orgUnitId()), true, s.secondPerson());
    }

    private record Enrolled(UUID candidateId, String name) {}

    private Enrolled enrolled(UUID scheduleId, UUID enrolmentId) {
        Enrolled e = jdbc.sql("SELECT candidate_id, display_name FROM exam_enrolment WHERE id = ? AND schedule_id = ?").params(enrolmentId, scheduleId)
                .query((rs, n) -> new Enrolled(rs.getObject(1, UUID.class), rs.getString(2))).optional().orElseThrow(() -> ApiException.notFound("Candidate"));
        if (e.candidateId() == null) throw ApiException.conflict("attempt_not_running", "This candidate has not started the examination.");
        return e;
    }

    /**
     * Extra minutes for one candidate's running examination (R-2): designated holders of exam.attempt.extend only; on an
     * examination that requires a second person the grant waits for another holder's approval.
     */
    @Transactional
    public com.ivorovi.exams.ExamSessions.AttemptExtension extend(UUID scheduleId, UUID enrolmentId, int minutes, String reason) {
        Sched s = schedule(scheduleId);
        Enrolled e = enrolled(scheduleId, enrolmentId);
        return sessions.extend(scheduleId, s.orgUnitId(), s.closesAt(), s.secondPerson(), e.candidateId(), e.name(), minutes, reason);
    }

    /** Every extra-time grant of one candidate (who, when, minutes, reason, previous and new expiry, approver). */
    @Transactional(readOnly = true)
    public List<com.ivorovi.exams.ExamSessions.ExtensionRecord> extensions(UUID scheduleId, UUID enrolmentId) {
        Sched s = schedule(scheduleId);
        UUID candidate = jdbc.sql("SELECT candidate_id FROM exam_enrolment WHERE id = ? AND schedule_id = ?").params(enrolmentId, scheduleId)
                .query(UUID.class).optional().orElseThrow(() -> ApiException.notFound("Candidate"));
        if (candidate == null) return List.of();
        return sessions.extensions(scheduleId, s.orgUnitId(), candidate);
    }

    /** An invigilator asks to end one candidate's examination; another person with exam.attempt.terminate decides. */
    @Transactional
    public UUID requestTermination(UUID scheduleId, UUID enrolmentId, String reason) {
        Sched s = schedule(scheduleId);
        Enrolled e = enrolled(scheduleId, enrolmentId);
        return sessions.requestTermination(scheduleId, s.orgUnitId(), e.candidateId(), e.name(), reason);
    }

    @Transactional(readOnly = true)
    public List<Incident> incidents(UUID scheduleId) {
        schedule(scheduleId);
        return jdbc.sql("""
                SELECT i.id, i.enrolment_id, e.display_name, i.category, i.note, i.corrects_id, u.display_name, i.recorded_at
                FROM exam_candidate_incident i JOIN exam_enrolment e ON e.id = i.enrolment_id LEFT JOIN user_account u ON u.id = i.recorded_by
                WHERE i.schedule_id = ? ORDER BY i.recorded_at DESC, i.id""").param(scheduleId)
                .query((rs, n) -> new Incident(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getObject(6, UUID.class), rs.getString(7), rs.getTimestamp(8).toInstant())).list();
    }

    @Transactional
    public Incident record(UUID scheduleId, IncidentInput in) {
        Sched s = schedule(scheduleId);
        if (in == null || in.enrolmentId() == null) throw ApiException.badRequest("enrolment_required", "Choose the candidate.");
        String category = in.category() == null ? "" : in.category().strip().toUpperCase(java.util.Locale.ROOT);
        if (!CATEGORIES.contains(category)) throw ApiException.badRequest("category_invalid", "Choose a category.");
        String note = in.note() == null ? "" : in.note().strip();
        if (note.isEmpty()) throw ApiException.badRequest("note_required", "Describe what happened.");
        if (note.length() > 1000) throw ApiException.badRequest("note_too_long", "The note may be at most 1,000 characters.");
        boolean enrolled = jdbc.sql("SELECT EXISTS (SELECT 1 FROM exam_enrolment WHERE id = ? AND schedule_id = ?)").params(in.enrolmentId(), scheduleId)
                .query(Boolean.class).single();
        if (!enrolled) throw ApiException.notFound("Candidate");
        if (in.correctsId() != null) {
            boolean same = jdbc.sql("SELECT EXISTS (SELECT 1 FROM exam_candidate_incident WHERE id = ? AND schedule_id = ? AND enrolment_id = ?)")
                    .params(in.correctsId(), scheduleId, in.enrolmentId()).query(Boolean.class).single();
            if (!same) throw ApiException.badRequest("correction_invalid", "A correction must refer to an incident of the same candidate.");
        }
        UUID id = Ids.newId();
        jdbc.sql("""
                INSERT INTO exam_candidate_incident (id, tenant_id, schedule_id, enrolment_id, category, note, corrects_id, recorded_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""").params(id, TenantContext.requireTenantId(), scheduleId, in.enrolmentId(), category, note,
                in.correctsId(), CurrentUser.require().userId()).update();
        audit.recordSuccess("exam.candidate_incident.recorded", "exam_candidate_incident", id.toString(), Map.of("schedule", s.id().toString(),
                "enrolment", in.enrolmentId().toString(), "category", category, "correction", String.valueOf(in.correctsId() != null)));
        return incidents(scheduleId).stream().filter(i -> i.id().equals(id)).findFirst().orElseThrow();
    }
}
