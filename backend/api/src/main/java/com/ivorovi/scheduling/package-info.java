/**
 * Scheduling (M2): examination schedules over PUBLISHED assessment versions, candidate enrolment (bulk upload and
 * email invitation), extra time and examination configuration. Uses only public module APIs:
 * {@code assessments.PublishedAssessments} and {@code exams.ExamSessions}; implements {@code exams.ExamEligibility}
 * for the delivery engine. Invited emails are stored encrypted with a keyed index; no plaintext lookups.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Scheduling")
package com.ivorovi.scheduling;
