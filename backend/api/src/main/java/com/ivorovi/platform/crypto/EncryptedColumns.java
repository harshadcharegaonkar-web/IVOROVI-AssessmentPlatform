package com.ivorovi.platform.crypto;

import java.util.List;

/**
 * The database columns that hold {@link FieldCipher} ciphertext and that the platform may read, with the field
 * identifier their owner binds as associated data. Key rotation and the startup key check work from this list only;
 * {@code KeyRotationIT} fails when a {@code *_enc} column exists in the schema but is neither here nor in its explicit
 * list of module-owned columns, so a new encrypted column cannot be forgotten.
 * <p>Answer keys and explanations are deliberately absent: by ADR-0009 only the question module's answer-key store
 * may read their table, so their rotation (and key check) belongs to that module (open item of KI-49).
 * Table and column names are fixed constants (never user input); they are the only identifiers concatenated into SQL.
 */
public final class EncryptedColumns {

    /**
     * @param guarded the table refuses updates through an immutability trigger: not rewritten (immutable record; the
     *                rotation mechanism is a design under the product owner's review, KI-49); reported as pending,
     *                never silently skipped
     */
    public record Column(String table, String column, String idColumn, String field, boolean guarded) {
        public String name() {
            return table + "." + column;
        }
    }

    public static final List<Column> ALL = List.of(
            new Column("assessment_paper", "seed_enc", "id", "assessment_paper.seed", true),
            new Column("candidate_account", "email_enc", "id", "candidate_account.email", false),
            new Column("email_delivery", "recipient_enc", "id", "email_delivery.recipient", false),
            new Column("exam_enrolment", "email_enc", "id", "exam_enrolment.email", false),
            new Column("guardian", "address_enc", "id", "guardian.address", false),
            new Column("guardian", "email_enc", "id", "guardian.email", false),
            new Column("guardian", "mobile_enc", "id", "guardian.mobile", false),
            new Column("integration", "secret_enc", "id", "integration.secret", false),
            new Column("question_import_row", "key_enc", "id", "question_import_row.key", false),
            new Column("student", "alternate_phone_enc", "id", "student.alternate_phone", false),
            new Column("student", "current_address_enc", "id", "student.current_address", false),
            new Column("student", "date_of_birth_enc", "id", "student.date_of_birth", false),
            new Column("student", "mobile_enc", "id", "student.mobile", false),
            new Column("student", "permanent_address_enc", "id", "student.permanent_address", false),
            new Column("student", "personal_email_enc", "id", "student.personal_email", false),
            new Column("student_import_row", "cells_enc", "id", "student_import_row.cells", false),
            new Column("webhook_endpoint", "secret_enc", "id", "webhook.secret", false));

    /** SQL: the key id a stored value names ({@code '?'} for an unknown format), mirroring {@link FieldCipher#keyIdOf}. */
    static String keyIdExpression(Column c) {
        String col = c.column();
        return "CASE WHEN " + col + " LIKE 'v1:%' THEN '1' WHEN " + col + " ~ '^v2:[a-z0-9-]{1,16}:' THEN split_part(" + col + ", ':', 2) ELSE '?' END";
    }

    /** SQL: the value is NOT on {@code keyId} (current-format check, no decryption). {@code keyId} must match {@link FieldCipher#KEY_ID}. */
    static String notOnKey(Column c, String keyId) {
        if (!FieldCipher.KEY_ID.matcher(keyId).matches()) throw new IllegalArgumentException("invalid key id");
        String col = c.column();
        return FieldCipher.LEGACY_KEY_ID.equals(keyId)
                ? "NOT (" + col + " LIKE 'v1:%' OR " + col + " LIKE 'v2:1:%')"
                : "NOT (" + col + " LIKE 'v2:" + keyId + ":%')";
    }

    private EncryptedColumns() {}
}
