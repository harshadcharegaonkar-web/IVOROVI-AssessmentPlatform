package com.ivorovi.questions;

import com.ivorovi.platform.web.ApiException;
import com.ivorovi.questions.internal.AnswerKeyStore;
import com.ivorovi.questions.internal.QuestionService;
import com.ivorovi.questions.internal.types.QuestionType;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Module API for manual evaluation (contract C-2): what an evaluator sees of a manually marked question version —
 * statement, word limit, rubric and (audited) model answer. Only for MANUAL types; never another type's key. The caller
 * (the evaluation module) has already authorized the evaluator for a task on this version.
 */
@Component
public class EvaluationMaterial {

    public record Criterion(String id, String title, String description, int weight) {}

    public record Material(UUID versionId, String type, Map<String, Object> content, int maxWords, List<Criterion> rubric) {}

    private final JdbcClient jdbc;
    private final QuestionService questions;
    private final AnswerKeyStore keys;
    private final JsonMapper json;

    EvaluationMaterial(JdbcClient jdbc, QuestionService questions, AnswerKeyStore keys, JsonMapper json) {
        this.jdbc = jdbc;
        this.questions = questions;
        this.keys = keys;
        this.json = json;
    }

    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Material material(UUID versionId) {
        record V(String type, String content, String body, String marking) {}
        V v = jdbc.sql("SELECT type_code, content::text, body::text, marking::text FROM question_version WHERE id = ?").param(versionId)
                .query((rs, n) -> new V(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4))).optional()
                .orElseThrow(() -> ApiException.notFound("Question version"));
        if (questions.type(v.type()).markingMode() != QuestionType.MarkingMode.MANUAL) throw ApiException.conflict("not_manually_marked", "This question is not marked by evaluators.");
        Map<String, Object> body = json.readValue(v.body(), Map.class);
        Map<String, Object> marking = json.readValue(v.marking(), Map.class);
        List<Criterion> rubric = ((List<Map<String, Object>>) marking.getOrDefault("rubric", List.of())).stream()
                .map(c -> new Criterion(String.valueOf(c.get("id")), String.valueOf(c.get("title")), c.get("description") == null ? null : String.valueOf(c.get("description")),
                        ((Number) c.get("weight")).intValue())).toList();
        int maxWords = body.get("maxWords") instanceof Number n ? n.intValue() : 0;
        return new Material(versionId, v.type(), json.readValue(v.content(), Map.class), maxWords, rubric);
    }

    /** The model answer (audited as an EVALUATION read, with the task it was read for); empty when the author gave none. */
    @Transactional
    public java.util.Optional<String> modelAnswer(UUID versionId, UUID taskId) {
        material(versionId);                                                  // MANUAL types only
        return keys.modelAnswerForEvaluation(versionId, taskId.toString());
    }
}
