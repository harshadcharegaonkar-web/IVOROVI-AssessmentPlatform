package com.ivorovi.platform.documents;

import com.ivorovi.platform.config.DeploymentProperties;
import com.ivorovi.platform.scanning.MalwareScanner;
import com.ivorovi.platform.storage.ObjectStorage;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.platform.web.ApiException;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Shared quarantine pipeline for uploaded documents (ADR-0006, D-19). Owner modules keep their own tables, permissions
 * and audit records; this component holds the parts that must be identical everywhere:
 * <ul>
 *   <li>fail closed: without a healthy scanner adapter, uploads are refused outside DEVELOPMENT/TEST ({@code 503 document_scanning_unavailable});</li>
 *   <li>validation (empty, at most 5 MB, type from magic bytes: PDF, JPEG, PNG). Validation is <b>not</b> malware protection;</li>
 *   <li>every upload is stored under {@code quarantine/…} and only a CLEAN verdict promotes it to {@code documents/…};</li>
 *   <li>a missing file, a scanner error or an INFECTED verdict is never CLEAN.</li>
 * </ul>
 */
@Component
public class QuarantinedDocuments {

    public static final long MAX_BYTES = 5L * 1024 * 1024;
    public static final String PDF = "application/pdf";
    public static final String JPEG = "image/jpeg";
    public static final String PNG = "image/png";
    public static final Set<String> ALL_TYPES = Set.of(PDF, JPEG, PNG);
    public static final Set<String> IMAGE_TYPES = Set.of(JPEG, PNG);

    /** A file that passed validation (not a malware verdict). */
    public record Validated(String contentType, String fileName, String sha256, long sizeBytes) {}

    /**
     * Result of one scan. {@code path} is the object path after the scan: the promoted path when CLEAN, otherwise the
     * unchanged quarantine path. {@code detail} is truncated to 300 characters.
     */
    public record Scanned(MalwareScanner.Verdict verdict, String detail, String path, String scanner) {
        public boolean clean() {
            return verdict == MalwareScanner.Verdict.CLEAN;
        }
    }

    private final ObjectStorage storage;
    private final ObjectProvider<MalwareScanner> scanner;
    private final DeploymentProperties deployment;
    private final boolean scanOnUpload;

    QuarantinedDocuments(ObjectStorage storage, ObjectProvider<MalwareScanner> scanner, DeploymentProperties deployment,
                         @Value("${ivorovi.scanning.scan-on-upload:true}") boolean scanOnUpload) {
        this.storage = storage;
        this.scanner = scanner;
        this.deployment = deployment;
        this.scanOnUpload = scanOnUpload;
    }

    /** Fail-closed policy: without a scanner, uploads are refused outside development and test. */
    public static boolean uploadsAllowed(DeploymentProperties.Environment env, boolean scannerPresent) {
        return scannerPresent || env == DeploymentProperties.Environment.DEVELOPMENT || env == DeploymentProperties.Environment.TEST;
    }

    public boolean scannerAvailable() {
        return scanner.getIfAvailable() != null;
    }

