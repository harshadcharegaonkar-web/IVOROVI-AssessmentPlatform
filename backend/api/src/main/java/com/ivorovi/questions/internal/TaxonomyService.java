package com.ivorovi.questions.internal;

import com.ivorovi.platform.audit.AuditLog;
import com.ivorovi.platform.ids.Ids;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.platform.web.ApiException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Institution-configurable taxonomy: subjects (optionally linked to an academic subject), topics and subtopics, learning
 * outcomes, difficulty levels, managed tags and metadata fields. Entries in use are deactivated, never deleted.
 */
@Service
public class TaxonomyService {

    @Schema(name = "QuestionSubject")
    public record Subject(UUID id, UUID orgUnitId, String code, String name, UUID academicSubjectId, String status) {}

    @Schema(name = "QuestionTopic")
    public record Topic(UUID id, UUID subjectId, UUID parentId, int level, String name, String status) {}

    @Schema(name = "LearningOutcome")
    public record Outcome(UUID id, UUID subjectId, String code, String description, String bloomLevel, String status) {}

    @Schema(name = "DifficultyLevel")
    public record Difficulty(UUID id, String code, String label, int rank, String status) {}

    @Schema(name = "QuestionTag")
    public record Tag(UUID id, String name) {}

    @Schema(name = "QuestionAttributeDefinition")
    public record Attribute(UUID id, String key, String label, String type, List<String> allowedValues, boolean requiredForApproval, String status) {}

    @Schema(name = "QuestionTaxonomy")
    public record Taxonomy(List<Subject> subjects, List<Topic> topics, List<Outcome> outcomes, List<Difficulty> difficulties, List<Tag> tags,
                           List<Attribute> attributes) {}

    private static final List<String[]> DEFAULT_DIFFICULTY = List.of(new String[] {"VERY_EASY", "Very easy"}, new String[] {"EASY", "Easy"},
            new String[] {"MEDIUM", "Medium"}, new String[] {"HARD", "Hard"}, new String[] {"VERY_HARD", "Very hard"});
    private static final Set<String> BLOOM = Set.of("REMEMBER", "UNDERSTAND", "APPLY", "ANALYSE", "EVALUATE", "CREATE");

    private final JdbcClient jdbc;
    private final QuestionAccess access;
    private final AuditLog audit;

    TaxonomyService(JdbcClient jdbc, QuestionAccess access, AuditLog audit) {
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
    }

    private void requireReadAnywhere() {
        // taxonomy is shared reference data of the organization: any question-bank user may read it
        if (!access.anywhere(QuestionAccess.READ)) throw new AccessDeniedException("Missing permission " + QuestionAccess.READ);
    }

    @Transactional
    public Taxonomy all() {
        requireReadAnywhere();
        ensureDifficulties();
        return new Taxonomy(
                jdbc.sql("SELECT id, org_unit_id, code, name, academic_subject_id, status FROM question_subject ORDER BY lower(name)")
                        .query((rs, n) -> new Subject(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                                rs.getObject(5, UUID.class), rs.getString(6))).list(),
                jdbc.sql("SELECT id, subject_id, parent_id, level, name, status FROM question_topic ORDER BY level, lower(name)")
                        .query((rs, n) -> new Topic(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getInt(4),
                                rs.getString(5), rs.getString(6))).list(),
                jdbc.sql("SELECT id, subject_id, code, description, bloom_level, status FROM learning_outcome ORDER BY lower(code)")
                        .query((rs, n) -> new Outcome(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                                rs.getString(5), rs.getString(6))).list(),
                jdbc.sql("SELECT id, code, label, rank, status FROM difficulty_level ORDER BY rank")
                        .query((rs, n) -> new Difficulty(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getString(5))).list(),
                jdbc.sql("SELECT id, name FROM question_tag ORDER BY lower(name)").query((rs, n) -> new Tag(rs.getObject(1, UUID.class), rs.getString(2))).list(),
                jdbc.sql("SELECT id, key, label, type, allowed_values, required_for_approval, status FROM question_attribute_definition ORDER BY key")
                        .query((rs, n) -> new Attribute(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                                BankService.strings(rs.getArray(5)), rs.getBoolean(6), rs.getString(7))).list());
    }

