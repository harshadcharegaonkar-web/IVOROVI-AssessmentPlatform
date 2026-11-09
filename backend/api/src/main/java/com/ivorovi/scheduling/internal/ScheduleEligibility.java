package com.ivorovi.scheduling.internal;

import com.ivorovi.exams.ExamEligibility;
import com.ivorovi.exams.ExamSessions;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Scheduling's answer to delivery: which scheduled examinations a candidate may take and on which terms. An enrolment
 * matches by its linked candidate account, or (not yet linked) by the keyed email index of the candidate's account on the
 * schedule's portal; it is linked at that moment and never re-linked. Cancelled schedules and withdrawn enrolments never
 * match. No personal data is read.
 */
@Component
class ScheduleEligibility implements ExamEligibility {

    private final JdbcClient jdbc;
    private final ExamSessions sessions;

    ScheduleEligibility(JdbcClient jdbc, ExamSessions sessions) {
        this.jdbc = jdbc;
        this.sessions = sessions;
    }

    private static final String SELECT = """
            SELECT e.id, s.id, s.title, s.version_id, s.session_id, s.opens_at, s.closes_at, s.late_entry_until, s.duration_minutes, e.extra_minutes, e.candidate_id
            FROM exam_enrolment e JOIN exam_schedule s ON s.id = e.schedule_id
            WHERE e.status = 'ACTIVE' AND s.status = 'SCHEDULED'
              AND (e.candidate_id = ? OR (e.candidate_id IS NULL AND e.email_index = ? AND s.portal_id = ?))""";

    private record Match(UUID enrolmentId, Eligibility eligibility, UUID linkedTo) {}

    private List<Match> matches(UUID candidate, String extra, Object... more) {
        Optional<ExamSessions.CandidateIdentity> me = sessions.candidate(candidate);
        if (me.isEmpty()) return List.of();
        Object[] params = new Object[3 + more.length];
        params[0] = candidate;
        params[1] = me.get().emailIndex() == null ? "-" : me.get().emailIndex();
        params[2] = me.get().portalId();
        System.arraycopy(more, 0, params, 3, more.length);
        List<Match> found = jdbc.sql(SELECT + extra).params(params)
                .query((rs, n) -> new Match(rs.getObject(1, UUID.class), new Eligibility(rs.getObject(2, UUID.class), rs.getString(3), rs.getObject(4, UUID.class),
                        rs.getObject(5, UUID.class), rs.getTimestamp(6).toInstant(), rs.getTimestamp(7).toInstant(), ts(rs.getTimestamp(8)), rs.getInt(9), rs.getInt(10)),
                        rs.getObject(11, UUID.class))).list();
        for (Match m : found) {
            if (m.linkedTo() == null) {
                jdbc.sql("UPDATE exam_enrolment SET candidate_id = ?, version = version + 1 WHERE id = ? AND candidate_id IS NULL").params(candidate, m.enrolmentId()).update();
            }
        }
        return found;
    }

    @Override
    public Optional<Eligibility> forCandidate(UUID scheduleId, UUID candidateAccountId) {
        return matches(candidateAccountId, " AND s.id = ?", scheduleId).stream().map(Match::eligibility).findFirst();
    }

    @Override
    public List<Eligibility> upcoming(UUID candidateAccountId) {
        return matches(candidateAccountId, " AND s.closes_at > now() - interval '1 day' ORDER BY s.opens_at LIMIT 50").stream().map(Match::eligibility).toList();
    }

    private static java.time.Instant ts(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
