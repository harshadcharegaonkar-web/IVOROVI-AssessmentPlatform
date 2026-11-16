package com.ivorovi.evaluation;

import com.ivorovi.questions.EvaluationMaterial;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Evaluation module API for AI-assisted evaluation (contract C-2 §4). Only the task's own evaluator gets a view, and
 * every view is audited. The view carries no candidate identity and no model answer. Marks are always the evaluator's.
 */
public interface EvaluationTasks {

    record TaskView(UUID taskId, String scriptCode, UUID questionVersionId, BigDecimal marks, List<EvaluationMaterial.Criterion> rubric, String responseText) {}

    enum SuggestionUse { ACCEPTED, CHANGED, IGNORED }

    /** The current user's own task, for assistance; empty for anyone else's task. */
    Optional<TaskView> forAssistance(UUID taskId);

    /** Records once how the evaluator used a suggestion on their own task. */
    void recordSuggestionUse(UUID taskId, UUID suggestionId, SuggestionUse use);
}
