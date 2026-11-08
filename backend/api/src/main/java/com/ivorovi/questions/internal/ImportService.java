package com.ivorovi.questions.internal;

import com.ivorovi.platform.audit.AuditLog;
import com.ivorovi.platform.config.DeploymentProperties;
import com.ivorovi.platform.crypto.FieldCipher;
import com.ivorovi.platform.ids.Ids;
import com.ivorovi.platform.scanning.MalwareScanner;
import com.ivorovi.platform.storage.ObjectStorage;
import com.ivorovi.platform.tabular.TabularFile;
import com.ivorovi.platform.tabular.TabularFormatException;
import com.ivorovi.platform.tabular.TabularLimits;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.platform.web.ApiException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Bulk import of questions from CSV or XLSX: scan gate (fail closed outside development), every row validated with the
 * same rules as the editor, exact duplicates within the file and against the bank, near duplicates (trigram similarity of
 * the normalized statement ≥ 0.85) — all shown in a preview; nothing is created before commit; commit is idempotent and
 * creates drafts only. Answer keys are encrypted per row at upload and never shown in the preview.
 */
@Service
public class ImportService {

    public static final int MAX_ROWS = 2000;
    public static final long MAX_BYTES = 10L * 1024 * 1024;
    static final double NEAR_DUPLICATE = 0.85;
    static final String ROW_KEY_FIELD = "question_import_row.key";
    static final List<String> COLUMNS = List.of("type", "statement", "option_a", "option_b", "option_c", "option_d", "option_e", "option_f", "correct",
            "accepted_answers", "match", "numeric_value", "tolerance_type", "tolerance", "precision", "rounding", "unit", "partial_tolerance", "partial_percent",
            "scoring_mode", "marks", "negative_marks", "subject_code", "topic", "subtopic", "difficulty", "outcomes", "tags", "explanation", "language");

    @Schema(name = "QuestionImport")
    public record Import(UUID id, UUID bankId, String fileName, String format, String status, int totalRows, int validRows, int invalidRows,
                         int duplicateRows, int nearDuplicateRows, int createdRows, String createdByName, Instant createdAt, Instant committedAt) {}

    @Schema(name = "QuestionImportRow")
    public record Row(int rowNo, String outcome, List<String> errors, String type, String excerpt, UUID matchedQuestionId, BigDecimal similarity,
                      UUID createdQuestionId) {}

    private final JdbcClient jdbc;
    private final QuestionAccess access;
    private final QuestionService questions;
    private final AuditLog audit;
    private final FieldCipher cipher;
    private final ObjectStorage storage;
    private final ObjectProvider<MalwareScanner> scanner;
    private final DeploymentProperties deployment;
    private final JsonMapper json;

    ImportService(JdbcClient jdbc, QuestionAccess access, QuestionService questions, AuditLog audit, FieldCipher cipher, ObjectStorage storage,
                  ObjectProvider<MalwareScanner> scanner, DeploymentProperties deployment, JsonMapper json) {
        this.jdbc = jdbc;
        this.access = access;
        this.questions = questions;
        this.audit = audit;
        this.cipher = cipher;
        this.storage = storage;
        this.scanner = scanner;
        this.deployment = deployment;
        this.json = json;
    }

