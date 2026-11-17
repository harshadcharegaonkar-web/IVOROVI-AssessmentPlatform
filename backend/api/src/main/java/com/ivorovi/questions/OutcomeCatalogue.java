package com.ivorovi.questions;

import com.ivorovi.platform.security.Authz;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only module API (demo programme S-6) for learning analytics: the organization's learning outcomes and the
 * outcome, subject, topic and difficulty tags of question versions. Metadata only: never question content, options,
 * marking rules or anything from the answer-key table (ADR-0009). Readable by holders of {@code question.read} or
 * {@code analytics.read} anywhere in the organization (the taxonomy is shared reference data, as on the question screens).
 */
@Component
public class OutcomeCatalogue {

    /** A learning outcome; {@code academicSubjectId} is the academics subject its question subject is linked to (may be null). */
    public record Outcome(UUID id, String code, String description, String bloomLevel, String status, UUID questionSubjectId, String questionSubjectCode,
                          String questionSubjectName, UUID academicSubjectId) {}

    /** The tags of one question version. */
    public record Tags(UUID questionVersionId, List<UUID> outcomeIds, UUID questionSubjectId, UUID academicSubjectId, UUID topicId, UUID difficultyId) {}

    static final int MAX_VERSIONS = 20000;

    private final JdbcClient jdbc;
    private final Authz authz;

    OutcomeCatalogue(JdbcClient jdbc, Authz authz) {
        this.jdbc = jdbc;
        this.authz = authz;
    }

    private void requireRead() {
        if (!authz.hasAnywhere("question.read") && !authz.hasAnywhere("analytics.read")) throw new AccessDeniedException("Missing permission analytics.read");
    }

    @Transactional(readOnly = true)
    public List<Outcome> outcomes() {
        requireRead();
        return jdbc.sql("""
                SELECT o.id, o.code, o.description, o.bloom_level, o.status, s.id, s.code, s.name, s.academic_subject_id
                FROM learning_outcome o JOIN question_subject s ON s.id = o.subject_id ORDER BY lower(s.code), lower(o.code)""")
                .query((rs, n) -> new Outcome(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getObject(6, UUID.class), rs.getString(7), rs.getString(8), rs.getObject(9, UUID.class))).list();
    }

    @Transactional(readOnly = true)
    public Map<UUID, Tags> tags(Collection<UUID> questionVersionIds) {
        requireRead();
        if (questionVersionIds == null || questionVersionIds.isEmpty()) return Map.of();
        if (questionVersionIds.size() > MAX_VERSIONS) throw new IllegalArgumentException("At most " + MAX_VERSIONS + " question versions at a time");
        Map<UUID, Tags> out = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT v.id, v.outcome_ids, v.subject_id, s.academic_subject_id, v.topic_id, v.difficulty_id
                FROM question_version v LEFT JOIN question_subject s ON s.id = v.subject_id WHERE v.id = ANY (?)""")
                .param(questionVersionIds.toArray(UUID[]::new))
                .query((rs, n) -> {
                    UUID[] outcomes = rs.getArray(2) == null ? new UUID[0] : (UUID[]) rs.getArray(2).getArray();
                    Tags t = new Tags(rs.getObject(1, UUID.class), List.of(outcomes), rs.getObject(3, UUID.class), rs.getObject(4, UUID.class),
                            rs.getObject(5, UUID.class), rs.getObject(6, UUID.class));
                    out.put(t.questionVersionId(), t);
                    return t;
                }).list();
        return out;
    }
}
