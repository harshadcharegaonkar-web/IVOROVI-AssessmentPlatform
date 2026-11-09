/**
 * Assessments (Increment 2.2 core): assessment definitions built only from approved question versions, review and
 * independent publication, immutable published versions, deterministic stored papers. Uses the question module's
 * public APIs ({@code ApprovedQuestions}, {@code QuestionReferences}); never reads answer keys.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Assessments")
package com.ivorovi.assessments;
