package com.ivorovi.questions;

import com.ivorovi.questions.internal.AnswerKeyStore;
import com.ivorovi.questions.internal.QuestionService;
import com.ivorovi.questions.internal.types.QuestionType;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Module API for automatic scoring (M4). Marks responses against answer keys INSIDE the question module: callers pass a
 * question version, a response and the marks the assessment assigns, and get back only an outcome and the marks awarded.
 * Keys, explanations and marking rules never leave this module. Deterministic: the same inputs always give the same grade.
 */
@Component
public class QuestionMarking {

    public enum Outcome { CORRECT, PARTIAL, INCORRECT, UNANSWERED, INVALID, NOT_MARKABLE }

    /** The result of marking one response. {@code detail} is a short non-revealing reason (e.g. "wrong option"). */
    public record Grade(Outcome outcome, BigDecimal awarded, String detail) {}

    private record Version(String type, Map<String, Object> body, Map<String, Object> marking) {}

    private final JdbcClient jdbc;
    private final AnswerKeyStore keys;
    private final QuestionService questions;
    private final JsonMapper json;

    QuestionMarking(JdbcClient jdbc, AnswerKeyStore keys, QuestionService questions, JsonMapper json) {
        this.jdbc = jdbc;
        this.keys = keys;
        this.questions = questions;
        this.json = json;
    }

    /** A marker for one scoring run: caches each version's definition and key for the run only, then forgets them. */
    public final class Run {
        private final Map<UUID, Optional<Version>> versions = new HashMap<>();
        private final Map<UUID, Optional<Map<String, Object>>> answerKeys = new HashMap<>();

        private Run() {}

        @SuppressWarnings("unchecked")
        public Grade mark(UUID versionId, Object response, BigDecimal marks, BigDecimal negativeMarks) {
            Optional<Version> v = versions.computeIfAbsent(versionId, id -> jdbc.sql("SELECT type_code, body::text, marking::text FROM question_version WHERE id = ?")
                    .param(id).query((rs, n) -> new Version(rs.getString(1), json.readValue(rs.getString(2), Map.class), json.readValue(rs.getString(3), Map.class)))
                    .optional());
            Optional<Map<String, Object>> key = answerKeys.computeIfAbsent(versionId, keys::readForMarking);
            if (v.isEmpty() || key.isEmpty()) return new Grade(Outcome.NOT_MARKABLE, BigDecimal.ZERO, "no answer key");
            QuestionType.Grade g;
            try {
                g = questions.type(v.get().type()).mark(v.get().body(), key.get(), v.get().marking(), marks, negativeMarks, response);
            } catch (RuntimeException e) {
                return new Grade(Outcome.INVALID, BigDecimal.ZERO, "response could not be marked");
            }
            return new Grade(Outcome.valueOf(g.outcome().name()), g.awarded(), g.detail());
        }
    }

    /** Starts a scoring run (call inside the caller's transaction and tenant). */
    @Transactional(readOnly = true)
    public Run run() {
        return new Run();
    }
}
