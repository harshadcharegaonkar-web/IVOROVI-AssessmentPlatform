package com.ivorovi.questions.internal;

import com.ivorovi.platform.audit.AuditLog;
import com.ivorovi.platform.config.DeploymentProperties;
import com.ivorovi.platform.ids.Ids;
import com.ivorovi.platform.jobs.JobLeases;
import com.ivorovi.platform.scanning.MalwareScanner;
import com.ivorovi.platform.storage.ObjectStorage;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.platform.web.ApiException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Question images (ADR-0010). PNG and JPEG only, at most 5 MB and 4,096 × 4,096 pixels (header checked before any
 * decoding). The original goes to quarantine and is scanned (ADR-0006: no scanner outside development/test → upload refused;
 * scanner error → ERROR, never served; infected → INFECTED, never served). Clean images are decoded and re-encoded by the
 * JDK (metadata, comments and appended data removed); only the re-encoded file is ever served, and only to holders of
 * {@code question.read} on the image's bank. Institution quotas bound storage; unused images are purged after 30 days once
 * verified unreferenced.
 */
@Service
public class AssetService {

    public static final long MAX_BYTES = 5L * 1024 * 1024;
    public static final int MAX_DIMENSION = 4096;
    private static final Logger log = LoggerFactory.getLogger(AssetService.class);

    @Schema(name = "QuestionAsset")
    public record Asset(UUID id, UUID bankId, String mediaType, int width, int height, long bytes, String scanStatus, String fileName, String altText,
                        Instant createdAt, long usedByVersions) {}

    @Schema(name = "QuestionAssetQuota")
    public record Quota(UUID orgUnitId, long limitBytes, long usedBytes) {}

    public record Served(byte[] bytes, String mediaType) {}

    private final JdbcClient jdbc;
    private final QuestionAccess access;
    private final AuditLog audit;
    private final ObjectStorage storage;
    private final ObjectProvider<MalwareScanner> scanner;
    private final DeploymentProperties deployment;
    private final JobLeases leases;
    private final long defaultQuota;
    private final boolean cleanupEnabled;

    AssetService(JdbcClient jdbc, QuestionAccess access, AuditLog audit, ObjectStorage storage, ObjectProvider<MalwareScanner> scanner,
                 DeploymentProperties deployment, JobLeases leases,
                 @Value("${ivorovi.questions.asset-quota-bytes:2147483648}") long defaultQuota,
                 @Value("${ivorovi.questions.asset-cleanup-enabled:true}") boolean cleanupEnabled) {
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
        this.storage = storage;
        this.scanner = scanner;
        this.deployment = deployment;
        this.leases = leases;
        this.defaultQuota = defaultQuota;
        this.cleanupEnabled = cleanupEnabled;
    }

    /** Fail closed (ADR-0006): without a scanner, uploads are possible only in development and test. */
    public static boolean uploadsAllowed(DeploymentProperties.Environment env, boolean scannerPresent) {
        return scannerPresent || env == DeploymentProperties.Environment.DEVELOPMENT || env == DeploymentProperties.Environment.TEST;
    }

    record Inspected(String mediaType, int width, int height) {}