    /** Throws {@code 503 document_scanning_unavailable} when uploads must be refused (fail closed). */
    public void requireUploadsAllowed() {
        if (!uploadsAllowed(deployment.environment(), scannerAvailable())) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "document_scanning_unavailable",
                    "Document uploads are disabled until the malware scanning service is available.");
        }
    }

    /** Throws {@code empty_file} when there is no content. */
    public static void requireContent(byte[] content) {
        if (content == null || content.length == 0) throw ApiException.badRequest("empty_file", "Choose a file.");
    }

    /**
     * Size and type checks; the content type comes from the leading bytes, never from the client. Call
     * {@link #requireContent} first when the owner checks something in between.
     */
    public static Validated validate(byte[] content, String originalName, Set<String> allowedTypes) {
        requireContent(content);
        if (content.length > MAX_BYTES) throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "file_too_large", "Files can be at most 5 MB.");
        String type = sniff(content);
        if (type == null || !allowedTypes.contains(type)) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_file_type",
                    allowedTypes.contains(PDF) ? "Upload a PDF, JPEG or PNG file." : "Upload a JPEG or PNG image.");
        }
        return new Validated(type, safeName(originalName, type), sha256(content), content.length);
    }

    /** Stores the content in quarantine and returns its object path ({@code quarantine/<area>/<id>}). */
    public String quarantine(String area, UUID documentId, byte[] content, String contentType) {
        String path = "quarantine/" + area + "/" + documentId;
        storage.put(path, new ByteArrayInputStream(content), content.length, contentType);
        return path;
    }

    /**
     * After the surrounding transaction commits, runs {@code scan} on a virtual thread in the current tenant (when a
     * scanner is configured and scan-on-upload is on). The scheduled poll of the owner module scans anything missed.
     */
    public void scanAfterCommit(Runnable scan) {
        if (!scanOnUpload || !scannerAvailable() || !TransactionSynchronizationManager.isSynchronizationActive()) return;
        UUID tenant = TenantContext.requireTenantId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                Thread.ofVirtual().name("document-scan").start(() -> TenantContext.runInTenant(tenant, scan));
            }
        });
    }

    /**
     * Scans a quarantined file. On CLEAN the file is copied to {@code promotedPath} and removed from quarantine; on every
     * other verdict (a missing file is reported as ERROR) it stays where it is. An exception from the adapter is not
     * caught, so nothing is recorded and the document stays PENDING for the next poll. Returns empty when no scanner is
     * configured. Call inside the owner's transaction, holding the row lock.
     */
    public Optional<Scanned> scan(String quarantinePath, String promotedPath, String contentType) {
        MalwareScanner s = scanner.getIfAvailable();
        if (s == null) return Optional.empty();
        byte[] content = storage.get(quarantinePath).orElse(null);
        MalwareScanner.Result r;
        if (content == null) {
            r = new MalwareScanner.Result(MalwareScanner.Verdict.ERROR, "file missing in quarantine");
        } else {
            // an exception from the adapter propagates: the owner's transaction rolls back and the file stays PENDING
            r = s.scan(content);
            if (r == null || r.verdict() == null) r = new MalwareScanner.Result(MalwareScanner.Verdict.ERROR, "no verdict");
        }
        String detail = r.detail() == null ? null : r.detail().length() > 300 ? r.detail().substring(0, 300) : r.detail();
        if (r.verdict() == MalwareScanner.Verdict.CLEAN) {
            storage.put(promotedPath, new ByteArrayInputStream(content), content.length, contentType);
            // the quarantine copy goes only once the owner's row points at the promoted file
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        storage.delete(quarantinePath);
                    }
                });
            } else {
                storage.delete(quarantinePath);
            }
            return Optional.of(new Scanned(r.verdict(), detail, promotedPath, s.name()));
        }
        return Optional.of(new Scanned(r.verdict(), detail, quarantinePath, s.name()));
    }

    /** Content of a stored file; owners call this only for CLEAN documents. */
    public Optional<byte[]> content(String path) {
        return storage.get(path);
    }

    /** Deletes a stored file (retention purge). */
    public void delete(String path) {
        storage.delete(path);
    }

    /** Content type from the leading bytes (never trusted from the client). */
    public static String sniff(byte[] b) {
        if (b.length >= 5 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F' && b[4] == '-') return PDF;
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return JPEG;
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G' && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A) {
            return PNG;
        }
        return null;
    }

    public static String safeName(String original, String type) {
        String ext = switch (type) { case PDF -> ".pdf"; case PNG -> ".png"; default -> ".jpg"; };
        String base = original == null ? "document" : original.replaceAll("^.*[/\\\\]", "").replaceAll("\\.[A-Za-z0-9]{1,5}$", "");
        base = base.replaceAll("[^A-Za-z0-9 ._-]", "_").trim();
        if (base.isEmpty()) base = "document";
        if (base.length() > 120) base = base.substring(0, 120);
        return base + ext;
    }

    public static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
