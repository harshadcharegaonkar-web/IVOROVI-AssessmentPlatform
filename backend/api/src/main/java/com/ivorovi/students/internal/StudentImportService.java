package com.ivorovi.students.internal;

import static com.ivorovi.students.internal.StudentAccess.actor;
import static com.ivorovi.students.internal.StudentAccess.conflict;
import static com.ivorovi.students.internal.StudentAccess.tenant;

import com.ivorovi.platform.audit.AuditLog;
import com.ivorovi.platform.audit.AuditOutcome;
import com.ivorovi.platform.config.DeploymentProperties;
import com.ivorovi.platform.ids.Ids;
import com.ivorovi.platform.outbox.Outbox;
import com.ivorovi.platform.scanning.MalwareScanner;
import com.ivorovi.platform.security.Authz;
import com.ivorovi.platform.security.Permission;
import com.ivorovi.platform.storage.ObjectStorage;
import com.ivorovi.platform.tabular.TabularFile;
import com.ivorovi.platform.tabular.TabularFormatException;
import com.ivorovi.platform.tabular.TabularLimits;
import com.ivorovi.platform.web.ApiException;
import com.ivorovi.students.internal.StudentImportEngine.Decision;
import com.ivorovi.students.internal.StudentImportEngine.Issue;
import com.ivorovi.students.internal.StudentImportEngine.MatchCategory;
import com.ivorovi.students.internal.StudentImportEngine.RowStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Student bulk import (increment 1.6): upload → column mapping → validation (background) → duplicate decisions →
 * preview → explicit confirmation → processing (background) → result. One job imports one CSV/XLSX file into one
 * university or college. Needs {@code student.import} and {@code student.manage} at the institution; personal and
 * guardian columns also need {@code student.personal.read}. Every step is audited without personal values.
 */
@Service
public class StudentImportService {

    public enum JobStatus { UPLOADED, QUEUED, VALIDATING, READY, PROCESSING, COMPLETED, PARTIALLY_COMPLETED, FAILED, CANCELLED }
    public enum Phase { VALIDATE, IMPORT }
    public enum Mode { ALL_OR_NOTHING, VALID_ROWS_ONLY }
    public enum MappingSource { SAVED, AUTO, MANUAL, NONE }

    static final String IMPORT = Permission.STUDENT_IMPORT.key();

    // ---------------------------------------------------------------------------------------------------- views

    @Schema(name = "StudentImportMappingEntry")
    public record MappingEntry(int column, String header, String field, MappingSource source, String note) {}

    @Schema(name = "StudentImportCounts")
    public record Counts(int total, int valid, int warnings, int invalid, int duplicates, int potentialDuplicates, int conflicts, int unresolved,
                         int toCreate, int toUpdate) {}

    @Schema(name = "StudentImportProgress")
    public record Progress(int total, int processed, int imported, int updated, int skipped, int failed, int percent) {}

    @Schema(name = "StudentImportAbilities")
    public record Abilities(boolean map, boolean decide, boolean confirm, boolean cancel, boolean retry, boolean downloadSource, boolean viewValues) {}

    @Schema(name = "StudentImportJob")
    public record JobView(UUID id, UUID institutionId, String institutionName, String fileName, String fileFormat, long fileSize, String fileSha256,
                          String encoding, String delimiter, String sheetName, String scanStatus, List<String> headers, List<MappingEntry> mapping,
                          List<String> unmappedColumns, JobStatus status, Phase phase, Mode mode, Counts counts, Progress progress, boolean cancelRequested,
                          String failureKind, String failureMessage, boolean rolledBack, int attempts, UUID previousJobId, String createdByName,
                          Instant createdAt, Instant validatedAt, String confirmedByName, Instant confirmedAt, Instant startedAt, Instant finishedAt,
                          boolean sourceAvailable, Instant purgeAfter, long version, Abilities can) {}

    @Schema(name = "StudentImportSummary")
    public record JobSummary(UUID id, String fileName, UUID institutionId, String institutionName, String createdByName, Instant createdAt, Mode mode,
                             JobStatus status, int totalRows, int succeededRows, int failedRows, int skippedRows, int processedRows, int progressTotal,
                             Instant finishedAt) {}

    @Schema(name = "StudentImportIssue")
    public record IssueView(String severity, String category, String code, String field, String column, Integer columnNo, String message, String hint) {}

    @Schema(name = "StudentImportPlan")
    public record PlanView(boolean student, boolean admission, boolean enrolment, int guardians, boolean activate) {}

    @Schema(name = "StudentImportRow")
    public record RowView(int rowNo, String displayName, String studentNo, String registrationNo, RowStatus status, List<IssueView> issues,
                          MatchCategory matchCategory, UUID matchStudentId, String matchStudentNo, Integer matchRowNo, String matchReason, PlanView plan,
                          Decision decision, String decidedByName, Instant decidedAt, List<Decision> allowedDecisions, String outcome, String outcomeCode,
                          String outcomeMessage, UUID studentId, Map<String, String> values) {}

    @Schema(name = "StudentImportPreviewRows")
    public record SampleRows(List<String> headers, List<Sample> rows, boolean masked) {}

    @Schema(name = "StudentImportSampleRow")
    public record Sample(int rowNo, List<String> cells) {}

    @Schema(name = "StudentImportField")
    public record FieldView(String key, String label, String section, String sectionLabel, String kind, boolean personal, boolean required,
                            String requiredWhen, String hint, String example, List<String> aliases) {}

    @Schema(name = "StudentImportSavedMapping")
    public record SavedMapping(UUID id, UUID institutionId, String institutionName, String name, String description, List<SavedColumn> columns,
                               String updatedByName, Instant updatedAt, long version) {}

    @Schema(name = "StudentImportSavedColumn")
    public record SavedColumn(String header, String field) {}

    @Schema(name = "StudentImportSettings")
    public record SettingsView(UUID institutionId, String institutionName, boolean matchPersonalEmail, boolean matchInstitutionalEmail, boolean matchNameDob,
                               boolean matchNameMobile, boolean requireDateOfBirth, boolean configured, long version) {}

    @Schema(name = "StudentImportOverview")
    public record Overview(long jobsInProgress, long awaitingAction, long completedLast30Days, long failedLast30Days, long studentsImportedLast30Days,
                           long rowsFailedLast30Days, List<JobSummary> recent, Limits limits) {}

    @Schema(name = "StudentImportLimits")
    public record Limits(long maxFileBytes, int maxRows, int maxColumns, int retentionDays) {}

    public record Page<T>(List<T> items, long total) {}

    public record ColumnCommand(int column, String field) {}

    public record SettingsCommand(boolean matchPersonalEmail, boolean matchInstitutionalEmail, boolean matchNameDob, boolean matchNameMobile,
                                  boolean requireDateOfBirth, Long version) {}

    // ---------------------------------------------------------------------------------------------------- wiring

    private final JdbcClient jdbc;
    private final StudentAccess access;
    private final Authz authz;
    private final AuditLog audit;
    private final Outbox outbox;
    private final ObjectStorage storage;
    private final ObjectProvider<MalwareScanner> scanner;
    private final DeploymentProperties deployment;
    private final StudentImportEngine engine;
    private final StudentImportProperties props;
    private final JsonMapper json;
    private final org.springframework.transaction.support.TransactionTemplate separate;
    private final org.springframework.context.ApplicationEventPublisher events;

    StudentImportService(JdbcClient jdbc, StudentAccess access, Authz authz, AuditLog audit, Outbox outbox, ObjectStorage storage,
                         ObjectProvider<MalwareScanner> scanner, DeploymentProperties deployment, StudentImportEngine engine, StudentImportProperties props,
                         JsonMapper json, org.springframework.transaction.PlatformTransactionManager txManager,
                         org.springframework.context.ApplicationEventPublisher events) {
        this.events = events;
        this.separate = new org.springframework.transaction.support.TransactionTemplate(txManager);
        this.separate.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.jdbc = jdbc;
        this.access = access;
        this.authz = authz;
        this.audit = audit;
        this.outbox = outbox;
        this.storage = storage;
        this.scanner = scanner;
        this.deployment = deployment;
        this.engine = engine;
        this.props = props;
        this.json = json;
    }

    // ---------------------------------------------------------------------------------------------------- access

    /** The caller may run imports for the institution: student.import and student.manage there. */
    void requireImporter(UUID institutionId) {
        if (!authz.hasInOrgUnit(IMPORT, institutionId) || !authz.hasInOrgUnit(StudentAccess.MANAGE, institutionId)) {
            throw new AccessDeniedException("Missing permission " + IMPORT);
        }
    }

    boolean personalAllowed(UUID institutionId) {
        return authz.hasInOrgUnit(StudentAccess.PERSONAL, institutionId);
    }

