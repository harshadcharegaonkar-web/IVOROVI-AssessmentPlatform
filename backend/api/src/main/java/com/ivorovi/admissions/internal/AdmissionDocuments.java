package com.ivorovi.admissions.internal;

import com.ivorovi.platform.audit.AuditLog;
import com.ivorovi.platform.audit.AuditOutcome;
import com.ivorovi.platform.documents.QuarantinedDocuments;
import com.ivorovi.platform.ids.Ids;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.platform.web.ApiException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applicant documents (ADR-0006, D-19) through the shared quarantine pipeline: fail closed without a scanner outside
 * development and test, quarantine on upload, promotion only on CLEAN. A PENDING, ERROR or INFECTED document is never
 * accepted: it cannot be downloaded or verified (application and database), and screening never counts it.
 */
@Service
public class AdmissionDocuments {

    @Schema(name = "AdmissionDocument")
    public record Document(UUID id, String category, String categoryLabel, String fileName, String contentType, long sizeBytes, String scanStatus,
                           Instant scannedAt, String verificationStatus, String verificationNote, Instant uploadedAt, boolean downloadable) {}

    public record Download(String fileName, String contentType, byte[] content) {}

    private static final Logger log = LoggerFactory.getLogger(AdmissionDocuments.class);

    private final JdbcClient jdbc;
    private final QuarantinedDocuments files;
    private final AuditLog audit;
    private final TransactionTemplate tx;
    private final com.ivorovi.platform.audit.UploadRefusals refusals;

    AdmissionDocuments(JdbcClient jdbc, QuarantinedDocuments files, AuditLog audit, TransactionTemplate tx, com.ivorovi.platform.audit.UploadRefusals refusals) {
        this.refusals = refusals;
        this.jdbc = jdbc;
        this.files = files;
        this.audit = audit;
        this.tx = tx;
    }

    List<Document> list(UUID applicationId) {
        return jdbc.sql("""
                SELECT id, category, file_name, content_type, size_bytes, scan_status, scanned_at, verification_status, verification_note, uploaded_at
                FROM admission_document WHERE application_id = ? AND status = 'ACTIVE' ORDER BY uploaded_at""").param(applicationId)
                .query((rs, n) -> new Document(rs.getObject(1, UUID.class), rs.getString(2), CycleService.DOCUMENT_CATEGORIES.get(rs.getString(2)), rs.getString(3),
                        rs.getString(4), rs.getLong(5), rs.getString(6), ts(rs.getTimestamp(7)), rs.getString(8), rs.getString(9), ts(rs.getTimestamp(10)),
                        "CLEAN".equals(rs.getString(6)))).list();
    }

