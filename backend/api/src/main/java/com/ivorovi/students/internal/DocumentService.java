package com.ivorovi.students.internal;

import static com.ivorovi.students.internal.StudentAccess.actor;
import static com.ivorovi.students.internal.StudentAccess.conflict;
import static com.ivorovi.students.internal.StudentAccess.tenant;

import com.ivorovi.platform.audit.AuditLog;
import com.ivorovi.platform.audit.AuditOutcome;
import com.ivorovi.platform.config.DeploymentProperties;
import com.ivorovi.platform.ids.Ids;
import com.ivorovi.platform.scanning.MalwareScanner;
import com.ivorovi.platform.storage.ObjectStorage;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.platform.web.ApiException;
import com.ivorovi.students.internal.StudentService.OptionKind;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Student documents (ADR-0006). Type and size checks only reject unexpected files; they are <b>not</b> malware
 * protection. Every upload lands in quarantine ({@code quarantine/…}) with scan state PENDING; only a CLEAN verdict from
 * the configured scanner promotes it to {@code documents/…}, and only CLEAN documents can be downloaded or verified.
 * Without a scanner, documents stay quarantined (development/test); in STAGING/PRODUCTION uploads are refused
 * (fail closed). Removed and infected files are purged from storage after the retention period.
 */
@Service
public class DocumentService {

    public enum ScanStatus { PENDING, CLEAN, INFECTED, ERROR }
    public enum Verification { PENDING, VERIFIED, REJECTED }

    @Schema(name = "StudentDocument")
    public record DocumentView(UUID id, String category, String categoryLabel, String title, String fileName, String contentType, long sizeBytes,
                               ScanStatus scanStatus, String scanDetail, Instant scannedAt, Verification verificationStatus, String verificationNote,
                               String verifiedByName, Instant verifiedAt, String status, String removedReason, Instant uploadedAt, String uploadedByName,
                               boolean downloadable) {}

    public record Download(String fileName, String contentType, byte[] content) {}

    static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private final JdbcClient jdbc;
    private final StudentAccess access;
    private final StudentOptions options;
    private final ObjectStorage storage;
    private final ObjectProvider<MalwareScanner> scanner;
    private final DeploymentProperties deployment;
    private final AuditLog audit;
    private final TransactionTemplate tx;
    private final boolean scanOnUpload;
    private final int retentionDays;
    private final com.ivorovi.platform.audit.UploadRefusals refusals;

    DocumentService(com.ivorovi.platform.audit.UploadRefusals refusals, JdbcClient jdbc, StudentAccess access, StudentOptions options, ObjectStorage storage, ObjectProvider<MalwareScanner> scanner,
                    DeploymentProperties deployment, AuditLog audit, TransactionTemplate tx,
                    @Value("${ivorovi.scanning.scan-on-upload:true}") boolean scanOnUpload,
                    @Value("${ivorovi.retention.document-days:30}") int retentionDays) {
        this.jdbc = jdbc;
        this.access = access;
        this.options = options;
        this.storage = storage;
        this.scanner = scanner;
        this.deployment = deployment;
        this.audit = audit;
        this.tx = tx;
        this.scanOnUpload = scanOnUpload;
        this.retentionDays = retentionDays;
        this.refusals = refusals;
    }

    private static final String SELECT = """
            SELECT d.*, v.display_name AS verified_by_name, u.display_name AS uploaded_by_name FROM student_document d
            LEFT JOIN user_account v ON v.id = d.verified_by LEFT JOIN user_account u ON u.id = d.uploaded_by""";