    void ensureDifficulties() {
        long n = jdbc.sql("SELECT count(*) FROM difficulty_level").query(Long.class).single();
        if (n > 0) return;
        int rank = 1;
        for (String[] d : DEFAULT_DIFFICULTY) {
            jdbc.sql("INSERT INTO difficulty_level (id, tenant_id, code, label, rank) VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING")
                    .params(Ids.newId(), TenantContext.requireTenantId(), d[0], d[1], rank++).update();
        }
    }

    private void requireManage(UUID unit) {
        if (!access.can(QuestionAccess.MANAGE, unit)) throw new AccessDeniedException("Missing permission " + QuestionAccess.MANAGE);
    }

    @Transactional
    public Subject createSubject(UUID unit, String code, String name, UUID academicSubjectId) {
        requireManage(unit);
        UUID id = Ids.newId();
        insert(() -> jdbc.sql("INSERT INTO question_subject (id, tenant_id, org_unit_id, code, name, academic_subject_id) VALUES (?, ?, ?, ?, ?, ?)")
                .params(id, TenantContext.requireTenantId(), unit, code.strip(), name.strip(), academicSubjectId).update(), "subject");
        audit.recordSuccess("question.taxonomy.subject_created", "question_subject", id.toString(), Map.of("code", code.strip()));
        return new Subject(id, unit, code.strip(), name.strip(), academicSubjectId, "ACTIVE");
    }

    @Transactional
    public Topic createTopic(UUID subjectId, UUID parentId, String name) {
        UUID unit = jdbc.sql("SELECT org_unit_id FROM question_subject WHERE id = ?").param(subjectId).query(UUID.class).optional()
                .orElseThrow(() -> ApiException.notFound("Subject"));
        requireManage(unit);
        int level = 1;
        if (parentId != null) {
            record P(UUID subject, int level) {}
            P p = jdbc.sql("SELECT subject_id, level FROM question_topic WHERE id = ?").param(parentId).query((rs, n) -> new P(rs.getObject(1, UUID.class), rs.getInt(2)))
                    .optional().orElseThrow(() -> ApiException.notFound("Topic"));
            if (!p.subject().equals(subjectId) || p.level() != 1) throw ApiException.badRequest("invalid_parent", "A subtopic belongs to a topic of the same subject.");
            level = 2;
        }
        UUID id = Ids.newId();
        int lvl = level;
        insert(() -> jdbc.sql("INSERT INTO question_topic (id, tenant_id, subject_id, parent_id, level, name) VALUES (?, ?, ?, ?, ?, ?)")
                .params(id, TenantContext.requireTenantId(), subjectId, parentId, lvl, name.strip()).update(), "topic");
        audit.recordSuccess("question.taxonomy.topic_created", "question_topic", id.toString(), Map.of("level", String.valueOf(level)));
        return new Topic(id, subjectId, parentId, level, name.strip(), "ACTIVE");
    }

    @Transactional
    public Outcome createOutcome(UUID subjectId, String code, String description, String bloom) {
        UUID unit = jdbc.sql("SELECT org_unit_id FROM question_subject WHERE id = ?").param(subjectId).query(UUID.class).optional()
                .orElseThrow(() -> ApiException.notFound("Subject"));
        requireManage(unit);
        if (bloom != null && !BLOOM.contains(bloom)) throw ApiException.badRequest("invalid_bloom", "Unknown Bloom level.");
        UUID id = Ids.newId();
        insert(() -> jdbc.sql("INSERT INTO learning_outcome (id, tenant_id, subject_id, code, description, bloom_level) VALUES (?, ?, ?, ?, ?, ?)")
                .params(id, TenantContext.requireTenantId(), subjectId, code.strip(), description.strip(), bloom).update(), "learning outcome");
        audit.recordSuccess("question.taxonomy.outcome_created", "learning_outcome", id.toString(), Map.of("code", code.strip()));
        return new Outcome(id, subjectId, code.strip(), description.strip(), bloom, "ACTIVE");
    }