    /**
     * Stores an upload in quarantine (the caller has authorized the applicant and locked the application). A new upload of
     * the same category replaces the earlier one. Refusals are audited without the file name or content.
     */
    UUID upload(UUID applicationId, String category, String originalName, byte[] content) {
        try {
            files.requireUploadsAllowed();
            if (!CycleService.DOCUMENT_CATEGORIES.containsKey(category)) throw ApiException.badRequest("invalid_category", "Choose the kind of document.");
            QuarantinedDocuments.Validated v = QuarantinedDocuments.validate(content, originalName,
                    "PHOTOGRAPH".equals(category) ? QuarantinedDocuments.IMAGE_TYPES : QuarantinedDocuments.ALL_TYPES);
            UUID id = Ids.newId();
            String path = files.quarantine("admissions/" + applicationId, id, content, v.contentType());
            jdbc.sql("UPDATE admission_document SET status = 'REPLACED' WHERE application_id = ? AND category = ? AND status = 'ACTIVE'")
                    .params(applicationId, category).update();
            jdbc.sql("""
                    INSERT INTO admission_document (id, tenant_id, application_id, category, file_name, content_type, size_bytes, sha256, object_path)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(id, TenantContext.requireTenantId(), applicationId, category, v.fileName(), v.contentType(), (int) v.sizeBytes(), v.sha256(), path)
                    .update();
            audit.recordSuccess("admission.document.uploaded", "admission_document", id.toString(), Map.of("applicationId", applicationId.toString(),
                    "category", category, "contentType", v.contentType(), "sizeBytes", String.valueOf(v.sizeBytes())));
            files.scanAfterCommit(() -> scanOne(id));
            return id;
        } catch (ApiException e) {
            // audited in its own transaction (this one rolls back), without the file name or content (KI-53 pattern)
            Map<String, String> d = new java.util.LinkedHashMap<>();
            d.put("applicationId", applicationId.toString());
            if (category != null) d.put("category", category.length() > 40 ? category.substring(0, 40) : category);
            d.put("sizeBytes", String.valueOf(content == null ? 0 : content.length));
            if (e.status().value() == 503) d.put("scanner", "unavailable");
            refusals.refusedByService(null, "admission.document.upload_refused", "admission_application", applicationId.toString(), e.status().value(), e.code(), d);
            throw e;
        }
    }

    /** CLEAN documents only; every download is audited (ids only). */
    Download download(UUID applicationId, UUID documentId) {
        record D(String name, String type, String path, String scan) {}
        D d = jdbc.sql("SELECT file_name, content_type, object_path, scan_status FROM admission_document WHERE id = ? AND application_id = ? AND status = 'ACTIVE'")
                .params(documentId, applicationId).query((rs, n) -> new D(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4))).optional()
                .orElseThrow(() -> ApiException.notFound("Document"));
        if (!"CLEAN".equals(d.scan())) {
            audit.recordDenied("admission.document.download_denied", Map.of("documentId", documentId.toString(), "scanStatus", d.scan()));
            throw ApiException.conflict("document_not_available", "This document has not passed the malware scan (" + d.scan().toLowerCase() + ").");
        }
        byte[] content = files.content(d.path()).orElseThrow(() -> ApiException.conflict("document_missing", "The file could not be found in storage."));
        audit.recordSuccess("admission.document.downloaded", "admission_document", documentId.toString(), Map.of("applicationId", applicationId.toString()));
        return new Download(d.name(), d.type(), content);
    }

    void verify(UUID applicationId, UUID documentId, boolean verified, String note, UUID actor) {
        String scan = jdbc.sql("SELECT scan_status FROM admission_document WHERE id = ? AND application_id = ? AND status = 'ACTIVE' FOR UPDATE")
                .params(documentId, applicationId).query(String.class).optional().orElseThrow(() -> ApiException.notFound("Document"));
        if (!"CLEAN".equals(scan)) throw ApiException.conflict("document_not_scanned", "Only documents that passed the malware scan can be verified.");
        String why = AdmissionAccess.text(note, 500, "note", !verified);
        jdbc.sql("UPDATE admission_document SET verification_status = ?, verification_note = ?, verified_by = ?, verified_at = now() WHERE id = ?")
                .params(verified ? "VERIFIED" : "REJECTED", why, actor, documentId).update();
        audit.recordSuccess("admission.document." + (verified ? "verified" : "rejected"), "admission_document", documentId.toString(),
                Map.of("applicationId", applicationId.toString()));
    }

    void rescan(UUID applicationId, UUID documentId) {
        int n = jdbc.sql("UPDATE admission_document SET scan_status = 'PENDING', scan_detail = NULL WHERE id = ? AND application_id = ? AND scan_status = 'ERROR' AND status = 'ACTIVE'")
                .params(documentId, applicationId).update();
        if (n == 0) throw ApiException.conflict("invalid_status_transition", "Only documents whose scan failed can be scanned again.");
        audit.recordSuccess("admission.document.rescan_requested", "admission_document", documentId.toString(), Map.of("applicationId", applicationId.toString()));
    }

    // ------------------------------------------------------------------------------------------------ scanning

    @Scheduled(fixedDelayString = "${ivorovi.scanning.poll-interval:PT30S}")
    public void scanPendingScheduled() {
        if (files.scannerAvailable()) scanPending();
    }

    public int scanPending() {
        if (!files.scannerAvailable()) return 0;
        List<UUID> tenants = TenantContext.callAsPlatform(() -> jdbc.sql("SELECT id FROM tenant WHERE status = 'ACTIVE'").query(UUID.class).list());
        int scanned = 0;
        for (UUID t : tenants) {
            List<UUID> pending = TenantContext.callInTenant(t, () -> tx.execute(st ->
                    jdbc.sql("SELECT id FROM admission_document WHERE scan_status = 'PENDING' AND status = 'ACTIVE' ORDER BY uploaded_at LIMIT 50").query(UUID.class).list()));
            for (UUID id : pending) {
                TenantContext.runInTenant(t, () -> scanOne(id));
                scanned++;
            }
        }
        return scanned;
    }

    void scanOne(UUID documentId) {
        if (!files.scannerAvailable()) return;
        try {
            tx.executeWithoutResult(st -> {
                record D(UUID application, String path, String type, String scan) {}
                Optional<D> doc = jdbc.sql("SELECT application_id, object_path, content_type, scan_status FROM admission_document WHERE id = ? FOR UPDATE SKIP LOCKED")
                        .param(documentId).query((rs, n) -> new D(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4))).optional();
                if (doc.isEmpty() || !"PENDING".equals(doc.get().scan())) return;
                Optional<QuarantinedDocuments.Scanned> scanned = files.scan(doc.get().path(), "documents/admissions/" + doc.get().application() + "/" + documentId,
                        doc.get().type());
                if (scanned.isEmpty()) return;
                QuarantinedDocuments.Scanned r = scanned.get();
                jdbc.sql("UPDATE admission_document SET scan_status = ?, scan_detail = ?, scanned_at = now(), object_path = ? WHERE id = ?")
                        .params(r.verdict().name(), r.detail(), r.path(), documentId).update();
                audit.record("admission.document.scanned", "admission_document", documentId.toString(),
                        r.verdict() == com.ivorovi.platform.scanning.MalwareScanner.Verdict.INFECTED ? AuditOutcome.FAILURE : AuditOutcome.SUCCESS,
                        Map.of("applicationId", doc.get().application().toString(), "verdict", r.verdict().name(), "scanner", r.scanner()));
            });
        } catch (RuntimeException e) {
            log.warn("Scanning admission document {} failed: {}", documentId, e.getClass().getSimpleName());
        }
    }

    private static Instant ts(java.sql.Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