    @Transactional(readOnly = true)
    public List<DocumentView> list(UUID studentId, boolean includeRemoved) {
        StudentAccess.StudentRef s = access.readable(studentId);
        access.require(StudentAccess.DOC_READ, s);
        return jdbc.sql(SELECT + " WHERE d.student_id = ?" + (includeRemoved ? "" : " AND d.status = 'ACTIVE'") + " ORDER BY d.uploaded_at DESC").param(studentId)
                .query((rs, n) -> {
                    String cat = rs.getString("category");
                    ScanStatus scan = ScanStatus.valueOf(rs.getString("scan_status"));
                    return new DocumentView(rs.getObject("id", UUID.class), cat, options.label(OptionKind.DOCUMENT_CATEGORY, cat), rs.getString("title"),
                            rs.getString("file_name"), rs.getString("content_type"), rs.getLong("size_bytes"), scan, rs.getString("scan_detail"),
                            StudentService.instant(rs, "scanned_at"), Verification.valueOf(rs.getString("verification_status")), rs.getString("verification_note"),
                            rs.getString("verified_by_name"), StudentService.instant(rs, "verified_at"), rs.getString("status"), rs.getString("removed_reason"),
                            StudentService.instant(rs, "uploaded_at"), rs.getString("uploaded_by_name"),
                            scan == ScanStatus.CLEAN && "ACTIVE".equals(rs.getString("status")));
                }).list();
    }

    /** Fail-closed policy: without a scanner, uploads are refused outside development and test. */
    public static boolean uploadsAllowed(DeploymentProperties.Environment env, boolean scannerPresent) {
        return scannerPresent || env == DeploymentProperties.Environment.DEVELOPMENT || env == DeploymentProperties.Environment.TEST;
    }