    void requireAnyImporter() {
        if (!authz.hasAnywhere(IMPORT)) throw new AccessDeniedException("Missing permission " + IMPORT);
    }

    /** Institutions where the caller may import (null = all). */
    private Optional<List<String>> importRoots() {
        if (authz.has(IMPORT)) return Optional.empty();
        List<String> roots = new ArrayList<>();
        for (UUID id : authz.orgUnitScopesWith(IMPORT)) access.unit(id).map(StudentAccess.Unit::path).ifPresent(roots::add);
        return Optional.of(roots);
    }

    // ---------------------------------------------------------------------------------------------------- catalogue

    @Transactional(readOnly = true)
    public List<FieldView> fields(UUID institutionId) {
        requireAnyImporter();
        boolean manual = true, dob = false;
        if (institutionId != null) {
            StudentImportEngine.Refs refs = engine.loadRefs(institutionId);
            manual = refs.numberingMode == StudentNumbering.Mode.MANUAL;
            dob = engine.settings(institutionId).requireDateOfBirth();
        }
        List<FieldView> out = new ArrayList<>();
        for (ImportField f : ImportField.values()) {
            boolean required = f == ImportField.FIRST_NAME || (f == ImportField.STUDENT_NO && manual) || (f == ImportField.DATE_OF_BIRTH && dob);
            String when = switch (f) {
                case ADMISSION_NO, ADMISSION_DATE -> "Required with admission or enrolment details";
                case COURSE -> "Required with admission details unless a batch is given";
                case BATCH -> "Required to enrol the student";
                case GUARDIAN1_NAME, GUARDIAN1_RELATIONSHIP -> "Required with any guardian 1 detail";
                case GUARDIAN2_NAME, GUARDIAN2_RELATIONSHIP -> "Required with any guardian 2 detail";
                case PREVIOUS_INSTITUTION_NAME -> "Required for transfer admissions";
                case STUDENT_NO -> manual ? null : "Leave empty for new students (IDs are generated)";
                default -> null;
            };
            out.add(new FieldView(f.key(), f.label, f.section.name(), f.section.label, f.kind.name(), f.personal, required, when, f.hint, f.example, f.aliases()));
        }
        return out;
    }

    public Limits limits() {
        return new Limits(props.maxFileBytes(), props.maxRows(), 100, props.retentionDays());
    }

    // ---------------------------------------------------------------------------------------------------- upload

