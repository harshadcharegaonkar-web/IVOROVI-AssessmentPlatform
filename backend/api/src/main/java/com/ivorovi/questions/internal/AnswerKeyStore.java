package com.ivorovi.questions.internal;

import com.ivorovi.platform.audit.AuditLog;
import com.ivorovi.platform.crypto.FieldCipher;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.platform.web.ApiException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The only component that reads or writes {@code question_answer_key} (enforced by an architecture test). Every read
 * re-checks the caller's right for that specific version, independently of the controller, and is audited; every write
 * is audited with key hashes only. Keys never leave this class except as the return value of {@link #read}.
 *
 * <p>Who may see a key: DRAFT — its author; IN_REVIEW/REVIEWED — the author and holders of {@code question.review} or
 * {@code question.approve} at the bank; APPROVED/SUPERSEDED/RETIRED — holders of {@code question.key.read} or
 * {@code question.approve}. {@code question.bank.manage} never grants access.
 */
@Component
public class AnswerKeyStore {

    public static final String KEY_FIELD = "question_answer_key.key";
    public static final String EXPLANATION_FIELD = "question_answer_key.explanation";

    public enum Purpose { EDIT, REVIEW, VIEW, MARKING_PREVIEW }

    /** Decrypted key and explanation. {@link #toString()} never prints them. */
    public record Plain(Map<String, Object> key, String explanation) {
        @Override
        public String toString() {
            return "AnswerKey[redacted]";
        }
    }

    record Target(UUID versionId, String state, UUID authorId, UUID orgUnitId) {}

    private final JdbcClient jdbc;
    private final FieldCipher cipher;
    private final AuditLog audit;
    private final QuestionAccess access;
    private final JsonMapper json;

    AnswerKeyStore(JdbcClient jdbc, FieldCipher cipher, AuditLog audit, QuestionAccess access, JsonMapper json) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.audit = audit;
        this.access = access;
        this.json = json;
    }

    private Target target(UUID versionId) {
        return jdbc.sql("""
                SELECT v.id, v.state, v.author_id, b.org_unit_id FROM question_version v JOIN question q ON q.id = v.question_id
                JOIN question_bank b ON b.id = q.bank_id WHERE v.id = ?""").param(versionId)
                .query((rs, n) -> new Target(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class), rs.getObject(4, UUID.class)))
                .optional().orElseThrow(() -> ApiException.notFound("Question version"));
    }

    /** Whether the caller may see the key of this version (no audit; used to show or hide the key panel). */
    public boolean mayRead(UUID versionId) {
        return allowed(target(versionId));
    }

    boolean allowed(Target t) {
        if (!access.can(QuestionAccess.READ, t.orgUnitId())) return false;
        boolean author = access.me().equals(t.authorId());
        return switch (t.state()) {
            case "DRAFT" -> author;
            case "IN_REVIEW", "REVIEWED" -> author || access.can(QuestionAccess.REVIEW, t.orgUnitId()) || access.can(QuestionAccess.APPROVE, t.orgUnitId());
            default -> access.can(QuestionAccess.KEY_READ, t.orgUnitId()) || access.can(QuestionAccess.APPROVE, t.orgUnitId());
        };
    }

    @SuppressWarnings("unchecked")
    public Optional<Plain> read(UUID versionId, Purpose purpose) {
        Target t = target(versionId);
        if (!allowed(t)) {
            audit.recordDenied("question.answer_key.view_denied", Map.of("versionId", versionId.toString(), "purpose", purpose.name()));
            throw new AccessDeniedException("No access to this answer key");
        }
        UUID tenant = TenantContext.requireTenantId();
        record Row(String key, String explanation) {}
        Optional<Row> row = jdbc.sql("SELECT key_enc, explanation_enc FROM question_answer_key WHERE version_id = ?").param(versionId)
                .query((rs, n) -> new Row(rs.getString(1), rs.getString(2))).optional();
        audit.recordSuccess("question.answer_key.viewed", "question_version", versionId.toString(), Map.of("purpose", purpose.name(), "state", t.state()));
        return row.map(r -> new Plain(json.readValue(cipher.decrypt(r.key(), KEY_FIELD, tenant), Map.class),
                r.explanation() == null ? null : cipher.decrypt(r.explanation(), EXPLANATION_FIELD, tenant)));
    }

    /**
     * Key for automatic scoring inside the question module (QuestionMarking): the key is used and discarded there, never
     * returned to another module; not audited per question (the scoring run is audited by its caller).
     */
    public Optional<Map<String, Object>> readForMarking(UUID versionId) {
        return readForValidation(versionId);
    }

    /** Internal read for marking/validation inside a service call that already authorized the caller; not audited as a view. */
    @SuppressWarnings("unchecked")
    Optional<Map<String, Object>> readForValidation(UUID versionId) {
        UUID tenant = TenantContext.requireTenantId();
        return jdbc.sql("SELECT key_enc FROM question_answer_key WHERE version_id = ?").param(versionId).query(String.class).optional()
                .map(k -> json.readValue(cipher.decrypt(k, KEY_FIELD, tenant), Map.class));
    }

    /** Hash of the canonical key (HMAC under the data key), part of the review hash. */
    public String hash(Map<String, Object> canonicalKey) {
        return cipher.blindIndex(Canonical.json(canonicalKey), "question-answer-key");
    }

    /** Writes the key of a draft (caller must be the draft's author). Audited with hashes only. */
    public String write(UUID versionId, Map<String, Object> canonicalKey, String explanation) {
        Target t = target(versionId);
        if (!"DRAFT".equals(t.state()) || !access.me().equals(t.authorId())) throw new AccessDeniedException("Only the author of a draft can change its answer key");
        UUID tenant = TenantContext.requireTenantId();
        String h = hash(canonicalKey);
        String old = jdbc.sql("SELECT key_hash FROM question_answer_key WHERE version_id = ?").param(versionId).query(String.class).optional().orElse(null);
        String exp = explanation == null || explanation.isBlank() ? null : cipher.encrypt(explanation.strip(), EXPLANATION_FIELD, tenant);
        jdbc.sql("""
                INSERT INTO question_answer_key (version_id, tenant_id, key_enc, explanation_enc, key_hash) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (version_id) DO UPDATE SET key_enc = EXCLUDED.key_enc, explanation_enc = EXCLUDED.explanation_enc,
                    key_hash = EXCLUDED.key_hash, updated_at = now()""")
                .params(versionId, tenant, cipher.encrypt(Canonical.json(canonicalKey), KEY_FIELD, tenant), exp, h).update();
        if (!h.equals(old)) {
            audit.recordSuccess("question.answer_key.changed", "question_version", versionId.toString(),
                    Map.of("previousKeyHash", old == null ? "none" : old.substring(0, 16), "keyHash", h.substring(0, 16)));
        }
        return h;
    }

    /** Copies the encrypted key of {@code from} to the new draft {@code to} (same organization; ciphertext reused). */
    void copy(UUID from, UUID to) {
        jdbc.sql("""
                INSERT INTO question_answer_key (version_id, tenant_id, key_enc, explanation_enc, key_hash)
                SELECT ?, tenant_id, key_enc, explanation_enc, key_hash FROM question_answer_key WHERE version_id = ?""").params(to, from).update();
    }

    Optional<String> keyHash(UUID versionId) {
        return jdbc.sql("SELECT key_hash FROM question_answer_key WHERE version_id = ?").param(versionId).query(String.class).optional();
    }

    void deleteDraft(UUID versionId) {
        jdbc.sql("DELETE FROM question_answer_key WHERE version_id = ?").param(versionId).update();
    }
}