    @Transactional
    public UUID upload(UUID studentId, String category, String title, String originalName, byte[] content) {
        // every refusal is audited in its own transaction (this one rolls back) with institution and reason (KI-53)
        String[] detected = {null};
        StudentAccess.StudentRef[] who = {null};
        try {
            StudentAccess.StudentRef s = access.readable(studentId);
            who[0] = s;
            access.require(StudentAccess.DOC_MANAGE, s);
            if (!s.active()) throw conflict("student_inactive", "This student profile is deactivated.");
            if (!uploadsAllowed(deployment.environment(), scanner.getIfAvailable() != null)) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "document_scanning_unavailable",
                        "Document uploads are disabled until the malware scanning service is available.");
            }
            options.require(OptionKind.DOCUMENT_CATEGORY, category, "category");
            if (content == null || content.length == 0) throw ApiException.badRequest("empty_file", "Choose a file.");
            if (content.length > MAX_BYTES) throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "file_too_large", "Files can be at most 5 MB.");
            detected[0] = sniff(content);
            if (detected[0] == null) throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_file_type", "Upload a PDF, JPEG or PNG file.");
            if ("PHOTOGRAPH".equals(category) && "application/pdf".equals(detected[0])) {
                throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_file_type", "A photograph must be a JPEG or PNG image.");
            }
        } catch (ApiException e) {
            refused(studentId, who[0], category, content, detected[0], e.status().value(), e.code());
            throw e;
        } catch (org.springframework.security.access.AccessDeniedException e) {
            refused(studentId, who[0], category, content, detected[0], 403, "forbidden");
            throw e;
        }
        StudentAccess.StudentRef s = who[0];
        String type = detected[0];
        String name = safeName(originalName, type);
        String docTitle = StudentService.optional(title, 200);
        UUID id = Ids.newId();
        String path = "quarantine/students/" + studentId + "/" + id;
        storage.put(path, new ByteArrayInputStream(content), content.length, type);
        jdbc.sql("""
                INSERT INTO student_document (id, tenant_id, student_id, category, title, file_name, content_type, size_bytes, sha256, object_path, uploaded_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(id, tenant(), studentId, category, docTitle == null ? options.label(OptionKind.DOCUMENT_CATEGORY, category) : docTitle, name, type,
                        content.length, sha256(content), path, actor()).update();
        access.changed("student.document.uploaded", s, "Document uploaded (" + options.label(OptionKind.DOCUMENT_CATEGORY, category) + ", awaiting malware scan)",
                Map.of("documentId", id.toString(), "category", category, "contentType", type, "sizeBytes", String.valueOf(content.length)));
        if (scanOnUpload && scanner.getIfAvailable() != null) {
            UUID tenant = tenant();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    Thread.ofVirtual().name("document-scan").start(() -> TenantContext.runInTenant(tenant, () -> scanOne(id)));
                }
            });
        }
        return id;
    }

    private void refused(UUID studentId, StudentAccess.StudentRef s, String category, byte[] content, String detectedType, int status, String reason) {
        Map<String, String> d = new java.util.LinkedHashMap<>();
        d.put("studentId", studentId.toString());
        if (s != null) {
            d.put("studentNo", s.studentNo());
            d.put("institutionId", s.institutionId().toString());
        }
        if (category != null) d.put("category", category.length() > 40 ? category.substring(0, 40) : category);
        d.put("sizeBytes", String.valueOf(content == null ? 0 : content.length));
        if (detectedType != null) d.put("detectedType", detectedType);
        if (status == 503) d.put("scanner", "unavailable");
        refusals.refusedByService(currentRequest(), "student.document.upload_refused", "student", studentId.toString(), status, reason, d);
    }

    private static jakarta.servlet.http.HttpServletRequest currentRequest() {
        return org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
                instanceof org.springframework.web.context.request.ServletRequestAttributes sra ? sra.getRequest() : null;
    }

    /** Streams a CLEAN document; quarantined, infected or failed documents are never served. Every download is audited. */
    @Transactional
    public Download download(UUID studentId, UUID documentId) {
        StudentAccess.StudentRef s = access.readable(studentId);
        access.require(StudentAccess.DOC_READ, s);
        record D(String name, String type, String path, String scan, String status) {}
        D d = jdbc.sql("SELECT file_name, content_type, object_path, scan_status, status FROM student_document WHERE id = ? AND student_id = ?")
                .params(documentId, studentId).query((rs, n) -> new D(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)))
                .optional().orElseThrow(() -> ApiException.notFound("Document"));
        if (!"ACTIVE".equals(d.status()) || !"CLEAN".equals(d.scan())) {
            audit.recordDenied("student.document.download_denied", Map.of("documentId", documentId.toString(), "scanStatus", d.scan(), "status", d.status()));
            throw conflict("document_not_available", "This document is not available: it has not passed the malware scan (" + d.scan().toLowerCase() + ").");
        }
        byte[] content = storage.get(d.path()).orElseThrow(() -> conflict("document_missing", "The file could not be found in storage."));
        access.auditOnly("student.document.downloaded", s, Map.of("documentId", documentId.toString()));
        return new Download(d.name(), d.type(), content);
    }

    @Transactional
    public void verify(UUID studentId, UUID documentId, Verification to, String note) {
        StudentAccess.StudentRef s = access.readable(studentId);
        access.require(StudentAccess.DOC_MANAGE, s);
        if (to == null || to == Verification.PENDING) throw ApiException.badRequest("invalid_status", "Choose verified or rejected.");
        String scan = jdbc.sql("SELECT scan_status FROM student_document WHERE id = ? AND student_id = ? AND status = 'ACTIVE' FOR UPDATE").params(documentId, studentId)
                .query(String.class).optional().orElseThrow(() -> ApiException.notFound("Document"));
        if (!"CLEAN".equals(scan)) throw conflict("document_not_scanned", "Only documents that passed the malware scan can be verified.");
        String why = StudentService.optional(note, 500);
        if (to == Verification.REJECTED && why == null) throw ApiException.badRequest("reason_required", "Say why the document is rejected.");
        jdbc.sql("UPDATE student_document SET verification_status = ?, verification_note = ?, verified_by = ?, verified_at = now() WHERE id = ?")
                .params(to.name(), why, actor(), documentId).update();
        access.changed("student.document." + to.name().toLowerCase(), s, "Document " + to.name().toLowerCase(), Map.of("documentId", documentId.toString()));
    }

    @Transactional
    public void remove(UUID studentId, UUID documentId, String reason) {
        StudentAccess.StudentRef s = access.readable(studentId);
        access.require(StudentAccess.DOC_MANAGE, s);
        String why = StudentService.required("reason", reason, 500);
        int n = jdbc.sql("UPDATE student_document SET status = 'REMOVED', removed_reason = ?, removed_by = ?, removed_at = now() WHERE id = ? AND student_id = ? AND status = 'ACTIVE'")
                .params(why, actor(), documentId, studentId).update();
        if (n == 0) throw ApiException.notFound("Document");
        access.changed("student.document.removed", s, "Document removed (file purged after " + retentionDays + " days)", Map.of("documentId", documentId.toString(), "reason", why));
    }

    // ------------------------------------------------------------------------------------------------ scanning

    /** Scans every pending document of every organization (scheduled; also triggered after uploads). */
    @Scheduled(fixedDelayString = "${ivorovi.scanning.poll-interval:PT30S}")
    public void scanPendingScheduled() {
        if (scanner.getIfAvailable() != null) scanPending();
    }

    public int scanPending() {
        if (scanner.getIfAvailable() == null) return 0;
        List<UUID> tenants = TenantContext.callAsPlatform(() -> jdbc.sql("SELECT id FROM tenant WHERE status = 'ACTIVE'").query(UUID.class).list());
        int scanned = 0;
        for (UUID t : tenants) {
            List<UUID> pending = TenantContext.callInTenant(t, () -> tx.execute(st ->
                    jdbc.sql("SELECT id FROM student_document WHERE scan_status = 'PENDING' AND status = 'ACTIVE' ORDER BY uploaded_at LIMIT 50").query(UUID.class).list()));
            for (UUID id : pending) {
                TenantContext.runInTenant(t, () -> scanOne(id));
                scanned++;
            }
        }
        return scanned;
    }

    /** Scans one quarantined document in its own transaction (tenant context must be bound). */
    void scanOne(UUID documentId) {
        MalwareScanner s = scanner.getIfAvailable();
        if (s == null) return;
        try {
            tx.executeWithoutResult(st -> {
                record D(UUID student, String path, String type, String scan) {}
                Optional<D> doc = jdbc.sql("SELECT student_id, object_path, content_type, scan_status FROM student_document WHERE id = ? FOR UPDATE SKIP LOCKED")
                        .param(documentId).query((rs, n) -> new D(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4))).optional();
                if (doc.isEmpty() || !"PENDING".equals(doc.get().scan())) return;
                byte[] content = storage.get(doc.get().path()).orElse(null);
                MalwareScanner.Result r = content == null ? new MalwareScanner.Result(MalwareScanner.Verdict.ERROR, "file missing in quarantine") : s.scan(content);
                String detail = r.detail() == null ? null : r.detail().length() > 300 ? r.detail().substring(0, 300) : r.detail();
                if (r.verdict() == MalwareScanner.Verdict.CLEAN) {
                    String promoted = "documents/students/" + doc.get().student() + "/" + documentId;
                    storage.put(promoted, new ByteArrayInputStream(content), content.length, doc.get().type());
                    jdbc.sql("UPDATE student_document SET scan_status = 'CLEAN', scan_detail = ?, scanned_at = now(), object_path = ? WHERE id = ?")
                            .params(detail, promoted, documentId).update();
                    storage.delete(doc.get().path());
                } else {
                    jdbc.sql("UPDATE student_document SET scan_status = ?, scan_detail = ?, scanned_at = now() WHERE id = ?")
                            .params(r.verdict().name(), detail, documentId).update();
                }
                String studentNo = jdbc.sql("SELECT student_no FROM student WHERE id = ?").param(doc.get().student()).query(String.class).single();
                Map<String, String> details = Map.of("documentId", documentId.toString(), "verdict", r.verdict().name(), "scanner", s.name(), "studentNo", studentNo);
                audit.record("student.document.scanned", "student", doc.get().student().toString(),
                        r.verdict() == MalwareScanner.Verdict.INFECTED ? AuditOutcome.FAILURE : AuditOutcome.SUCCESS, details);
                jdbc.sql("INSERT INTO student_event (id, tenant_id, student_id, event_type, summary) VALUES (?, ?, ?, 'student.document.scanned', ?)")
                        .params(Ids.newId(), TenantContext.requireTenantId(), doc.get().student(), "Malware scan: " + r.verdict().name().toLowerCase()).update();
            });
        } catch (RuntimeException e) {
            log.warn("Scanning document {} failed: {}", documentId, e.getClass().getSimpleName());
        }
    }

    /** Re-queues a document whose scan failed (ERROR) for another attempt. */
    @Transactional
    public void rescan(UUID studentId, UUID documentId) {
        StudentAccess.StudentRef s = access.readable(studentId);
        access.require(StudentAccess.DOC_MANAGE, s);
        int n = jdbc.sql("UPDATE student_document SET scan_status = 'PENDING', scan_detail = NULL WHERE id = ? AND student_id = ? AND scan_status = 'ERROR' AND status = 'ACTIVE'")
                .params(documentId, studentId).update();
        if (n == 0) throw conflict("invalid_status_transition", "Only documents whose scan failed can be scanned again.");
        access.auditOnly("student.document.rescan_requested", s, Map.of("documentId", documentId.toString()));
    }

    /** Retention: purges stored files of removed documents and of infected uploads after the retention period. */
    @Scheduled(cron = "${ivorovi.retention.cron:0 17 * * * *}")
    public void purgeExpired() {
        List<UUID> tenants = TenantContext.callAsPlatform(() -> jdbc.sql("SELECT id FROM tenant").query(UUID.class).list());
        for (UUID t : tenants) TenantContext.runInTenant(t, () -> tx.executeWithoutResult(st -> {
            record P(UUID id, String path) {}
            List<P> due = jdbc.sql("""
                    SELECT id, object_path FROM student_document WHERE purged_at IS NULL
                      AND ((status = 'REMOVED' AND removed_at < now() - make_interval(days => ?)) OR (scan_status = 'INFECTED' AND scanned_at < now() - make_interval(days => ?)))
                    LIMIT 200""").params(retentionDays, retentionDays).query((rs, n) -> new P(rs.getObject(1, UUID.class), rs.getString(2))).list();
            for (P p : due) {
                try {
                    storage.delete(p.path());
                    jdbc.sql("UPDATE student_document SET purged_at = now() WHERE id = ?").param(p.id()).update();
                    audit.recordSuccess("student.document.purged", "student_document", p.id().toString(), Map.of("documentId", p.id().toString()));
                } catch (RuntimeException e) {
                    log.warn("Purging document {} failed: {}", p.id(), e.getClass().getSimpleName());
                }
            }
        }));
    }

    // ------------------------------------------------------------------------------------------------ validation

    /** Content type from the leading bytes (never trusted from the client). */
    static String sniff(byte[] b) {
        if (b.length >= 5 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F' && b[4] == '-') return "application/pdf";
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "image/jpeg";
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G' && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A) {
            return "image/png";
        }
        return null;
    }

    static String safeName(String original, String type) {
        String ext = switch (type) { case "application/pdf" -> ".pdf"; case "image/png" -> ".png"; default -> ".jpg"; };
        String base = original == null ? "document" : original.replaceAll("^.*[/\\\\]", "").replaceAll("\\.[A-Za-z0-9]{1,5}$", "");
        base = base.replaceAll("[^A-Za-z0-9 ._-]", "_").trim();
        if (base.isEmpty()) base = "document";
        if (base.length() > 120) base = base.substring(0, 120);
        return base + ext;
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
