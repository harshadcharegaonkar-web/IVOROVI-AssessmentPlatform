/**
 * Admission management and enrollment automation (demo programme add-on E): admission cycles and offerings, the
 * applicant portal (candidate realm), applications and documents (shared quarantine pipeline, ADR-0006), typed
 * eligibility screening whose inputs are shown to staff, entrance tests on the existing examination engine, merit lists
 * and offers under four-eyes approval, and conversion of accepted applicants into students. Uses only public module
 * APIs: students.StudentAdmissions, academics.AcademicCatalogue, scheduling.ExamScheduling, exams.ExamResults,
 * exams.ExamCandidates, approvals, platform.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Admissions")
package com.ivorovi.admissions;