    /** CSV template with the columns and one example row per type (examples only; no real content). */
    public static String template() {
        StringBuilder sb = new StringBuilder(String.join(",", COLUMNS)).append('\n');
        sb.append(csv("SINGLE_CHOICE", "Which gas do plants absorb for photosynthesis?", "Oxygen", "Carbon dioxide", "Nitrogen", "Hydrogen", "", "", "B",
                "", "", "", "", "", "", "", "", "", "", "", "1", "0.25", "", "", "", "", "", "biology;example", "Plants take in CO2.", "en"));
        sb.append(csv("MULTIPLE_CHOICE", "Which of these are prime numbers?", "2", "4", "5", "9", "", "", "A;C", "", "", "", "", "", "", "", "", "", "",
                "PARTIAL", "2", "0", "", "", "", "", "", "example", "", "en"));
        sb.append(csv("TRUE_FALSE", "The value of $\\pi$ is exactly 3.14.", "", "", "", "", "", "", "FALSE", "", "", "", "", "", "", "", "", "", "", "",
                "1", "0", "", "", "", "", "", "example", "", "en"));
        sb.append(csv("SHORT_ANSWER", "Name the capital of Karnataka.", "", "", "", "", "", "", "", "Bengaluru|Bangalore", "NORMALIZED", "", "", "", "", "",
                "", "", "", "", "1", "0", "", "", "", "", "", "example", "", "en"));
        sb.append(csv("NUMERIC", "Evaluate $\\int_0^1 x^2\\,dx$ to two decimal places.", "", "", "", "", "", "", "", "", "", "0.3333", "ABSOLUTE", "0.005",
                "2", "ROUND_HALF_UP", "", "0.02", "50", "", "2", "0.5", "", "", "", "", "", "calculus;example", "", "en"));
        return sb.toString();
    }

    private static String csv(String... cells) {
        return String.join(",", Arrays.stream(cells).map(c -> c.contains(",") || c.contains("\"") ? "\"" + c.replace("\"", "\"\"") + "\"" : c).toList()) + "\n";
    }

    // ================================================================================================ upload + preview

