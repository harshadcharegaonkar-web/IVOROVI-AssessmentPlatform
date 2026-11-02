package com.ivorovi.platform.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Tamper-evidence for the audit trail: each event's hash covers its content and the previous event's hash, so
 * modifying, deleting or reordering any stored event breaks verification from that point on.
 * Details are restricted to string values and hashed in sorted-key order, so the JSONB round trip is stable.
 */
public final class AuditHasher {

    public static final String GENESIS = "0".repeat(64);

    private AuditHasher() {}

    public static String hash(String prevHash, UUID tenantId, long seq, Instant occurredAt, UUID actorId,
                              String actorSubject, String action, String resourceType, String resourceId,
                              AuditOutcome outcome, Map<String, String> details) {
        StringBuilder sb = new StringBuilder(256)
                .append(prevHash).append('\u001f').append(tenantId).append('\u001f').append(seq).append('\u001f')
                .append(occurredAt).append('\u001f').append(Objects.toString(actorId, "")).append('\u001f')
                .append(Objects.toString(actorSubject, "")).append('\u001f').append(action).append('\u001f')
                .append(Objects.toString(resourceType, "")).append('\u001f')
                .append(Objects.toString(resourceId, "")).append('\u001f').append(outcome);
        new TreeMap<>(details).forEach((k, v) -> sb.append('\u001e').append(k).append('=').append(v));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