    /** Media type from magic bytes and dimensions from the header only (no pixel decoding yet). */
    static Inspected inspect(byte[] content) {
        String type;
        if (content.length > 8 && (content[0] & 0xff) == 0x89 && content[1] == 'P' && content[2] == 'N' && content[3] == 'G') type = "image/png";
        else if (content.length > 3 && (content[0] & 0xff) == 0xff && (content[1] & 0xff) == 0xd8 && (content[2] & 0xff) == 0xff) type = "image/jpeg";
        else throw ApiException.badRequest("unsupported_image", "Only PNG and JPEG images are accepted.");
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByMIMEType(type);
            if (!readers.hasNext()) throw ApiException.badRequest("unsupported_image", "Only PNG and JPEG images are accepted.");
            ImageReader r = readers.next();
            try {
                r.setInput(in, true, true);
                int w = r.getWidth(0);
                int h = r.getHeight(0);
                if (w < 1 || h < 1 || w > MAX_DIMENSION || h > MAX_DIMENSION) {
                    throw ApiException.badRequest("image_too_large", "Images may be at most " + MAX_DIMENSION + " × " + MAX_DIMENSION + " pixels.");
                }
                return new Inspected(type, w, h);
            } finally {
                r.dispose();
            }
        } catch (IOException e) {
            throw ApiException.badRequest("invalid_image", "The image could not be read.");
        }
    }

    /** Decodes and re-encodes pixels only: metadata, comments, ICC/EXIF and trailing data are dropped. */
    static byte[] reencode(byte[] content, String type) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(content));
            if (img == null || img.getWidth() > MAX_DIMENSION || img.getHeight() > MAX_DIMENSION) throw ApiException.badRequest("invalid_image", "The image could not be read.");
            BufferedImage clean;
            if ("image/jpeg".equals(type)) {
                clean = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
                clean.getGraphics().drawImage(img, 0, 0, java.awt.Color.WHITE, null);
            } else {
                clean = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
                clean.getGraphics().drawImage(img, 0, 0, null);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if ("image/jpeg".equals(type)) {
                ImageWriter w = ImageIO.getImageWritersByFormatName("jpeg").next();
                ImageWriteParam p = w.getDefaultWriteParam();
                p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                p.setCompressionQuality(0.9f);
                try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
                    w.setOutput(ios);
                    w.write(null, new IIOImage(clean, null, null), p);
                } finally {
                    w.dispose();
                }
            } else {
                ImageIO.write(clean, "png", out);
            }
            return out.toByteArray();
        } catch (IOException | RuntimeException e) {
            if (e instanceof ApiException a) throw a;
            throw ApiException.badRequest("invalid_image", "The image could not be read.");
        }
    }

    @Transactional
    public Asset upload(UUID bankId, String fileName, byte[] content, String altText) {
        QuestionAccess.BankRef bank = access.bank(bankId);
        access.require(QuestionAccess.AUTHOR, bank);
        MalwareScanner sc = scanner.getIfAvailable();
        if (!uploadsAllowed(deployment.environment(), sc != null)) {
            audit.recordDenied("question.asset.upload_refused", Map.of("bank", bankId.toString(), "reason", "no_malware_scanner"));
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "scanning_unavailable", "Image upload is unavailable: no malware scanner is configured.");
        }
        if (content == null || content.length == 0) throw ApiException.badRequest("empty_file", "The file is empty.");
        if (content.length > MAX_BYTES) throw ApiException.badRequest("image_too_large", "Images may be at most 5 MB.");
        Inspected info = inspect(content);
        checkQuota(bank, content.length);
        String alt = altText == null || altText.isBlank() ? null : altText.strip();
        if (alt != null && alt.length() > 250) throw ApiException.badRequest("alt_too_long", "Alternative text may be at most 250 characters.");
        UUID tenant = TenantContext.requireTenantId();
        UUID id = Ids.newId();
        String sha = sha256(content);
        String quarantine = "t/" + tenant + "/quarantine/question-assets/" + id;
        storage.put(quarantine, new ByteArrayInputStream(content), content.length, "application/octet-stream");

        String status;
        String served = null;
        byte[] servedBytes = null;
        if (sc == null) {
            status = "NOT_SCANNED_DEV";
        } else {
            MalwareScanner.Result r;
            try {
                r = sc.scan(content);
            } catch (RuntimeException e) {
                r = new MalwareScanner.Result(MalwareScanner.Verdict.ERROR, e.getClass().getSimpleName());
            }
            status = r.verdict().name();
        }
        if ("CLEAN".equals(status) || "NOT_SCANNED_DEV".equals(status)) {
            servedBytes = reencode(content, info.mediaType());
            if (servedBytes.length > MAX_BYTES) throw ApiException.badRequest("image_too_large", "The processed image would exceed 5 MB; use a smaller or compressed image.");
            served = "t/" + tenant + "/question-assets/" + bankId + "/" + id;
            storage.put(served, new ByteArrayInputStream(servedBytes), servedBytes.length, info.mediaType());
        }
        jdbc.sql("""
                INSERT INTO question_asset (id, tenant_id, bank_id, sha256, media_type, width, height, bytes, scan_status, quarantine_key, served_key, file_name,
                    alt_text, uploaded_by, scanned_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CASE WHEN ? THEN now() END)""")
                .params(id, tenant, bankId, sha, info.mediaType(), info.width(), info.height(), servedBytes == null ? content.length : servedBytes.length, status,
                        quarantine, served, safeName(fileName), alt, access.me(), sc != null).update();
        audit.recordSuccess("question.asset.uploaded", "question_asset", id.toString(), Map.of("bank", bankId.toString(), "type", info.mediaType(),
                "bytes", String.valueOf(content.length), "scan", status));
        if ("INFECTED".equals(status)) {
            audit.recordSuccess("question.asset.infected", "question_asset", id.toString(), Map.of("bank", bankId.toString()));
        }
        return get(id);
    }

    private void checkQuota(QuestionAccess.BankRef bank, long incoming) {
        Quota q = quotaFor(bank.orgUnitId());
        if (q.usedBytes() + incoming > q.limitBytes()) {
            audit.recordDenied("question.asset.quota_exceeded", Map.of("unit", String.valueOf(q.orgUnitId()), "limit", String.valueOf(q.limitBytes())));
            throw ApiException.conflict("asset_quota_exceeded", "The image storage quota of this institution is used up.");
        }
    }

    /** Closest quota at or above the unit (or the organization default); usage counts every bank in that quota's subtree. */
    Quota quotaFor(UUID unit) {
        record Q(UUID unit, long limit) {}
        Q q = jdbc.sql("""
                SELECT qa.org_unit_id, qa.bytes_limit FROM question_asset_quota qa LEFT JOIN org_unit qu ON qu.id = qa.org_unit_id
                WHERE qa.org_unit_id IS NULL OR (CAST(? AS uuid) IS NOT NULL AND EXISTS (SELECT 1 FROM org_unit bu WHERE bu.id = CAST(? AS uuid) AND bu.path <@ qu.path))
                ORDER BY (qa.org_unit_id IS NULL), nlevel(qu.path) DESC LIMIT 1""").params(unit, unit)
                .query((rs, n) -> new Q(rs.getObject(1, UUID.class), rs.getLong(2))).optional().orElse(new Q(null, defaultQuota));
        long used = jdbc.sql("""
                SELECT coalesce(sum(a.bytes), 0) FROM question_asset a JOIN question_bank b ON b.id = a.bank_id
                WHERE CAST(? AS uuid) IS NULL OR b.org_unit_id IN (SELECT d.id FROM org_unit d, org_unit p WHERE p.id = CAST(? AS uuid) AND d.path <@ p.path)""")
                .params(q.unit(), q.unit()).query(Long.class).single();
        return new Quota(q.unit(), q.limit(), used);
    }

    @Transactional
    public Quota setQuota(UUID unit, long limitBytes) {
        if (!access.can(QuestionAccess.MANAGE, unit)) throw new org.springframework.security.access.AccessDeniedException("Missing permission " + QuestionAccess.MANAGE);
        if (limitBytes < MAX_BYTES) throw ApiException.badRequest("invalid_quota", "A quota must allow at least one image (5 MB).");
        jdbc.sql("""
                INSERT INTO question_asset_quota (id, tenant_id, org_unit_id, bytes_limit, updated_by) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, coalesce(org_unit_id, '00000000-0000-0000-0000-000000000000'::uuid))
                DO UPDATE SET bytes_limit = EXCLUDED.bytes_limit, updated_by = EXCLUDED.updated_by, updated_at = now()""")
                .params(Ids.newId(), TenantContext.requireTenantId(), unit, limitBytes, access.me()).update();
        audit.recordSuccess("question.asset.quota_set", "question_asset_quota", String.valueOf(unit), Map.of("bytes", String.valueOf(limitBytes)));
        return quotaFor(unit);
    }

    @Transactional(readOnly = true)
    public Quota quota(UUID bankId) {
        return quotaFor(access.bank(bankId).orgUnitId());
    }

    @Transactional(readOnly = true)
    public List<Asset> list(UUID bankId) {
        access.bank(bankId);
        return jdbc.sql(SELECT + " WHERE a.bank_id = ? ORDER BY a.created_at DESC LIMIT 500").param(bankId).query(this::row).list();
    }

    private static final String SELECT = """
            SELECT a.*, (SELECT count(DISTINCT va.version_id) FROM question_version_asset va WHERE va.asset_id = a.id) AS used FROM question_asset a""";

    Asset get(UUID id) {
        return jdbc.sql(SELECT + " WHERE a.id = ?").param(id).query(this::row).optional().orElseThrow(() -> ApiException.notFound("Image"));
    }

    private Asset row(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Asset(rs.getObject("id", UUID.class), rs.getObject("bank_id", UUID.class), rs.getString("media_type"), rs.getInt("width"), rs.getInt("height"),
                rs.getLong("bytes"), rs.getString("scan_status"), rs.getString("file_name"), rs.getString("alt_text"), rs.getTimestamp("created_at").toInstant(),
                rs.getLong("used"));
    }

    /** The re-encoded image, only when clean and only to readers of its bank (otherwise 404, audited). */
    @Transactional(readOnly = true)
    public Served serve(UUID id) {
        record R(UUID bank, String status, String key, String type) {}
        R r = jdbc.sql("SELECT bank_id, scan_status, served_key, media_type FROM question_asset WHERE id = ?").param(id)
                .query((rs, n) -> new R(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4))).optional()
                .orElseThrow(() -> ApiException.notFound("Image"));
        try {
            access.bank(r.bank());
        } catch (ApiException e) {
            audit.recordDenied("question.asset.view_denied", Map.of("assetId", id.toString()));
            throw ApiException.notFound("Image");
        }
        if (r.key() == null) {
            audit.recordDenied("question.asset.unavailable", Map.of("assetId", id.toString(), "scan", r.status()));
            throw ApiException.conflict("image_not_available", "This image is not available (malware scan " + r.status().toLowerCase() + ").");
        }
        byte[] bytes = storage.get(r.key()).orElseThrow(() -> ApiException.notFound("Image"));
        return new Served(bytes, r.type());
    }

    /**
     * KI-83: an image for delivery, served only when it is linked to one of {@code versionIds} (the questions of the
     * caller's own paper; the delivery module checks the attempt) and its malware scan is clean (see {@link #deliverable}). No bank access is
     * involved (candidates have none); refusals are audited without personal data.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<Served> serveForVersions(UUID id, java.util.Collection<UUID> versionIds) {
        if (versionIds.isEmpty()) return java.util.Optional.empty();
        boolean linked = jdbc.sql("SELECT EXISTS (SELECT 1 FROM question_version_asset WHERE asset_id = ? AND version_id = ANY (?))")
                .params(id, versionIds.toArray(UUID[]::new)).query(Boolean.class).single();
        if (!linked) {
            audit.recordDenied("question.asset.delivery_denied", Map.of("assetId", id.toString(), "reason", "not_in_paper"));
            return java.util.Optional.empty();
        }
        record Row(String scanStatus, String servedKey, String mediaType) {}
        Row r = jdbc.sql("SELECT scan_status, served_key, media_type FROM question_asset WHERE id = ?").param(id)
                .query((rs, n) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3))).single();
        if (!deliverable(r.scanStatus(), deployment.environment()) || r.servedKey() == null) {
            audit.recordDenied("question.asset.delivery_denied", Map.of("assetId", id.toString(), "reason", "scan_" + r.scanStatus().toLowerCase(java.util.Locale.ROOT)));
            return java.util.Optional.empty();
        }
        return storage.get(r.servedKey()).map(b -> new Served(b, r.mediaType()));
    }

    /**
     * KI-83: every image of these versions is deliverable. An image block whose version has no asset link (which the
     * editor always writes) also counts as undeliverable, so nothing unexpected passes.
     */
    @Transactional(readOnly = true)
    public boolean imagesDeliverable(java.util.Collection<UUID> versionIds) {
        if (versionIds.isEmpty()) return true;
        UUID[] ids = versionIds.toArray(UUID[]::new);
        boolean unlinked = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM question_version qv WHERE qv.id = ANY (?)
                  AND (jsonb_path_exists(qv.content, 'strict $.**.type ? (@ == "image")') OR jsonb_path_exists(qv.body, 'strict $.**.type ? (@ == "image")'))
                  AND NOT EXISTS (SELECT 1 FROM question_version_asset va WHERE va.version_id = qv.id))""").param(ids).query(Boolean.class).single();
        if (unlinked) return false;
        List<String> statuses = jdbc.sql("""
                SELECT DISTINCT a.scan_status FROM question_version_asset va JOIN question_asset a ON a.id = va.asset_id WHERE va.version_id = ANY (?)""")
                .param(ids).query(String.class).list();
        return statuses.stream().allMatch(st -> deliverable(st, deployment.environment()));
    }

    /** Fail closed: candidates receive scanned-clean images; unscanned ones (development and test uploads) only there. */
    public static boolean deliverable(String scanStatus, DeploymentProperties.Environment env) {
        return "CLEAN".equals(scanStatus) || ("NOT_SCANNED_DEV".equals(scanStatus)
                && (env == DeploymentProperties.Environment.DEVELOPMENT || env == DeploymentProperties.Environment.TEST));
    }

    // ================================================================================================ cleanup

    @Scheduled(cron = "${ivorovi.questions.asset-cleanup-cron:0 41 3 * * *}")
    void scheduledCleanup() {
        if (!cleanupEnabled || !leases.tryAcquire("questions.asset-cleanup", Duration.ofMinutes(50))) return;
        List<UUID> tenants = TenantContext.callAsPlatform(() -> jdbc.sql("SELECT id FROM tenant WHERE status IN ('ACTIVE', 'TRIAL')").query(UUID.class).list());
        for (UUID t : tenants) {
            try {
                TenantContext.runInTenant(t, () -> cleanup(Instant.now().minus(Duration.ofDays(30))));
            } catch (RuntimeException e) {
                log.warn("Question asset cleanup failed for an organization: {}", e.getClass().getSimpleName());
            }
        }
    }

    /**
     * Deletes images older than {@code before} that no question version (draft or not) links to. The database trigger refuses
     * to delete any image still linked, so a race with a new link cannot remove a used image. Returns the number deleted.
     */
    @Transactional
    public int cleanup(Instant before) {
        List<UUID> candidates = jdbc.sql("""
                SELECT a.id FROM question_asset a WHERE a.created_at < ? AND NOT EXISTS (SELECT 1 FROM question_version_asset va WHERE va.asset_id = a.id)""")
                .param(java.sql.Timestamp.from(before)).query(UUID.class).list();
        int n = 0;
        for (UUID id : candidates) {
            record K(String q, String s) {}
            K k = jdbc.sql("SELECT quarantine_key, served_key FROM question_asset WHERE id = ?").param(id).query((rs, i) -> new K(rs.getString(1), rs.getString(2))).single();
            jdbc.sql("DELETE FROM question_asset WHERE id = ?").param(id).update();
            storage.delete(k.q());
            if (k.s() != null) storage.delete(k.s());
            audit.record("question.asset.purged", "question_asset", id.toString(), com.ivorovi.platform.audit.AuditOutcome.SUCCESS, Map.of("reason", "unused for 30 days"));
            n++;
        }
        return n;
    }

    private static String safeName(String name) {
        if (name == null) return null;
        String n = name.replaceAll("[^A-Za-z0-9._ -]", "_");
        return n.length() > 200 ? n.substring(0, 200) : n;
    }

    static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