    @Transactional
    public Import upload(UUID bankId, String fileName, byte[] content) {
        QuestionAccess.BankRef bank = access.bank(bankId);
        access.require(QuestionAccess.AUTHOR, bank);
        MalwareScanner sc = scanner.getIfAvailable();
        if (!AssetService.uploadsAllowed(deployment.environment(), sc != null)) {
            audit.recordDenied("question.import.upload_refused", Map.of("bank", bankId.toString(), "reason", "no_malware_scanner"));
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "scanning_unavailable", "Import is unavailable: no malware scanner is configured.");
        }
        if (content == null || content.length == 0) throw ApiException.badRequest("empty_file", "The file is empty.");
        if (content.length > MAX_BYTES) throw ApiException.badRequest("file_too_large", "Import files may be at most 10 MB.");
        TabularFile.Format format;
        try {
            format = TabularFile.detect(fileName, Arrays.copyOf(content, Math.min(content.length, 8192)));
        } catch (TabularFormatException e) {
            throw ApiException.badRequest(e.code(), e.getMessage());
        }
        String scan = "NOT_SCANNED_DEV";
        if (sc != null) {
            MalwareScanner.Result r;
            try {
                r = sc.scan(content);
            } catch (RuntimeException e) {
                r = new MalwareScanner.Result(MalwareScanner.Verdict.ERROR, e.getClass().getSimpleName());
            }
            scan = r.verdict().name();
            if (r.verdict() != MalwareScanner.Verdict.CLEAN) {
                audit.recordDenied("question.import.upload_refused", Map.of("bank", bankId.toString(), "reason", "scan_" + scan.toLowerCase()));
                throw r.verdict() == MalwareScanner.Verdict.INFECTED
                        ? ApiException.badRequest("file_infected", "The file was rejected by the malware scan.")
                        : new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "scanning_failed", "The malware scan could not be completed. Try again later.");
            }
        }
        UUID tenant = TenantContext.requireTenantId();
        UUID id = Ids.newId();
        String key = "t/" + tenant + "/quarantine/question-imports/" + id;
        storage.put(key, new ByteArrayInputStream(content), content.length, "application/octet-stream");
        jdbc.sql("""
                INSERT INTO question_import (id, tenant_id, bank_id, file_name, format, bytes, sha256, object_key, scan_status, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""").params(id, tenant, bankId, name(fileName), format.name(), content.length, AssetService.sha256(content), key, scan,
                access.me()).update();
        preview(id, bankId, format, content);
        audit.recordSuccess("question.import.previewed", "question_import", id.toString(), Map.of("bank", bankId.toString(), "format", format.name(), "scan", scan));
        return get(id);
    }

    private void preview(UUID importId, UUID bankId, TabularFile.Format format, byte[] content) {
        Path tmp = null;
        try {
            tmp = Files.createTempFile("qimport", format == TabularFile.Format.CSV ? ".csv" : ".xlsx");
            Files.write(tmp, content);
            TabularFile.Reader reader = TabularFile.open(format, tmp, new TabularLimits(MAX_ROWS, 60, QuestionService.MAX_STATEMENT, 100L * 1024 * 1024, 200, 500_000));
            List<String> headers = reader.describe().headers().stream().map(h -> h == null ? "" : h.strip().toLowerCase(Locale.ROOT)).toList();
            if (!headers.contains("type") || !headers.contains("statement")) throw ApiException.badRequest("missing_columns", "The file needs at least the columns 'type' and 'statement' (use the template).");
            for (String h : headers) if (!h.isEmpty() && !COLUMNS.contains(h)) throw ApiException.badRequest("unknown_column", "Unknown column '" + h + "' (use the template).");
            Map<String, Integer> seen = new HashMap<>();
            int[] counts = new int[5]; // total, valid, invalid, duplicate, near
            reader.rows(row -> {
                counts[0]++;
                Map<String, String> v = new LinkedHashMap<>();
                for (int i = 0; i < headers.size(); i++) {
                    String text = row.cell(i).text();
                    if (row.cell(i).formula()) text = null;
                    v.put(headers.get(i), text == null ? "" : text.strip());
                }
                storeRow(importId, bankId, row.number(), v, seen, counts);
                return true;
            });
            jdbc.sql("UPDATE question_import SET total_rows = ?, valid_rows = ?, invalid_rows = ?, duplicate_rows = ?, near_duplicate_rows = ? WHERE id = ?")
                    .params(counts[0], counts[1], counts[2], counts[3], counts[4], importId).update();
        } catch (TabularFormatException e) {
            throw ApiException.badRequest(e.code(), e.getMessage());
        } catch (IOException e) {
            throw ApiException.badRequest("unreadable_file", "The file could not be read.");
        } finally {
            if (tmp != null) try { Files.deleteIfExists(tmp); } catch (IOException ignored) { /* temp file */ }
        }
    }

    private void storeRow(UUID importId, UUID bankId, int rowNo, Map<String, String> v, Map<String, Integer> seen, int[] counts) {
        String outcome;
        List<String> errors = new ArrayList<>();
        String payload = null;
        String keyEnc = null;
        String dup = null;
        UUID matched = null;
        BigDecimal similarity = null;
        UUID tenant = TenantContext.requireTenantId();
        try {
            QuestionService.DraftInput in = toInput(v);
            QuestionService.Prepared p = questions.prepare(bankId, in);
            dup = p.dupHash();
            Map<String, Object> pay = new LinkedHashMap<>();
            pay.put("input", withoutKey(in));
            pay.put("excerpt", p.plainText().length() > 200 ? p.plainText().substring(0, 200) : p.plainText());
            payload = json.writeValueAsString(pay);
            keyEnc = cipher.encrypt(json.writeValueAsString(Map.of("key", in.answerKey(), "explanation", in.explanation() == null ? "" : in.explanation())), ROW_KEY_FIELD, tenant);
            Integer firstRow = seen.putIfAbsent(dup, rowNo);
            if (firstRow != null) {
                outcome = "DUPLICATE_IN_FILE";
                errors.add("Same question as row " + firstRow + ".");
                counts[3]++;
            } else {
                matched = jdbc.sql("""
                        SELECT q.id FROM question q JOIN question_version v ON v.question_id = q.id WHERE q.bank_id = ? AND v.dup_hash = ? LIMIT 1""")
                        .params(bankId, dup).query(UUID.class).optional().orElse(null);
                if (matched != null) {
                    outcome = "DUPLICATE_IN_BANK";
                    errors.add("This question already exists in the bank.");
                    counts[3]++;
                } else {
                    record Near(UUID id, double sim) {}
                    // indexed trigram search, tenant- and bank-scoped (see question_near_duplicate in the migration)
                    Near near = jdbc.sql("SELECT question_id, sim FROM question_near_duplicate(?, ?)").params(bankId, p.normalized())
                            .query((rs, n) -> new Near(rs.getObject(1, UUID.class), rs.getDouble(2))).optional().orElse(null);
                    if (near != null && near.sim() >= NEAR_DUPLICATE) {
                        outcome = "NEAR_DUPLICATE";
                        matched = near.id();
                        similarity = BigDecimal.valueOf(near.sim()).setScale(3, java.math.RoundingMode.HALF_UP);
                        errors.add("Very similar to an existing question (" + similarity.multiply(BigDecimal.valueOf(100)).intValue() + "% similar).");
                        counts[4]++;
                    } else {
                        outcome = "VALID";
                        counts[1]++;
                    }
                }
            }
        } catch (ApiException e) {
            outcome = "INVALID";
            errors.add(e.getMessage());
            counts[2]++;
            payload = json.writeValueAsString(Map.of("excerpt", v.getOrDefault("statement", "").length() > 200 ? v.get("statement").substring(0, 200) : v.getOrDefault("statement", ""),
                    "type", v.getOrDefault("type", "")));
        }
        jdbc.sql("""
                INSERT INTO question_import_row (id, tenant_id, import_id, row_no, outcome, errors, payload, key_enc, dup_hash, matched_question_id, similarity)
                VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, ?, ?)""")
                .params(Ids.newId(), tenant, importId, rowNo, outcome, json.writeValueAsString(errors), payload, keyEnc, dup, matched, similarity).update();
    }

    private static Map<String, Object> withoutKey(QuestionService.DraftInput in) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", in.type());
        m.put("content", in.content());
        m.put("body", in.body());
        m.put("marking", in.marking());
        m.put("marks", in.marks());
        m.put("negativeMarks", in.negativeMarks());
        m.put("subjectId", in.subjectId());
        m.put("topicId", in.topicId());
        m.put("subtopicId", in.subtopicId());
        m.put("difficultyId", in.difficultyId());
        m.put("outcomeIds", in.outcomeIds());
        m.put("tags", in.tags());
        m.put("language", in.language());
        return m;
    }

    /** One spreadsheet row → editor input (same validation path as the editor afterwards). */
    QuestionService.DraftInput toInput(Map<String, String> v) {
        String type = v.getOrDefault("type", "").toUpperCase(Locale.ROOT);
        questions.type(type);
        Map<String, Object> content = ContentFormat.fromSource(required(v, "statement"));
        Map<String, Object> body = new LinkedHashMap<>();
        Map<String, Object> key = new LinkedHashMap<>();
        Map<String, Object> marking = new LinkedHashMap<>();
        switch (type) {
            case "SINGLE_CHOICE", "MULTIPLE_CHOICE" -> {
                List<Object> options = new ArrayList<>();
                for (char c = 'a'; c <= 'f'; c++) {
                    String o = v.getOrDefault("option_" + c, "");
                    if (!o.isEmpty()) options.add(Map.of("id", String.valueOf(Character.toUpperCase(c)), "content", ContentFormat.fromSource(o)));
                }
                body.put("options", options);
                List<String> correct = Arrays.stream(required(v, "correct").toUpperCase(Locale.ROOT).split("[;,\\s]+")).filter(s -> !s.isEmpty()).toList();
                if ("SINGLE_CHOICE".equals(type)) key.put("correct", correct.isEmpty() ? "" : correct.get(0));
                else {
                    key.put("correct", correct);
                    if (!v.getOrDefault("scoring_mode", "").isEmpty()) marking.put("mode", v.get("scoring_mode").toUpperCase(Locale.ROOT));
                }
            }
            case "TRUE_FALSE" -> {
                String c = required(v, "correct").toUpperCase(Locale.ROOT);
                if (!c.equals("TRUE") && !c.equals("FALSE")) throw ApiException.badRequest("invalid_question", "correct: TRUE or FALSE.");
                key.put("value", c.equals("TRUE"));
            }
            case "SHORT_ANSWER" -> {
                List<Object> answers = new ArrayList<>();
                for (String a : required(v, "accepted_answers").split("\\|")) if (!a.isBlank()) answers.add(Map.of("text", a.strip()));
                key.put("answers", answers);
                if (!v.getOrDefault("match", "").isEmpty()) key.put("match", v.get("match").toUpperCase(Locale.ROOT));
            }
            case "NUMERIC" -> {
                if (!v.getOrDefault("precision", "").isEmpty()) body.put("precision", integer(v.get("precision"), "precision"));
                if (!v.getOrDefault("unit", "").isEmpty()) body.put("unit", v.get("unit"));
                key.put("value", required(v, "numeric_value"));
                key.put("tolerance", Map.of("type", v.getOrDefault("tolerance_type", "").isEmpty() ? "ABSOLUTE" : v.get("tolerance_type").toUpperCase(Locale.ROOT),
                        "value", v.getOrDefault("tolerance", "").isEmpty() ? "0" : v.get("tolerance")));
                if (!v.getOrDefault("rounding", "").isEmpty()) key.put("rounding", v.get("rounding").toUpperCase(Locale.ROOT));
                if (!v.getOrDefault("partial_tolerance", "").isEmpty()) {
                    key.put("partial", Map.of("tolerance", Map.of("type", ((Map<?, ?>) key.get("tolerance")).get("type"), "value", v.get("partial_tolerance")),
                            "percent", integer(v.getOrDefault("partial_percent", "50"), "partial_percent")));
                }
            }
            default -> throw ApiException.badRequest("unknown_type", "Unknown question type.");
        }
        UUID subject = null;
        UUID topic = null;
        UUID subtopic = null;
        if (!v.getOrDefault("subject_code", "").isEmpty()) {
            subject = jdbc.sql("SELECT id FROM question_subject WHERE lower(code) = lower(?) AND status = 'ACTIVE' LIMIT 1").param(v.get("subject_code")).query(UUID.class)
                    .optional().orElseThrow(() -> ApiException.badRequest("invalid_question", "subject_code: unknown subject."));
            if (!v.getOrDefault("topic", "").isEmpty()) {
                topic = jdbc.sql("SELECT id FROM question_topic WHERE subject_id = ? AND level = 1 AND lower(name) = lower(?)").params(subject, v.get("topic")).query(UUID.class)
                        .optional().orElseThrow(() -> ApiException.badRequest("invalid_question", "topic: unknown topic of the subject."));
                if (!v.getOrDefault("subtopic", "").isEmpty()) {
                    subtopic = jdbc.sql("SELECT id FROM question_topic WHERE parent_id = ? AND level = 2 AND lower(name) = lower(?)").params(topic, v.get("subtopic"))
                            .query(UUID.class).optional().orElseThrow(() -> ApiException.badRequest("invalid_question", "subtopic: unknown subtopic of the topic."));
                }
            }
        }
        UUID difficulty = v.getOrDefault("difficulty", "").isEmpty() ? null
                : jdbc.sql("SELECT id FROM difficulty_level WHERE lower(code) = lower(?) OR lower(label) = lower(?)").params(v.get("difficulty"), v.get("difficulty"))
                        .query(UUID.class).optional().orElseThrow(() -> ApiException.badRequest("invalid_question", "difficulty: unknown difficulty level."));
        List<UUID> outcomes = new ArrayList<>();
        for (String code : v.getOrDefault("outcomes", "").split("[;,]")) {
            if (code.isBlank()) continue;
            if (subject == null) throw ApiException.badRequest("invalid_question", "outcomes: need a subject.");
            UUID s = subject;
            outcomes.add(jdbc.sql("SELECT id FROM learning_outcome WHERE subject_id = ? AND lower(code) = lower(?)").params(s, code.strip()).query(UUID.class)
                    .optional().orElseThrow(() -> ApiException.badRequest("invalid_question", "outcomes: unknown outcome " + code.strip() + ".")));
        }
        List<String> tags = Arrays.stream(v.getOrDefault("tags", "").split("[;]")).map(String::strip).filter(s -> !s.isEmpty()).toList();
        return new QuestionService.DraftInput(type, content, body, marking, key, v.getOrDefault("explanation", ""), decimal(v.get("marks"), "marks"),
                decimal(v.get("negative_marks"), "negative_marks"), subject, topic, subtopic, difficulty, outcomes, tags, null,
                v.getOrDefault("language", "").isEmpty() ? null : v.get("language"), null);
    }

    private static String required(Map<String, String> v, String col) {
        String s = v.getOrDefault(col, "");
        if (s.isEmpty()) throw ApiException.badRequest("invalid_question", col + ": required.");
        return s;
    }

    private static BigDecimal decimal(String s, String col) {
        if (s == null || s.isEmpty()) return null;
        try {
            return new BigDecimal(s.strip());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("invalid_question", col + ": a number.");
        }
    }

    private static int integer(String s, String col) {
        try {
            return Integer.parseInt(s.strip());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("invalid_question", col + ": a whole number.");
        }
    }

    // ================================================================================================ commit

    /** Creates drafts for valid rows (and near duplicates when asked). Idempotent: a committed import returns its result. */
    @SuppressWarnings("unchecked")
    @Transactional
    public Import commit(UUID importId, boolean includeNearDuplicates) {
        Import imp = get(importId);
        QuestionAccess.BankRef bank = access.bank(imp.bankId());
        access.require(QuestionAccess.AUTHOR, bank);
        UUID owner = jdbc.sql("SELECT created_by FROM question_import WHERE id = ? FOR UPDATE").param(importId).query(UUID.class).single();
        if (!owner.equals(access.me())) throw new AccessDeniedException("Only the person who uploaded the file can commit it");
        String status = jdbc.sql("SELECT status FROM question_import WHERE id = ?").param(importId).query(String.class).single();
        if ("COMMITTED".equals(status)) return get(importId);
        if (!"PREVIEWED".equals(status)) throw ApiException.conflict("import_not_committable", "This import cannot be committed.");
        UUID tenant = TenantContext.requireTenantId();
        record R(UUID id, int rowNo, String payload, String key) {}
        List<R> rows = jdbc.sql("SELECT id, row_no, payload::text, key_enc FROM question_import_row WHERE import_id = ? AND outcome = ANY (?) ORDER BY row_no")
                .params(importId, includeNearDuplicates ? new String[] {"VALID", "NEAR_DUPLICATE"} : new String[] {"VALID"})
                .query((rs, n) -> new R(rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3), rs.getString(4))).list();
        int created = 0;
        for (R r : rows) {
            Map<String, Object> pay = json.readValue(r.payload(), Map.class);
            Map<String, Object> in = (Map<String, Object>) pay.get("input");
            Map<String, Object> k = json.readValue(cipher.decrypt(r.key(), ROW_KEY_FIELD, tenant), Map.class);
            QuestionService.DraftInput d = new QuestionService.DraftInput((String) in.get("type"), (Map<String, Object>) in.get("content"),
                    (Map<String, Object>) in.get("body"), (Map<String, Object>) in.get("marking"), (Map<String, Object>) k.get("key"), (String) k.get("explanation"),
                    in.get("marks") == null ? null : new BigDecimal(String.valueOf(in.get("marks"))),
                    in.get("negativeMarks") == null ? null : new BigDecimal(String.valueOf(in.get("negativeMarks"))), uuid(in.get("subjectId")), uuid(in.get("topicId")),
                    uuid(in.get("subtopicId")), uuid(in.get("difficultyId")),
                    ((List<Object>) in.getOrDefault("outcomeIds", List.of())).stream().map(o -> UUID.fromString(String.valueOf(o))).toList(),
                    (List<String>) in.getOrDefault("tags", List.of()), null, (String) in.get("language"), null);
            UUID qid = questions.createImported(imp.bankId(), questions.prepare(imp.bankId(), d), importId);
            jdbc.sql("UPDATE question_import_row SET created_question_id = ?, key_enc = NULL WHERE id = ?").params(qid, r.id()).update();
            created++;
        }
        jdbc.sql("UPDATE question_import_row SET key_enc = NULL WHERE import_id = ?").param(importId).update();
        jdbc.sql("UPDATE question_import SET status = 'COMMITTED', created_rows = ?, committed_at = now() WHERE id = ?").params(created, importId).update();
        audit.recordSuccess("question.import.committed", "question_import", importId.toString(), Map.of("created", String.valueOf(created),
                "nearDuplicatesIncluded", String.valueOf(includeNearDuplicates)));
        return get(importId);
    }

    private static UUID uuid(Object o) {
        return o == null ? null : UUID.fromString(String.valueOf(o));
    }

    // ================================================================================================ reading

    @Transactional(readOnly = true)
    public Import read(UUID importId) {
        Import i = get(importId);
        access.bank(i.bankId());
        return i;
    }

    Import get(UUID importId) {
        return jdbc.sql("""
                SELECT i.*, u.display_name AS by_name FROM question_import i LEFT JOIN user_account u ON u.id = i.created_by WHERE i.id = ?""").param(importId)
                .query((rs, n) -> new Import(rs.getObject("id", UUID.class), rs.getObject("bank_id", UUID.class), rs.getString("file_name"), rs.getString("format"),
                        rs.getString("status"), rs.getInt("total_rows"), rs.getInt("valid_rows"), rs.getInt("invalid_rows"), rs.getInt("duplicate_rows"),
                        rs.getInt("near_duplicate_rows"), rs.getInt("created_rows"), rs.getString("by_name"), rs.getTimestamp("created_at").toInstant(),
                        QuestionService.ts(rs, "committed_at"))).optional().orElseThrow(() -> ApiException.notFound("Import"));
    }

    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true)
    public List<Row> rows(UUID importId) {
        read(importId);
        return jdbc.sql("""
                SELECT row_no, outcome, errors::text, payload::text, matched_question_id, similarity, created_question_id FROM question_import_row
                WHERE import_id = ? ORDER BY row_no""").param(importId).query((rs, n) -> {
                    Map<String, Object> pay = rs.getString(4) == null ? Map.of() : json.readValue(rs.getString(4), Map.class);
                    Object input = pay.get("input");
                    String type = input instanceof Map<?, ?> m ? String.valueOf(m.get("type")) : String.valueOf(pay.getOrDefault("type", ""));
                    return new Row(rs.getInt(1), rs.getString(2), json.readValue(rs.getString(3), List.class), type, String.valueOf(pay.getOrDefault("excerpt", "")),
                            rs.getObject(5, UUID.class), rs.getBigDecimal(6), rs.getObject(7, UUID.class));
                }).list();
    }

    @Transactional(readOnly = true)
    public List<Import> list(UUID bankId) {
        access.bank(bankId);
        List<UUID> ids = jdbc.sql("SELECT id FROM question_import WHERE bank_id = ? ORDER BY created_at DESC LIMIT 50").param(bankId).query(UUID.class).list();
        return ids.stream().map(this::get).toList();
    }

    private static String name(String n) {
        if (n == null) return "import";
        String s = n.replaceAll("[^A-Za-z0-9._ -]", "_");
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}
