package com.ivorovi.scheduling;

import com.ivorovi.scheduling.internal.ScheduleService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Module API (demo programme S-4) through which the admissions and placement modules run their entrance, aptitude and
 * technical tests on the existing examination engine: they create a schedule over a published assessment, enrol people
 * with an opaque subject reference, invite them and cancel the schedule. Delivery, invigilation, scoring and the R-1
 * result release are unchanged; results come back only through {@code exams.ExamResults}. Every call runs under the
 * caller's {@code exam.schedule.manage} / {@code exam.schedule.read} at the schedule's institution, as on the scheduling
 * screens. A subject reference is set only here, never from a CSV upload, and never changes once recorded.
 */
@Component
public class ExamScheduling {

    public enum SubjectType { STUDENT, APPLICANT, PLACEMENT_CANDIDATE }

    public record NewSchedule(UUID assessmentVersionId, UUID portalId, UUID orgUnitId, String title, Instant opensAt, Instant closesAt,
                              Instant lateEntryUntil, String instructions) {}

    public record Schedule(UUID id, String title, UUID orgUnitId, UUID assessmentVersionId, UUID portalId, String portalUrl, Instant opensAt, Instant closesAt,
                           int durationMinutes, String status, int enrolled) {}

    public record Enrollee(String email, String displayName, SubjectType subjectType, UUID subjectRef) {}

    /** {@code rejected} lists {@code TYPE:ref reason} entries; never e-mail addresses. */
    public record EnrolResult(int created, int linkedExisting, int alreadyEnrolled, List<String> rejected) {}

    public record Enrolment(UUID enrolmentId, SubjectType subjectType, UUID subjectRef, String status, boolean linked, String invitationStatus) {}

    public record Invitations(int sent, int failed) {}

    private final ScheduleService schedules;

    ExamScheduling(ScheduleService schedules) {
        this.schedules = schedules;
    }

    public Schedule create(NewSchedule s) {
        return map(schedules.create(new ScheduleService.ScheduleInput(s.assessmentVersionId(), s.portalId(), s.orgUnitId(), s.title(), s.opensAt(), s.closesAt(),
                s.lateEntryUntil(), s.instructions(), null, null)));
    }

    public Schedule get(UUID scheduleId) {
        return map(schedules.get(scheduleId));
    }

    public EnrolResult enrol(UUID scheduleId, List<Enrollee> people) {
        ScheduleService.SubjectEnrolResult r = schedules.enrolSubjects(scheduleId, people.stream()
                .map(p -> new ScheduleService.SubjectEnrollee(p.email(), p.displayName(), p.subjectType() == null ? null : p.subjectType().name(), p.subjectRef()))
                .toList());
        return new EnrolResult(r.created(), r.linkedExisting(), r.alreadyEnrolled(), r.rejected());
    }

    public List<Enrolment> enrolments(UUID scheduleId) {
        return schedules.subjectEnrolments(scheduleId).stream()
                .map(e -> new Enrolment(e.enrolmentId(), SubjectType.valueOf(e.subjectType()), e.subjectRef(), e.status(), e.linked(), e.invitationStatus()))
                .toList();
    }

    public Invitations invite(UUID scheduleId) {
        ScheduleService.InviteResult r = schedules.invite(scheduleId);
        return new Invitations(r.sent(), r.failed());
    }

    public Schedule cancel(UUID scheduleId, String reason) {
        return map(schedules.cancel(scheduleId, reason));
    }

    private static Schedule map(ScheduleService.Schedule s) {
        return new Schedule(s.id(), s.title(), s.orgUnitId(), s.versionId(), s.portalId(), s.portalUrl(), s.opensAt(), s.closesAt(), s.durationMinutes(), s.status(),
                s.enrolled());
    }
}