    @Transactional
    public JobView upload(UUID institutionId, String fileName, byte[] content, String importKey, UUID mappingTemplateId) {
        if (institutionId == null) throw ApiException.badRequest("invalid_institutionId", "Choose the university or college.");
        StudentAccess.Unit inst = access.unit(institutionId).filter(u -> Set.of("UNIVERSITY", "COLLEGE").contains(u.type()))
                .orElseThrow(() -> StudentAccess.invalid("invalid_institutionId", "Choose a university or college of your organization."));
        requireImporter(inst.id());
        if (!"ACTIVE".equals(inst.status())) throw StudentAccess.invalid("inactive_institutionId", inst.name() + " is inactive.");
        MalwareScanner sc = scanner.getIfAvailable();
        if (!DocumentService.uploadsAllowed(deployment.environment(), sc != null)) {
            auditFailure("student.import.upload_refused", null, Map.of("reason", "no_malware_scanner"));
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "import_scanning_unavailable",
                    "Student imports are disabled until the malware scanning service is available.");
        }
        if (content == null || content.length == 0) throw ApiException.badRequest("empty_file", "Choose a file.");
        if (content.length > props.maxFileBytes()) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "file_too_large", "Files can be at most " + (props.maxFileBytes() / (1024 * 1024)) + " MB.");
        }
        String key = importKey == null || importKey.isBlank() ? null : importKey.trim();
        if (key != null && !key.matches("^[A-Za-z0-9_-]{8,100}$")) throw ApiException.badRequest("invalid_importKey", "Use 8–100 letters, digits, - or _.");
        String sha = sha256(content);
        if (key != null) {
            List<Map<String, Object>> same = jdbc.sql("SELECT id, file_sha256, institution_id FROM student_import_job WHERE import_key = ?").param(key).query().listOfRows();
            if (!same.isEmpty()) {
                Map<String, Object> row = same.getFirst();
                if (!sha.equals(row.get("file_sha256")) || !institutionId.equals(row.get("institution_id"))) {
                    auditFailure("student.import.key_reused", String.valueOf(row.get("id")), Map.of("importKey", key));
                    throw conflict("import_key_reused", "This import identifier was already used for a different file. Start a new import.");
                }
                return job((UUID) row.get("id"));
            }
        }
        String safeName = safeName(fileName);
        TabularFile.Format format;
        try {
            format = TabularFile.detect(safeName, Arrays.copyOf(content, Math.min(content.length, 8192)));
        } catch (TabularFormatException e) {
            auditFailure("student.import.rejected", null, Map.of("reason", e.code(), "sizeBytes", String.valueOf(content.length)));
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, e.code(), e.getMessage());
        }
        String scanStatus = "NOT_SCANNED";
        if (sc != null) {
            MalwareScanner.Result r = sc.scan(content);
            if (r.verdict() == MalwareScanner.Verdict.INFECTED) {
                auditFailure("student.import.rejected", null, Map.of("reason", "malware_detected", "scanner", sc.name(), "sha256", sha));
                throw StudentAccess.invalid("malware_detected", "The malware scanner rejected this file. It was not stored.");
            }
            if (r.verdict() != MalwareScanner.Verdict.CLEAN) {
                auditFailure("student.import.rejected", null, Map.of("reason", "scan_failed", "scanner", sc.name()));
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "scan_failed", "The malware scan could not be completed. Try again in a few minutes.");
            }
            scanStatus = "CLEAN";
        }
        TabularFile.Description description;
        int rows;
        Path tmp = null;
        try {
            tmp = Files.createTempFile("ivorovi-import-", format == TabularFile.Format.CSV ? ".csv" : ".xlsx");
            Files.write(tmp, content);
            TabularFile.Reader reader = TabularFile.open(format, tmp, TabularLimits.of(props.maxRows()));
            description = reader.describe();
            int[] count = {0};
            reader.rows(r -> { count[0]++; return true; });
            rows = count[0];
        } catch (TabularFormatException e) {
            auditFailure("student.import.rejected", null, Map.of("reason", e.code(), "sha256", sha));
            throw StudentAccess.invalid(e.code(), e.getMessage());
        } catch (IOException e) {
            throw StudentAccess.invalid("malformed_file", "The file could not be read.");
        } finally {
            deleteQuietly(tmp);
        }
        if (rows == 0) {
            auditFailure("student.import.rejected", null, Map.of("reason", "no_rows", "sha256", sha));
            throw StudentAccess.invalid("no_rows", "The file has column headings but no data rows.");
        }
        List<String> headers = headerNames(description.headers());
        UUID id = Ids.newId();
        String path = "imports/students/" + id + "/source." + (format == TabularFile.Format.CSV ? "csv" : "xlsx");
        storage.put(path, new ByteArrayInputStream(content), content.length,
                format == TabularFile.Format.CSV ? "text/csv" : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        UUID previous = jdbc.sql("SELECT id FROM student_import_job WHERE file_sha256 = ? AND status IN ('COMPLETED', 'PARTIALLY_COMPLETED') ORDER BY created_at DESC LIMIT 1")
                .param(sha).query(UUID.class).optional().orElse(null);
        List<MappingEntry> suggestion = suggest(headers, mappingTemplateId == null ? null : savedColumns(mappingTemplateId));
        try {
            jdbc.sql("""
                    INSERT INTO student_import_job (id, tenant_id, institution_id, import_key, file_name, file_format, file_size, file_sha256, object_path, encoding,
                        delimiter, sheet_name, scan_status, headers, mapping, mapping_template_id, status, total_rows, previous_job_id, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, 'UPLOADED', ?, ?, ?)""")
                    .params(id, tenant(), institutionId, key, safeName, format.name(), content.length, sha, path, description.encoding(), description.delimiter(),
                            description.sheetName(), scanStatus, json.writeValueAsString(headers), json.writeValueAsString(suggestion), mappingTemplateId, rows,
                            previous, actor()).update();
        } catch (DuplicateKeyException e) {
            throw conflict("import_key_reused", "This import identifier is already in use. Start a new import.");
        }
        Map<String, String> d = new LinkedHashMap<>();
        d.put("institutionId", institutionId.toString());
        d.put("format", format.name());
        d.put("sizeBytes", String.valueOf(content.length));
        d.put("sha256", sha);
        d.put("rows", String.valueOf(rows));
        d.put("scan", scanStatus);
        audit.recordSuccess("student.import.uploaded", "student_import", id.toString(), d);
        return job(id);
    }

    static List<String> headerNames(List<String> raw) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            String h = raw.get(i) == null ? "" : raw.get(i).trim();
            out.add(h.isEmpty() ? "Column " + excelColumn(i) : h.length() > 120 ? h.substring(0, 120) : h);
        }
        return out;
    }

    static String excelColumn(int index) {
        StringBuilder s = new StringBuilder();
        int n = index + 1;
        while (n > 0) { int r = (n - 1) % 26; s.insert(0, (char) ('A' + r)); n = (n - 1) / 26; }
        return s.toString();
    }

    /** Automatic mapping: saved template first, then exact key/label/alias matches; ambiguous headers stay unmapped. */
    static List<MappingEntry> suggest(List<String> headers, List<SavedColumn> saved) {
        Map<String, String> savedByHeader = new HashMap<>();
        if (saved != null) for (SavedColumn c : saved) savedByHeader.put(ImportField.normalise(c.header()), c.field());
        Map<ImportField, List<Integer>> claims = new LinkedHashMap<>();
        List<ImportField> chosen = new ArrayList<>();
        List<MappingSource> sources = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (int i = 0; i < headers.size(); i++) {
            String h = headers.get(i);
            ImportField savedField = ImportField.ofKey(savedByHeader.get(ImportField.normalise(h)));
            if (savedField != null) {
                chosen.add(savedField); sources.add(MappingSource.SAVED); notes.add(null);
            } else {
                List<ImportField> c = ImportField.candidates(h);
                if (c.size() == 1) { chosen.add(c.getFirst()); sources.add(MappingSource.AUTO); notes.add(null); }
                else if (c.size() > 1) {
                    chosen.add(null); sources.add(MappingSource.NONE);
                    notes.add("Ambiguous: could be " + String.join(" or ", c.stream().map(f -> f.label).toList()) + ". Choose the field.");
                } else { chosen.add(null); sources.add(MappingSource.NONE); notes.add(null); }
            }
            if (chosen.getLast() != null) claims.computeIfAbsent(chosen.getLast(), k -> new ArrayList<>()).add(i);
        }
        List<MappingEntry> out = new ArrayList<>();
        for (int i = 0; i < headers.size(); i++) {
            ImportField f = chosen.get(i);
            if (f != null && claims.get(f).size() > 1) {
                out.add(new MappingEntry(i, headers.get(i), null, MappingSource.NONE, "Ambiguous: several columns could be " + f.label + ". Choose which one to use."));
            } else {
                out.add(new MappingEntry(i, headers.get(i), f == null ? null : f.key(), sources.get(i), notes.get(i)));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------------------- job views

    record JobRow(UUID id, UUID institutionId, String institutionName, String institutionPath, String fileName, String fileFormat, long fileSize, String sha,
                  String objectPath, String encoding, String delimiter, String sheetName, String scanStatus, String headersJson, String mappingJson,
                  JobStatus status, Phase phase, Mode mode, int[] counts, int[] progress, boolean cancelRequested, String failureKind, String failureMessage,
                  boolean rolledBack, int attempts, UUID previousJobId, UUID createdBy, String createdByName, Instant createdAt, Instant validatedAt,
                  UUID confirmedBy, String confirmedByName, Instant confirmedAt, Instant startedAt, Instant finishedAt, Instant sourcePurgedAt,
                  UUID phaseRequestedBy, long version) {}

    private static final String JOB_SELECT = """
            SELECT j.*, ou.name AS institution_name, ou.path::text AS institution_path, cu.display_name AS created_by_name, fu.display_name AS confirmed_by_name
            FROM student_import_job j JOIN org_unit ou ON ou.id = j.institution_id LEFT JOIN user_account cu ON cu.id = j.created_by
            LEFT JOIN user_account fu ON fu.id = j.confirmed_by""";

    Optional<JobRow> jobRow(UUID id, boolean lock) {
        if (lock) jdbc.sql("SELECT 1 FROM student_import_job WHERE id = ? FOR UPDATE").param(id).query(Integer.class).optional();
        return jdbc.sql(JOB_SELECT + " WHERE j.id = ?").param(id).query(StudentImportService::mapJob).optional();
    }

    static JobRow mapJob(ResultSet rs, int n) throws SQLException {
        return new JobRow(rs.getObject("id", UUID.class), rs.getObject("institution_id", UUID.class), rs.getString("institution_name"),
                rs.getString("institution_path"), rs.getString("file_name"), rs.getString("file_format"), rs.getLong("file_size"), rs.getString("file_sha256"),
                rs.getString("object_path"), rs.getString("encoding"), rs.getString("delimiter"), rs.getString("sheet_name"), rs.getString("scan_status"),
                rs.getString("headers"), rs.getString("mapping"), JobStatus.valueOf(rs.getString("status")),
                rs.getString("phase") == null ? null : Phase.valueOf(rs.getString("phase")), rs.getString("mode") == null ? null : Mode.valueOf(rs.getString("mode")),
                new int[] {rs.getInt("total_rows"), rs.getInt("valid_rows"), rs.getInt("warning_rows"), rs.getInt("invalid_rows"), rs.getInt("duplicate_rows"),
                        rs.getInt("potential_duplicate_rows"), rs.getInt("conflict_rows"), rs.getInt("unresolved_rows"), rs.getInt("to_create_rows"), rs.getInt("to_update_rows")},
                new int[] {rs.getInt("progress_total"), rs.getInt("processed_rows"), rs.getInt("imported_rows"), rs.getInt("updated_rows"), rs.getInt("skipped_rows"),
                        rs.getInt("failed_rows")},
                rs.getBoolean("cancel_requested"), rs.getString("failure_kind"), rs.getString("failure_message"), rs.getBoolean("rolled_back"), rs.getInt("attempts"),
                rs.getObject("previous_job_id", UUID.class), rs.getObject("created_by", UUID.class), rs.getString("created_by_name"),
                StudentService.instant(rs, "created_at"), StudentService.instant(rs, "validated_at"), rs.getObject("confirmed_by", UUID.class),
                rs.getString("confirmed_by_name"), StudentService.instant(rs, "confirmed_at"), StudentService.instant(rs, "started_at"),
                StudentService.instant(rs, "finished_at"), StudentService.instant(rs, "source_purged_at"), rs.getObject("phase_requested_by", UUID.class),
                rs.getLong("version"));
    }

    /** Loads a job the caller may see (importer at its institution). */
    JobRow visible(UUID id, boolean lock) {
        JobRow j = jobRow(id, lock).orElseThrow(() -> ApiException.notFound("Import"));
        if (!authz.hasInOrgUnit(IMPORT, j.institutionId())) {
            audit.recordDenied("student.import.access_denied", Map.of("importId", id.toString()));
            throw new AccessDeniedException("Import outside scope");
        }
        return j;
    }

    @Transactional(readOnly = true)
    public JobView job(UUID id) {
        return view(visible(id, false));
    }

    JobView view(JobRow j) {
        List<String> headers = json.readValue(j.headersJson(), new TypeReference<List<String>>() {});
        List<MappingEntry> mapping = j.mappingJson() == null ? List.of() : json.readValue(j.mappingJson(), new TypeReference<List<MappingEntry>>() {});
        List<String> unmapped = mapping.stream().filter(m -> m.field() == null).map(MappingEntry::header).toList();
        int[] c = j.counts(), p = j.progress();
        int percent = p[0] == 0 ? (EnumSet.of(JobStatus.COMPLETED, JobStatus.PARTIALLY_COMPLETED).contains(j.status()) ? 100 : 0) : Math.min(100, p[1] * 100 / p[0]);
        boolean manage = authz.hasInOrgUnit(IMPORT, j.institutionId()) && authz.hasInOrgUnit(StudentAccess.MANAGE, j.institutionId());
        boolean retry = manage && retryable(j);
        Abilities can = new Abilities(manage && EnumSet.of(JobStatus.UPLOADED, JobStatus.READY).contains(j.status()),
                manage && j.status() == JobStatus.READY, manage && j.status() == JobStatus.READY,
                manage && EnumSet.of(JobStatus.UPLOADED, JobStatus.QUEUED, JobStatus.READY, JobStatus.VALIDATING, JobStatus.PROCESSING).contains(j.status()) && !j.cancelRequested(),
                retry, "CLEAN".equals(j.scanStatus()) && j.sourcePurgedAt() == null && personalAllowed(j.institutionId()), personalAllowed(j.institutionId()));
        Instant purgeAfter = j.finishedAt() == null ? null : j.finishedAt().plus(java.time.Duration.ofDays(props.retentionDays()));
        return new JobView(j.id(), j.institutionId(), j.institutionName(), j.fileName(), j.fileFormat(), j.fileSize(), j.sha(), j.encoding(), j.delimiter(), j.sheetName(),
                j.scanStatus(), headers, mapping, unmapped, j.status(), j.phase(), j.mode(), new Counts(c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8], c[9]),
                new Progress(p[0], p[1], p[2], p[3], p[4], p[5], percent), j.cancelRequested(), j.failureKind(), j.failureMessage(), j.rolledBack(), j.attempts(),
                j.previousJobId(), j.createdByName(), j.createdAt(), j.validatedAt(), j.confirmedByName(), j.confirmedAt(), j.startedAt(), j.finishedAt(),
                j.sourcePurgedAt() == null, purgeAfter, j.version(), can);
    }

    static boolean retryable(JobRow j) {
        if (j.status() == JobStatus.FAILED) return "SYSTEM".equals(j.failureKind());
        return false;
    }

    // ---------------------------------------------------------------------------------------------------- preview of the file

    /** Not read-only: viewing imported values is audited. */
    @Transactional
    public SampleRows sample(UUID id, int max) {
        JobRow j = visible(id, false);
        List<String> headers = json.readValue(j.headersJson(), new TypeReference<List<String>>() {});
        if (j.sourcePurgedAt() != null) return new SampleRows(headers, List.of(), true);
        boolean personal = personalAllowed(j.institutionId());
        Set<Integer> visibleCols = new java.util.HashSet<>();
        if (!personal) {
            List<MappingEntry> mapping = j.mappingJson() == null ? List.of() : json.readValue(j.mappingJson(), new TypeReference<List<MappingEntry>>() {});
            for (MappingEntry m : mapping) {
                ImportField f = ImportField.ofKey(m.field());
                if (f != null && !f.personal) visibleCols.add(m.column());
            }
        }
        List<Sample> rows = new ArrayList<>();
        withSource(j, reader -> reader.rows(r -> {
            List<String> cells = new ArrayList<>();
            for (int i = 0; i < headers.size(); i++) {
                TabularFile.Cell c = r.cell(i);
                String v = c.formula() ? "=(formula)" : c.text();
                cells.add(personal || visibleCols.contains(i) ? v : v == null ? null : "•••");
            }
            rows.add(new Sample(r.number(), cells));
            return rows.size() < Math.max(1, Math.min(max, 20));
        }));
        if (personal && !rows.isEmpty()) audit.recordSuccess("student.import.values_viewed", "student_import", j.id().toString(), Map.of("rows", String.valueOf(rows.size()), "view", "sample"));
        return new SampleRows(headers, rows, !personal);
    }

    interface SourceWork {
        void run(TabularFile.Reader reader) throws IOException;
    }

    /** Copies the stored source file to a private temporary file and opens a streaming reader on it. */
    void withSource(JobRow j, SourceWork work) {
        if (j.objectPath() == null || j.sourcePurgedAt() != null) throw conflict("source_purged", "The source file was deleted after the retention period.");
        byte[] content = storage.get(j.objectPath()).orElseThrow(() -> conflict("source_missing", "The source file could not be found in storage."));
        Path tmp = null;
        try {
            tmp = Files.createTempFile("ivorovi-import-", "XLSX".equals(j.fileFormat()) ? ".xlsx" : ".csv");
            Files.write(tmp, content);
            TabularFile.Reader reader = TabularFile.open(TabularFile.Format.valueOf(j.fileFormat()), tmp, TabularLimits.of(props.maxRows()));
            reader.describe();
            work.run(reader);
        } catch (IOException e) {
            throw new IllegalStateException("Import source could not be read", e);
        } finally {
            deleteQuietly(tmp);
        }
    }

    // ---------------------------------------------------------------------------------------------------- mapping

    @Transactional
    public JobView saveMapping(UUID id, List<ColumnCommand> columns, long version, String saveAsName, String saveAsDescription, boolean saveForOrganization) {
        JobRow j = visible(id, true);
        requireImporter(j.institutionId());
        StudentAccess.version(version, j.version());
        if (!EnumSet.of(JobStatus.UPLOADED, JobStatus.READY, JobStatus.FAILED).contains(j.status()) || (j.status() == JobStatus.FAILED && j.phase() == Phase.IMPORT)) {
            throw conflict("import_not_editable", "The mapping can be changed only before the import is confirmed.");
        }
        List<String> headers = json.readValue(j.headersJson(), new TypeReference<List<String>>() {});
        Map<Integer, ImportField> mapping = validateMapping(j.institutionId(), headers, columns);
        List<MappingEntry> previous = j.mappingJson() == null ? List.of() : json.readValue(j.mappingJson(), new TypeReference<List<MappingEntry>>() {});
        List<MappingEntry> entries = new ArrayList<>();
        for (int i = 0; i < headers.size(); i++) {
            ImportField f = mapping.get(i);
            int col = i;
            MappingEntry before = previous.stream().filter(m -> m.column() == col).findFirst().orElse(null);
            MappingSource src = f == null ? MappingSource.NONE
                    : before != null && f.key().equals(before.field()) && before.source() != MappingSource.MANUAL ? before.source() : MappingSource.MANUAL;
            entries.add(new MappingEntry(i, headers.get(i), f == null ? null : f.key(), src, null));
        }
        UUID templateId = null;
        if (saveAsName != null && !saveAsName.isBlank()) {
            List<SavedColumn> saved = entries.stream().filter(e -> e.field() != null).map(e -> new SavedColumn(e.header(), e.field())).toList();
            templateId = createMapping(saveForOrganization ? null : j.institutionId(), saveAsName, saveAsDescription, saved);
        }
        jdbc.sql("DELETE FROM student_import_row WHERE job_id = ?").param(id).update();
        jdbc.sql("""
                UPDATE student_import_job SET mapping = CAST(? AS jsonb), mapping_template_id = coalesce(?, mapping_template_id), status = 'QUEUED', phase = 'VALIDATE',
                    failure_kind = NULL, failure_message = NULL, valid_rows = 0, warning_rows = 0, invalid_rows = 0, duplicate_rows = 0, potential_duplicate_rows = 0,
                    conflict_rows = 0, unresolved_rows = 0, to_create_rows = 0, to_update_rows = 0, progress_total = total_rows, processed_rows = 0, attempts = 0,
                    lease_owner = NULL, lease_until = NULL, phase_requested_by = ?, updated_at = now(), version = version + 1 WHERE id = ?""")
                .params(json.writeValueAsString(entries), templateId, actor(), id).update();
        Map<String, String> d = new LinkedHashMap<>();
        d.put("mappedColumns", String.valueOf(mapping.size()));
        d.put("fields", String.join(",", mapping.values().stream().map(ImportField::key).sorted().toList()));
        audit.recordSuccess("student.import.mapped", "student_import", id.toString(), d);
        enqueue(id, Phase.VALIDATE);
        return view(jobRow(id, false).orElseThrow());
    }

    Map<Integer, ImportField> validateMapping(UUID institutionId, List<String> headers, List<ColumnCommand> columns) {
        if (columns == null) throw ApiException.badRequest("invalid_mapping", "Map the columns.");
        Map<Integer, ImportField> mapping = new LinkedHashMap<>();
        Set<ImportField> used = EnumSet.noneOf(ImportField.class);
        for (ColumnCommand c : columns) {
            if (c.field() == null || c.field().isBlank()) continue;
            if (c.column() < 0 || c.column() >= headers.size()) throw ApiException.badRequest("invalid_mapping", "Unknown column " + (c.column() + 1) + ".");
            ImportField f = ImportField.ofKey(c.field());
            if (f == null) throw ApiException.badRequest("invalid_mapping", "Unknown field " + c.field() + ".");
            if (!used.add(f)) throw StudentAccess.invalid("duplicate_field_mapping", f.label + " is mapped to more than one column. Map each field once.");
            if (mapping.put(c.column(), f) != null) throw ApiException.badRequest("invalid_mapping", "Column " + (c.column() + 1) + " is mapped twice.");
        }
        List<String> missing = new ArrayList<>();
        if (!used.contains(ImportField.FIRST_NAME)) missing.add(ImportField.FIRST_NAME.label);
        StudentImportEngine.Refs refs = engine.loadRefs(institutionId);
        if (refs.numberingMode == StudentNumbering.Mode.MANUAL && !used.contains(ImportField.STUDENT_NO)) missing.add(ImportField.STUDENT_NO.label);
        if (engine.settings(institutionId).requireDateOfBirth() && !used.contains(ImportField.DATE_OF_BIRTH)) missing.add(ImportField.DATE_OF_BIRTH.label);
        boolean academic = used.stream().anyMatch(f -> ImportField.ADMISSION_FIELDS.contains(f) || ImportField.ENROLMENT_FIELDS.contains(f));
        if (academic) {
            if (!used.contains(ImportField.ADMISSION_NO)) missing.add(ImportField.ADMISSION_NO.label);
            if (!used.contains(ImportField.ADMISSION_DATE)) missing.add(ImportField.ADMISSION_DATE.label);
            if (!used.contains(ImportField.COURSE) && !used.contains(ImportField.BATCH)) missing.add(ImportField.COURSE.label + " or " + ImportField.BATCH.label);
        }
        for (int n = 1; n <= 2; n++) {
            int g = n;
            if (used.stream().anyMatch(f -> f.guardian(g))) {
                if (!used.contains(ImportField.valueOf("GUARDIAN" + n + "_NAME"))) missing.add("Guardian " + n + " name");
                if (!used.contains(ImportField.valueOf("GUARDIAN" + n + "_RELATIONSHIP"))) missing.add("Guardian " + n + " relationship");
            }
        }
        if (!missing.isEmpty()) throw StudentAccess.invalid("required_fields_unmapped", "Map the required fields: " + String.join(", ", missing) + ".");
        if (used.stream().anyMatch(f -> f.personal) && !personalAllowed(institutionId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "personal_data_not_permitted",
                    "You need 'View student personal details' to import personal or guardian information. Unmap those columns.");
        }
        return mapping;
    }

    /** Announced after commit to the local worker (immediate start) and through the outbox/RabbitMQ (any instance). */
    public record Queued(UUID tenantId, UUID jobId) {}

    void enqueue(UUID jobId, Phase phase) {
        outbox.append("student_import", jobId.toString(), "student.import.queued", Map.of("jobId", jobId.toString(), "phase", phase.name()));
        events.publishEvent(new Queued(tenant(), jobId));
    }

    // ---------------------------------------------------------------------------------------------------- rows and decisions

    /** Not read-only: viewing imported values is audited. */
    @Transactional
    public Page<RowView> rows(UUID id, String filter, int page, int size) {
        JobRow j = visible(id, false);
        boolean personal = personalAllowed(j.institutionId());
        String where = switch (filter == null ? "ALL" : filter.toUpperCase(Locale.ROOT)) {
            case "ISSUES" -> " AND (r.validation_status IN ('INVALID', 'WARNING', 'CONFLICT') OR jsonb_array_length(r.issues) > 0)";
            case "ERRORS" -> " AND r.validation_status IN ('INVALID', 'CONFLICT')";
            case "INVALID" -> " AND r.validation_status = 'INVALID'";
            case "WARNINGS" -> " AND jsonb_path_exists(r.issues, '$[*] ? (@.severity == \"WARNING\")')";
            case "DUPLICATES" -> " AND r.match_category IS NOT NULL";
            case "DECISIONS" -> " AND r.validation_status = 'NEEDS_DECISION'";
            case "UNRESOLVED" -> " AND r.validation_status = 'NEEDS_DECISION' AND r.decision IS NULL";
            case "CONFLICTS" -> " AND r.validation_status = 'CONFLICT'";
            case "VALID" -> " AND r.validation_status IN ('VALID', 'WARNING')";
            case "FAILED" -> " AND r.outcome = 'FAILED'";
            case "IMPORTED" -> " AND r.outcome IN ('IMPORTED', 'UPDATED')";
            case "SKIPPED" -> " AND r.outcome IN ('SKIPPED', 'NOT_PROCESSED', 'ROLLED_BACK')";
            default -> "";
        };
        long total = jdbc.sql("SELECT count(*) FROM student_import_row r WHERE r.job_id = ?" + where).param(id).query(Long.class).single();
        List<RowView> items = jdbc.sql("""
                SELECT r.*, du.display_name AS decided_by_name, ms.student_no AS match_student_no, ms.scope_unit_id AS match_scope_unit_id FROM student_import_row r
                LEFT JOIN user_account du ON du.id = r.decided_by LEFT JOIN student ms ON ms.id = r.match_student_id
                WHERE r.job_id = ?""" + where + " ORDER BY r.row_no LIMIT ? OFFSET ?")
                .params(id, size, (long) page * size).query((rs, n) -> rowView(rs, j, personal)).list();
        if (personal && items.stream().anyMatch(r -> !r.values().isEmpty())) {
            audit.recordSuccess("student.import.values_viewed", "student_import", id.toString(), Map.of("rows", String.valueOf(items.size()), "view", "rows"));
        }
        return new Page<>(items, total);
    }

    RowView rowView(ResultSet rs, JobRow j, boolean personal) throws SQLException {
        RowStatus status = RowStatus.valueOf(rs.getString("validation_status"));
        MatchCategory cat = rs.getString("match_category") == null ? null : MatchCategory.valueOf(rs.getString("match_category"));
        List<IssueView> issues = json.readValue(rs.getString("issues"), new TypeReference<List<IssueView>>() {});
        StudentImportEngine.Plan plan = rs.getString("plan") == null ? null : json.readValue(rs.getString("plan"), StudentImportEngine.Plan.class);
        Map<String, String> values = Map.of();
        if (rs.getString("cells_enc") != null) {
            values = mappedValues(j, decryptCells(rs.getString("cells_enc")), personal);
        }
        // a matched student is identified only to viewers who may read that student
        UUID matchScope = rs.getObject("match_scope_unit_id", UUID.class);
        boolean matchVisible = matchScope != null && authz.hasInOrgUnit(StudentAccess.READ, matchScope);
        return new RowView(rs.getInt("row_no"), rs.getString("display_name"), rs.getString("student_no"), rs.getString("registration_no"), status, issues, cat,
                matchVisible ? rs.getObject("match_student_id", UUID.class) : null, matchVisible ? rs.getString("match_student_no") : null, (Integer) rs.getObject("match_row_no"), rs.getString("match_reason"),
                plan == null ? null : new PlanView(plan.student(), plan.admission(), plan.enrolment(), plan.guardians(), plan.activate()),
                rs.getString("decision") == null ? null : Decision.valueOf(rs.getString("decision")), rs.getString("decided_by_name"),
                StudentService.instant(rs, "decided_at"), allowedDecisions(status, cat), rs.getString("outcome"), rs.getString("outcome_code"),
                rs.getString("outcome_message"), rs.getObject("student_id", UUID.class), values);
    }

    static List<Decision> allowedDecisions(RowStatus status, MatchCategory cat) {
        if (status != RowStatus.NEEDS_DECISION || cat == null) return List.of();
        return cat == MatchCategory.EXISTING_NEW_ENROLMENT ? List.of(Decision.ADD_TO_EXISTING, Decision.SKIP) : List.of(Decision.CREATE_NEW, Decision.SKIP);
    }

    record Cells(List<String> c, List<Integer> f) {}

    Cells decryptCells(String enc) {
        String plain = access.open(enc, "student_import_row.cells");
        return plain == null ? new Cells(List.of(), List.of()) : json.readValue(plain, Cells.class);
    }

    String encryptCells(Cells cells) {
        return access.seal(json.writeValueAsString(cells), "student_import_row.cells");
    }

    Map<String, String> mappedValues(JobRow j, Cells cells, boolean personal) {
        List<MappingEntry> mapping = j.mappingJson() == null ? List.of() : json.readValue(j.mappingJson(), new TypeReference<List<MappingEntry>>() {});
        Map<String, String> out = new LinkedHashMap<>();
        for (MappingEntry m : mapping) {
            ImportField f = ImportField.ofKey(m.field());
            if (f == null) continue;
            String v = m.column() < cells.c().size() ? cells.c().get(m.column()) : null;
            if (cells.f().contains(m.column())) v = "=(formula)";
            out.put(f.key(), f.personal && !personal ? (v == null ? null : "•••") : v);
        }
        return out;
    }

    @Transactional
    public JobView decide(UUID id, List<Integer> rowNos, MatchCategory category, Decision decision, long version) {
        JobRow j = visible(id, true);
        requireImporter(j.institutionId());
        StudentAccess.version(version, j.version());
        if (j.status() != JobStatus.READY) throw conflict("import_not_editable", "Decisions can be recorded only while the import awaits confirmation.");
        if (decision == null) throw ApiException.badRequest("invalid_decision", "Choose a decision.");
        if ((rowNos == null || rowNos.isEmpty()) && category == null) throw ApiException.badRequest("invalid_rows", "Choose rows or a duplicate category.");
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE job_id = ? AND validation_status = 'NEEDS_DECISION'");
        params.add(id);
        if (rowNos != null && !rowNos.isEmpty()) {
            if (rowNos.size() > 10_000) throw ApiException.badRequest("invalid_rows", "Too many rows in one decision.");
            where.append(" AND row_no = ANY (?)");
            params.add(rowNos.toArray(Integer[]::new));
        }
        if (category != null) { where.append(" AND match_category = ?"); params.add(category.name()); }
        List<String> categories = jdbc.sql("SELECT DISTINCT match_category FROM student_import_row" + where).params(params).query(String.class).list();
        if (categories.isEmpty()) throw ApiException.notFound("Import rows needing a decision");
        for (String c : categories) {
            if (!allowedDecisions(RowStatus.NEEDS_DECISION, MatchCategory.valueOf(c)).contains(decision)) {
                throw StudentAccess.invalid("decision_not_allowed", decision == Decision.ADD_TO_EXISTING
                        ? "Only rows that match an existing student can add records to that student."
                        : "Rows that match an existing student can add their records to it or be skipped; they cannot create another student.");
            }
        }
        List<Object> up = new ArrayList<>();
        up.add(decision.name());
        up.add(actor());
        up.addAll(params);
        int n = jdbc.sql("UPDATE student_import_row SET decision = ?, decided_by = ?, decided_at = now()" + where).params(up).update();
        recount(id);
        jdbc.sql("UPDATE student_import_job SET updated_at = now(), version = version + 1 WHERE id = ?").param(id).update();
        Map<String, String> d = new LinkedHashMap<>();
        d.put("decision", decision.name());
        d.put("rows", String.valueOf(n));
        if (category != null) d.put("category", category.name());
        audit.recordSuccess("student.import.decision", "student_import", id.toString(), d);
        return view(jobRow(id, false).orElseThrow());
    }

    /** Recomputes the validation counts of a job from its rows. */
    void recount(UUID id) {
        jdbc.sql("""
                UPDATE student_import_job j SET (valid_rows, warning_rows, invalid_rows, duplicate_rows, potential_duplicate_rows, conflict_rows, unresolved_rows,
                    to_create_rows, to_update_rows) = (
                    SELECT count(*) FILTER (WHERE validation_status IN ('VALID', 'WARNING')),
                           count(*) FILTER (WHERE jsonb_path_exists(issues, '$[*] ? (@.severity == "WARNING")')),
                           count(*) FILTER (WHERE validation_status = 'INVALID'),
                           count(*) FILTER (WHERE validation_status = 'DUPLICATE'),
                           count(*) FILTER (WHERE validation_status = 'NEEDS_DECISION'),
                           count(*) FILTER (WHERE validation_status = 'CONFLICT'),
                           count(*) FILTER (WHERE validation_status = 'NEEDS_DECISION' AND decision IS NULL),
                           count(*) FILTER (WHERE validation_status IN ('VALID', 'WARNING') OR (validation_status = 'NEEDS_DECISION' AND decision = 'CREATE_NEW')),
                           count(*) FILTER (WHERE validation_status = 'NEEDS_DECISION' AND decision = 'ADD_TO_EXISTING')
                    FROM student_import_row r WHERE r.job_id = j.id)
                WHERE j.id = ?""").param(id).update();
    }

    // ---------------------------------------------------------------------------------------------------- confirm, cancel, retry

    @Transactional
    public JobView confirm(UUID id, Mode mode, long version, int expectedRows) {
        JobRow j = visible(id, true);
        requireImporter(j.institutionId());
        StudentAccess.version(version, j.version());
        if (j.status() != JobStatus.READY) throw conflict("import_not_ready", "The import can be confirmed only after validation and before processing.");
        if (mode == null) throw ApiException.badRequest("invalid_mode", "Choose the import mode.");
        int[] c = j.counts();
        int unresolved = c[7], invalid = c[3], conflicts = c[6], toCreate = c[8], toUpdate = c[9];
        if (unresolved > 0) throw conflict("decisions_pending", unresolved + " possible duplicate(s) need a decision before the import can start.");
        if (mode == Mode.ALL_OR_NOTHING && (invalid > 0 || conflicts > 0)) {
            throw conflict("all_or_nothing_blocked", "All-or-nothing imports need a file without invalid or conflicting rows (" + (invalid + conflicts)
                    + " found). Correct the file or choose 'Valid rows only'.");
        }
        if (toCreate + toUpdate == 0) throw conflict("nothing_to_import", "No row would be imported.");
        if (expectedRows != toCreate + toUpdate) {
            throw conflict("concurrent_modification", "The preview changed. Review it again before confirming.");
        }
        // rows that will not be processed get their final outcome now
        jdbc.sql("""
                UPDATE student_import_row SET outcome = 'SKIPPED', processed_at = now(), outcome_code = CASE
                        WHEN validation_status = 'INVALID' THEN 'invalid'
                        WHEN validation_status = 'CONFLICT' THEN 'conflict'
                        WHEN validation_status = 'DUPLICATE' THEN 'duplicate'
                        ELSE 'skipped_by_decision' END,
                    outcome_message = CASE
                        WHEN validation_status = 'INVALID' THEN 'Not imported: the row has validation errors.'
                        WHEN validation_status = 'CONFLICT' THEN 'Not imported: conflicting student identity or academic records.'
                        WHEN validation_status = 'DUPLICATE' THEN 'Not imported: the student already exists or the row repeats another row.'
                        ELSE 'Not imported: skipped by decision.' END
                WHERE job_id = ? AND NOT (validation_status IN ('VALID', 'WARNING') OR (validation_status = 'NEEDS_DECISION' AND decision IN ('CREATE_NEW', 'ADD_TO_EXISTING')))""")
                .param(id).update();
        int skipped = jdbc.sql("SELECT count(*) FROM student_import_row WHERE job_id = ? AND outcome = 'SKIPPED'").param(id).query(Integer.class).single();
        jdbc.sql("""
                UPDATE student_import_job SET status = 'QUEUED', phase = 'IMPORT', mode = ?, confirmed_by = ?, confirmed_at = now(), phase_requested_by = ?,
                    progress_total = ?, processed_rows = 0, imported_rows = 0, updated_rows = 0, skipped_rows = ?, failed_rows = 0, attempts = 0,
                    updated_at = now(), version = version + 1 WHERE id = ?""")
                .params(mode.name(), actor(), actor(), toCreate + toUpdate, skipped, id).update();
        Map<String, String> d = new LinkedHashMap<>();
        d.put("mode", mode.name());
        d.put("toCreate", String.valueOf(toCreate));
        d.put("toUpdate", String.valueOf(toUpdate));
        d.put("skipped", String.valueOf(skipped));
        audit.recordSuccess("student.import.confirmed", "student_import", id.toString(), d);
        enqueue(id, Phase.IMPORT);
        return view(jobRow(id, false).orElseThrow());
    }

    @Transactional
    public JobView cancel(UUID id) {
        JobRow j = visible(id, true);
        requireImporter(j.institutionId());
        switch (j.status()) {
            case UPLOADED, READY, QUEUED -> {
                if (j.status() == JobStatus.QUEUED && j.phase() == Phase.IMPORT) {
                    jdbc.sql("UPDATE student_import_row SET outcome = 'NOT_PROCESSED', outcome_code = 'cancelled', outcome_message = 'Not imported: the import was cancelled.' "
                            + "WHERE job_id = ? AND outcome = 'PENDING'").param(id).update();
                }
                jdbc.sql("UPDATE student_import_job SET status = 'CANCELLED', finished_at = now(), updated_at = now(), version = version + 1 WHERE id = ?").param(id).update();
            }
            case VALIDATING, PROCESSING -> jdbc.sql("UPDATE student_import_job SET cancel_requested = true, updated_at = now(), version = version + 1 WHERE id = ?")
                    .param(id).update();
            default -> throw conflict("import_finished", "The import has already finished.");
        }
        audit.recordSuccess("student.import.cancel_requested", "student_import", id.toString(), Map.of("status", j.status().name()));
        return view(jobRow(id, false).orElseThrow());
    }

    @Transactional
    public JobView retry(UUID id) {
        JobRow j = visible(id, true);
        requireImporter(j.institutionId());
        if (!retryable(j)) throw conflict("not_retryable", "Only imports that failed because of a system error can be retried. Correct the file and upload it again.");
        if (j.phase() == Phase.IMPORT && j.rolledBack()) {
            // all-or-nothing import that was rolled back: start over with the same decisions
            jdbc.sql("UPDATE student_import_row SET outcome = 'PENDING', outcome_code = NULL, outcome_message = NULL, student_id = NULL, processed_tx = NULL, "
                    + "prior_state = NULL, processed_at = NULL WHERE job_id = ? AND outcome IN ('ROLLED_BACK', 'FAILED', 'NOT_PROCESSED')").param(id).update();
        } else if (j.phase() == Phase.IMPORT) {
            jdbc.sql("UPDATE student_import_row SET outcome = 'PENDING', outcome_code = NULL, outcome_message = NULL, processed_at = NULL "
                    + "WHERE job_id = ? AND outcome IN ('FAILED', 'NOT_PROCESSED') AND coalesce(outcome_code, '') IN ('system_error', 'cancelled', '')").param(id).update();
        }
        jdbc.sql("""
                UPDATE student_import_job SET status = 'QUEUED', failure_kind = NULL, failure_message = NULL, rolled_back = false, attempts = 0, cancel_requested = false,
                    lease_owner = NULL, lease_until = NULL, finished_at = NULL, phase_requested_by = ?, updated_at = now(), version = version + 1 WHERE id = ?""")
                .params(actor(), id).update();
        audit.recordSuccess("student.import.retried", "student_import", id.toString(), Map.of("phase", String.valueOf(j.phase())));
        enqueue(id, j.phase() == null ? Phase.VALIDATE : j.phase());
        return view(jobRow(id, false).orElseThrow());
    }

    // ---------------------------------------------------------------------------------------------------- history and dashboard

    private static final String SUMMARY = """
            SELECT j.id, j.file_name, j.institution_id, ou.name AS institution_name, cu.display_name AS created_by_name, j.created_at, j.mode, j.status, j.total_rows,
                   j.imported_rows + j.updated_rows AS succeeded, j.failed_rows, j.skipped_rows, j.processed_rows, j.progress_total, j.finished_at
            FROM student_import_job j JOIN org_unit ou ON ou.id = j.institution_id LEFT JOIN user_account cu ON cu.id = j.created_by""";

    @Transactional(readOnly = true)
    public Page<JobSummary> history(JobStatus status, UUID institutionId, String q, int page, int size) {
        requireAnyImporter();
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (status != null) { where.append(" AND j.status = ?"); params.add(status.name()); }
        if (institutionId != null) { where.append(" AND j.institution_id = ?"); params.add(institutionId); }
        if (q != null && !q.isBlank()) {
            where.append(" AND (j.file_name ILIKE ? OR CAST(j.id AS text) LIKE ?)");
            params.add("%" + q.trim().replace("%", "\\%").replace("_", "\\_") + "%");
            params.add(q.trim().toLowerCase(Locale.ROOT) + "%");
        }
        Optional<List<String>> roots = importRoots();
        if (roots.isPresent()) {
            if (roots.get().isEmpty()) return new Page<>(List.of(), 0);
            where.append(" AND ou.path <@ CAST(? AS ltree[])");
            params.add(roots.get().toArray(String[]::new));
        }
        long total = jdbc.sql("SELECT count(*) FROM student_import_job j JOIN org_unit ou ON ou.id = j.institution_id" + where).params(params).query(Long.class).single();
        List<Object> p = new ArrayList<>(params);
        p.add(size);
        p.add((long) page * size);
        return new Page<>(jdbc.sql(SUMMARY + where + " ORDER BY j.created_at DESC, j.id LIMIT ? OFFSET ?").params(p).query(StudentImportService::summary).list(), total);
    }

    static JobSummary summary(ResultSet rs, int n) throws SQLException {
        return new JobSummary(rs.getObject("id", UUID.class), rs.getString("file_name"), rs.getObject("institution_id", UUID.class), rs.getString("institution_name"),
                rs.getString("created_by_name"), StudentService.instant(rs, "created_at"), rs.getString("mode") == null ? null : Mode.valueOf(rs.getString("mode")),
                JobStatus.valueOf(rs.getString("status")), rs.getInt("total_rows"), rs.getInt("succeeded"), rs.getInt("failed_rows"), rs.getInt("skipped_rows"),
                rs.getInt("processed_rows"), rs.getInt("progress_total"), StudentService.instant(rs, "finished_at"));
    }

    @Transactional(readOnly = true)
    public Overview overview() {
        requireAnyImporter();
        List<Object> params = new ArrayList<>();
        String scope = "";
        Optional<List<String>> roots = importRoots();
        if (roots.isPresent()) {
            if (roots.get().isEmpty()) return new Overview(0, 0, 0, 0, 0, 0, List.of(), limits());
            scope = " AND ou.path <@ CAST(? AS ltree[])";
            params.add(roots.get().toArray(String[]::new));
        }
        Map<String, Object> k = jdbc.sql("""
                SELECT count(*) FILTER (WHERE j.status IN ('QUEUED', 'VALIDATING', 'PROCESSING')) AS in_progress,
                       count(*) FILTER (WHERE j.status IN ('UPLOADED', 'READY')) AS awaiting,
                       count(*) FILTER (WHERE j.status IN ('COMPLETED', 'PARTIALLY_COMPLETED') AND j.finished_at > now() - interval '30 days') AS completed,
                       count(*) FILTER (WHERE j.status = 'FAILED' AND j.updated_at > now() - interval '30 days') AS failed,
                       coalesce(sum(j.imported_rows) FILTER (WHERE j.finished_at > now() - interval '30 days' AND NOT j.rolled_back), 0) AS imported,
                       coalesce(sum(j.failed_rows) FILTER (WHERE j.finished_at > now() - interval '30 days'), 0) AS failed_rows
                FROM student_import_job j JOIN org_unit ou ON ou.id = j.institution_id WHERE 1=1""" + scope).params(params).query().singleRow();
        List<JobSummary> recent = jdbc.sql(SUMMARY + " WHERE 1=1" + scope + " ORDER BY j.created_at DESC LIMIT 6").params(params).query(StudentImportService::summary).list();
        return new Overview(num(k.get("in_progress")), num(k.get("awaiting")), num(k.get("completed")), num(k.get("failed")), num(k.get("imported")),
                num(k.get("failed_rows")), recent, limits());
    }

    private static long num(Object o) { return o == null ? 0 : ((Number) o).longValue(); }

    // ---------------------------------------------------------------------------------------------------- source download

    public record Source(String fileName, String contentType, byte[] content) {}

    @Transactional
    public Source source(UUID id) {
        JobRow j = visible(id, false);
        if (!personalAllowed(j.institutionId())) throw new AccessDeniedException("Missing permission " + StudentAccess.PERSONAL);
        if (!"CLEAN".equals(j.scanStatus())) {
            audit.recordDenied("student.import.source_download_denied", Map.of("importId", id.toString(), "scanStatus", j.scanStatus()));
            throw conflict("source_not_scanned", "The original file can be downloaded only after a clean malware scan; no scanner is configured in this environment.");
        }
        if (j.sourcePurgedAt() != null) throw conflict("source_purged", "The original file was deleted after the retention period.");
        byte[] content = storage.get(j.objectPath()).orElseThrow(() -> conflict("source_missing", "The original file could not be found in storage."));
        audit.recordSuccess("student.import.source_downloaded", "student_import", id.toString(), Map.of());
        return new Source(j.fileName(), "XLSX".equals(j.fileFormat()) ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" : "text/csv", content);
    }

    // ---------------------------------------------------------------------------------------------------- saved mappings

    @Transactional(readOnly = true)
    public List<SavedMapping> mappings(UUID institutionId) {
        requireAnyImporter();
        List<Object> params = new ArrayList<>();
        String where = "";
        if (institutionId != null) { where = " WHERE m.institution_id IS NULL OR m.institution_id = ?"; params.add(institutionId); }
        return jdbc.sql("""
                SELECT m.*, ou.name AS institution_name, ou.path::text AS institution_path, u.display_name AS updated_by_name FROM student_import_mapping m
                LEFT JOIN org_unit ou ON ou.id = m.institution_id LEFT JOIN user_account u ON u.id = m.updated_by""" + where + " ORDER BY lower(m.name)")
                .params(params).query((rs, n) -> {
                    UUID inst = rs.getObject("institution_id", UUID.class);
                    return inst != null && !authz.hasInOrgUnit(IMPORT, inst) ? null
                            : new SavedMapping(rs.getObject("id", UUID.class), inst, rs.getString("institution_name"), rs.getString("name"), rs.getString("description"),
                                    json.readValue(rs.getString("columns"), new TypeReference<List<SavedColumn>>() {}), rs.getString("updated_by_name"),
                                    StudentService.instant(rs, "updated_at"), rs.getLong("version"));
                }).list().stream().filter(java.util.Objects::nonNull).toList();
    }

    List<SavedColumn> savedColumns(UUID templateId) {
        return jdbc.sql("SELECT columns, institution_id FROM student_import_mapping WHERE id = ?").param(templateId).query((rs, n) -> {
            UUID inst = rs.getObject(2, UUID.class);
            if (inst != null && !authz.hasInOrgUnit(IMPORT, inst)) throw new AccessDeniedException("Mapping outside scope");
            return json.readValue(rs.getString(1), new TypeReference<List<SavedColumn>>() {});
        }).optional().orElseThrow(() -> ApiException.notFound("Saved mapping"));
    }

    @Transactional
    public SavedMapping saveMappingTemplate(UUID templateId, UUID institutionId, String name, String description, List<SavedColumn> columns, Long version) {
        UUID id;
        if (templateId == null) {
            id = createMapping(institutionId, name, description, columns);
        } else {
            List<Map<String, Object>> rows = jdbc.sql("SELECT institution_id, version FROM student_import_mapping WHERE id = ? FOR UPDATE").param(templateId).query().listOfRows();
            if (rows.isEmpty()) throw ApiException.notFound("Saved mapping");
            UUID inst = (UUID) rows.getFirst().get("institution_id");
            requireMappingManager(inst);
            StudentAccess.version(version, ((Number) rows.getFirst().get("version")).longValue());
            String n = StudentService.required("name", name, 100);
            List<SavedColumn> cols = checkColumns(columns);
            try {
                jdbc.sql("UPDATE student_import_mapping SET name = ?, description = ?, columns = CAST(? AS jsonb), updated_by = ?, updated_at = now(), version = version + 1 WHERE id = ?")
                        .params(n, StudentService.optional(description, 300), json.writeValueAsString(cols), actor(), templateId).update();
            } catch (DuplicateKeyException e) {
                throw conflict("mapping_name_in_use", "Another saved mapping already uses this name.");
            }
            audit.recordSuccess("student.import.mapping_updated", "student_import_mapping", templateId.toString(), Map.of("columns", String.valueOf(cols.size())));
            id = templateId;
        }
        return mappings(null).stream().filter(m -> m.id().equals(id)).findFirst().orElseThrow();
    }

    private UUID createMapping(UUID institutionId, String name, String description, List<SavedColumn> columns) {
        requireMappingManager(institutionId);
        String n = StudentService.required("name", name, 100);
        List<SavedColumn> cols = checkColumns(columns);
        UUID id = Ids.newId();
        try {
            jdbc.sql("""
                    INSERT INTO student_import_mapping (id, tenant_id, institution_id, name, description, columns, created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?)""")
                    .params(id, tenant(), institutionId, n, StudentService.optional(description, 300), json.writeValueAsString(cols), actor(), actor()).update();
        } catch (DuplicateKeyException e) {
            throw conflict("mapping_name_in_use", "Another saved mapping already uses this name.");
        }
        audit.recordSuccess("student.import.mapping_saved", "student_import_mapping", id.toString(),
                Map.of("columns", String.valueOf(cols.size()), "scope", institutionId == null ? "organization" : institutionId.toString()));
        return id;
    }

    @Transactional
    public void deleteMappingTemplate(UUID templateId) {
        List<Map<String, Object>> rows = jdbc.sql("SELECT institution_id FROM student_import_mapping WHERE id = ?").param(templateId).query().listOfRows();
        if (rows.isEmpty()) throw ApiException.notFound("Saved mapping");
        requireMappingManager((UUID) rows.getFirst().get("institution_id"));
        jdbc.sql("UPDATE student_import_job SET mapping_template_id = NULL WHERE mapping_template_id = ?").param(templateId).update();
        jdbc.sql("DELETE FROM student_import_mapping WHERE id = ?").param(templateId).update();
        audit.recordSuccess("student.import.mapping_deleted", "student_import_mapping", templateId.toString(), Map.of());
    }

    private void requireMappingManager(UUID institutionId) {
        if (institutionId == null) {
            if (!authz.has(IMPORT)) throw new AccessDeniedException("Organization-wide mappings need organization-wide import permission");
        } else {
            requireImporter(institutionId);
        }
    }

    private static List<SavedColumn> checkColumns(List<SavedColumn> columns) {
        if (columns == null || columns.isEmpty()) throw ApiException.badRequest("invalid_columns", "A saved mapping needs at least one column.");
        if (columns.size() > 100) throw ApiException.badRequest("invalid_columns", "At most 100 columns.");
        Set<String> fields = new java.util.HashSet<>();
        List<SavedColumn> out = new ArrayList<>();
        for (SavedColumn c : columns) {
            String h = StudentService.required("header", c.header(), 120);
            ImportField f = ImportField.ofKey(c.field());
            if (f == null) throw ApiException.badRequest("invalid_columns", "Unknown field " + c.field() + ".");
            if (!fields.add(f.key())) throw StudentAccess.invalid("duplicate_field_mapping", f.label + " is mapped more than once.");
            out.add(new SavedColumn(h, f.key()));
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------------------- settings

    @Transactional(readOnly = true)
    public List<SettingsView> settingsList() {
        requireAnyImporter();
        return jdbc.sql("""
                SELECT ou.id, ou.name, s.match_personal_email, s.match_institutional_email, s.match_name_dob, s.match_name_mobile, s.require_date_of_birth,
                       s.id IS NOT NULL AS configured, coalesce(s.version, 0) AS version
                FROM org_unit ou LEFT JOIN student_import_settings s ON s.institution_id = ou.id
                WHERE ou.unit_type IN ('UNIVERSITY', 'COLLEGE') AND ou.status = 'ACTIVE' ORDER BY ou.path""")
                .query((rs, n) -> {
                    UUID inst = rs.getObject(1, UUID.class);
                    if (!authz.hasInOrgUnit(IMPORT, inst)) return null;
                    boolean cfg = rs.getBoolean("configured");
                    StudentImportEngine.Settings d = StudentImportEngine.Settings.DEFAULT;
                    return new SettingsView(inst, rs.getString(2), cfg ? rs.getBoolean(3) : d.matchPersonalEmail(), cfg ? rs.getBoolean(4) : d.matchInstitutionalEmail(),
                            cfg ? rs.getBoolean(5) : d.matchNameDob(), cfg ? rs.getBoolean(6) : d.matchNameMobile(), cfg ? rs.getBoolean(7) : d.requireDateOfBirth(),
                            cfg, rs.getLong("version"));
                }).list().stream().filter(java.util.Objects::nonNull).toList();
    }

    @Transactional
    public SettingsView saveSettings(UUID institutionId, SettingsCommand c) {
        access.unit(institutionId).filter(u -> Set.of("UNIVERSITY", "COLLEGE").contains(u.type()))
                .orElseThrow(() -> StudentAccess.invalid("invalid_institutionId", "Choose a university or college."));
        requireImporter(institutionId);
        Long current = jdbc.sql("SELECT version FROM student_import_settings WHERE institution_id = ? FOR UPDATE").param(institutionId).query(Long.class).optional().orElse(null);
        if (current != null) {
            StudentAccess.version(c.version(), current);
            jdbc.sql("""
                    UPDATE student_import_settings SET match_personal_email = ?, match_institutional_email = ?, match_name_dob = ?, match_name_mobile = ?,
                        require_date_of_birth = ?, updated_by = ?, updated_at = now(), version = version + 1 WHERE institution_id = ?""")
                    .params(c.matchPersonalEmail(), c.matchInstitutionalEmail(), c.matchNameDob(), c.matchNameMobile(), c.requireDateOfBirth(), actor(), institutionId).update();
        } else {
            jdbc.sql("""
                    INSERT INTO student_import_settings (id, tenant_id, institution_id, match_personal_email, match_institutional_email, match_name_dob, match_name_mobile,
                        require_date_of_birth, updated_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(Ids.newId(), tenant(), institutionId, c.matchPersonalEmail(), c.matchInstitutionalEmail(), c.matchNameDob(), c.matchNameMobile(),
                            c.requireDateOfBirth(), actor()).update();
        }
        Map<String, String> d = new LinkedHashMap<>();
        d.put("institutionId", institutionId.toString());
        d.put("matchPersonalEmail", String.valueOf(c.matchPersonalEmail()));
        d.put("matchInstitutionalEmail", String.valueOf(c.matchInstitutionalEmail()));
        d.put("matchNameDob", String.valueOf(c.matchNameDob()));
        d.put("matchNameMobile", String.valueOf(c.matchNameMobile()));
        d.put("requireDateOfBirth", String.valueOf(c.requireDateOfBirth()));
        audit.recordSuccess("student.import.settings_updated", "student_import_settings", institutionId.toString(), d);
        return settingsList().stream().filter(s -> s.institutionId().equals(institutionId)).findFirst().orElseThrow();
    }

    // ---------------------------------------------------------------------------------------------------- helpers

    /** Records a refused operation in its own transaction (the request's transaction is about to roll back). */
    private void auditFailure(String action, String resourceId, Map<String, String> details) {
        separate.executeWithoutResult(st -> audit.record(action, "student_import", resourceId, AuditOutcome.FAILURE, details));
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String safeName(String name) {
        String n = name == null ? "" : name.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}\"<>|:*?]", "_").trim();
        if (n.isEmpty()) n = "import";
        return n.length() > 200 ? n.substring(n.length() - 200) : n;
    }

    static void deleteQuietly(Path p) {
        if (p == null) return;
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // temporary file in the private temp directory; removed at JVM exit at the latest
            p.toFile().deleteOnExit();
        }
    }

    /** Issues as stored (JSON). */
    String issuesJson(List<Issue> issues) {
        List<IssueView> v = issues.stream().map(i -> new IssueView(i.severity().name(), i.category().name(), i.code(), i.field(), i.column(), i.columnNo(),
                i.message(), i.hint())).toList();
        return json.writeValueAsString(v);
    }
}