    @Transactional
    public Difficulty createDifficulty(String code, String label, int rank) {
        requireManage(null);
        UUID id = Ids.newId();
        insert(() -> jdbc.sql("INSERT INTO difficulty_level (id, tenant_id, code, label, rank) VALUES (?, ?, ?, ?, ?)")
                .params(id, TenantContext.requireTenantId(), code.strip().toUpperCase(), label.strip(), rank).update(), "difficulty level");
        audit.recordSuccess("question.taxonomy.difficulty_created", "difficulty_level", id.toString(), Map.of("code", code.strip()));
        return new Difficulty(id, code.strip().toUpperCase(), label.strip(), rank, "ACTIVE");
    }

    @Transactional
    public Attribute createAttribute(String key, String label, String type, List<String> allowed, boolean required) {
        requireManage(null);
        if (!Set.of("TEXT", "NUMBER", "BOOLEAN", "CHOICE", "DATE").contains(type)) throw ApiException.badRequest("invalid_type", "Unknown field type.");
        List<String> values = allowed == null ? List.of() : allowed.stream().map(String::strip).filter(s -> !s.isEmpty()).distinct().toList();
        UUID id = Ids.newId();
        insert(() -> jdbc.sql("INSERT INTO question_attribute_definition (id, tenant_id, key, label, type, allowed_values, required_for_approval) VALUES (?, ?, ?, ?, ?, ?::text[], ?)")
                .params(id, TenantContext.requireTenantId(), key.strip(), label.strip(), type, "{" + String.join(",", values.stream().map(TaxonomyService::arr).toList()) + "}",
                        required).update(), "metadata field");
        audit.recordSuccess("question.taxonomy.attribute_created", "question_attribute_definition", id.toString(), Map.of("key", key.strip(), "type", type));
        return new Attribute(id, key.strip(), label.strip(), type, values, required, "ACTIVE");
    }

    /** Deactivates (or reactivates) a taxonomy entry; entries in use are never deleted. */
    @Transactional
    public void setStatus(String kind, UUID id, boolean active) {
        String table = switch (kind) {
            case "subjects" -> "question_subject";
            case "topics" -> "question_topic";
            case "learning-outcomes" -> "learning_outcome";
            case "difficulty-levels" -> "difficulty_level";
            case "attributes" -> "question_attribute_definition";
            default -> throw ApiException.notFound("Taxonomy entry");
        };
        requireManage(null);
        int n = jdbc.sql("UPDATE " + table + " SET status = ? WHERE id = ?").params(active ? "ACTIVE" : "INACTIVE", id).update();
        if (n == 0) throw ApiException.notFound("Taxonomy entry");
        audit.recordSuccess("question.taxonomy." + (active ? "activated" : "deactivated"), table, id.toString(), Map.of());
    }

    /** Tags used on a question: managed tags are created on first use when the bank allows free tags. */
    List<String> resolveTags(List<String> tags, boolean freeTags) {
        if (tags == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String t : tags) {
            String name = t == null ? "" : t.strip();
            if (name.isEmpty()) continue;
            if (!name.matches("^[\\w .+#/-]{1,50}$")) throw ApiException.badRequest("invalid_tag", "Tags use letters, digits, spaces and . + # / - (at most 50).");
            String existing = jdbc.sql("SELECT name FROM question_tag WHERE lower(name) = lower(?)").param(name).query(String.class).optional().orElse(null);
            if (existing == null) {
                if (!freeTags) throw ApiException.badRequest("unknown_tag", "Tag '" + name + "' is not in the managed tag list.");
                jdbc.sql("INSERT INTO question_tag (id, tenant_id, name) VALUES (?, ?, ?) ON CONFLICT DO NOTHING").params(Ids.newId(), TenantContext.requireTenantId(), name).update();
                existing = name;
            }
            if (!out.contains(existing)) out.add(existing);
        }
        if (out.size() > 20) throw ApiException.badRequest("too_many_tags", "At most 20 tags.");
        return out;
    }

    private static String arr(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static void insert(Runnable r, String what) {
        try {
            r.run();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("duplicate", "This " + what + " already exists.");
        } catch (DataIntegrityViolationException e) {
            throw ApiException.badRequest("invalid_reference", "The " + what + " refers to an unknown or invalid entry.");
        }
    }
}
