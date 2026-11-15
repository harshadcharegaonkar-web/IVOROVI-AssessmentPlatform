package com.ivorovi.platform.crypto;

import com.ivorovi.platform.config.DeploymentProperties;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Application-level encryption of sensitive personal fields (AES-256-GCM, JDK only), with a key ring for rotation
 * (RB-3 / KI-49). The column name and tenant id are bound as associated data, so a value copied to another column or
 * organization does not decrypt.
 * <p>Stored formats (both readable for ever):
 * <ul>
 *   <li>{@code v1:<base64(iv | ciphertext+tag)>}: format 1, always key id {@code 1} (every value written before
 *       rotation existed);</li>
 *   <li>{@code v2:<keyId>:<base64(iv | ciphertext+tag)>}: format 2, names its key.</li>
 * </ul>
 * Encryption uses only the current key. While the current key id is {@code 1}, values are still written in format 1,
 * byte-for-byte as before, so an untouched deployment behaves exactly as it did (and can roll back to a build without
 * key rotation). Decryption picks the key by id; an id that is not configured fails closed.
 * <p>Configuration (from the environment's secret store): {@code IVOROVI_DATA_KEY} (current key),
 * {@code IVOROVI_DATA_KEY_ID} (its id, default {@code 1}), {@code IVOROVI_DATA_KEYS_PREVIOUS}
 * ({@code id:secret,id:secret}) and {@code IVOROVI_DATA_INDEX_KEY_ID} (default {@code 1}): the key of the keyed blind
 * indexes, which must stay configured, because rotating it would change every stored index (see the runbook).
 * Error messages name key ids and fields, never values or key material.
 */
@Component
public class FieldCipher {

    /** Key ids: short operator-chosen labels. */
    public static final Pattern KEY_ID = Pattern.compile("[a-z0-9-]{1,16}");
    /** The key id of every format-1 value. */
    public static final String LEGACY_KEY_ID = "1";

    private static final String V1 = "v1:";
    private static final String V2 = "v2:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int MIN_SECRET = 24;

    private final Map<String, SecretKeySpec> keys;
    private final String currentId;
    private final SecretKeySpec current;
    private final SecretKeySpec indexKey;
    private final SecureRandom random = new SecureRandom();

    /** Single key (id {@code 1}), as before rotation existed; used by tests and tools. */
    FieldCipher(String secret, DeploymentProperties deployment) {
        this(secret, LEGACY_KEY_ID, "", LEGACY_KEY_ID, deployment);
    }

    @Autowired
    public FieldCipher(@Value("${ivorovi.security.data-key:}") String secret,
                       @Value("${ivorovi.security.data-key-id:1}") String currentKeyId,
                       @Value("${ivorovi.security.data-keys-previous:}") String previous,
                       @Value("${ivorovi.security.data-index-key-id:1}") String indexKeyId,
                       DeploymentProperties deployment) {
        String env = deployment == null ? "unknown" : String.valueOf(deployment.environment());
        if (secret == null || secret.length() < MIN_SECRET) {
            throw new IllegalStateException("ivorovi.security.data-key (IVOROVI_DATA_KEY) must be set to at least 24 random characters (" + env + ")");
        }
        String cid = currentKeyId == null || currentKeyId.isBlank() ? LEGACY_KEY_ID : currentKeyId.strip();
        requireId(cid, "IVOROVI_DATA_KEY_ID");
        Map<String, SecretKeySpec> ring = new LinkedHashMap<>();
        ring.put(cid, derive(secret));
        if (previous != null && !previous.isBlank()) {
            for (String entry : previous.split(",")) {
                String e = entry.strip();
                if (e.isEmpty()) continue;
                int colon = e.indexOf(':');
                if (colon <= 0) throw new IllegalStateException("IVOROVI_DATA_KEYS_PREVIOUS: every entry must be <keyId>:<secret> (" + env + ")");
                String id = e.substring(0, colon);
                String s = e.substring(colon + 1);
                requireId(id, "IVOROVI_DATA_KEYS_PREVIOUS");
                if (s.length() < MIN_SECRET) throw new IllegalStateException("IVOROVI_DATA_KEYS_PREVIOUS: the key with id " + id + " is shorter than 24 characters");
                if (ring.containsKey(id)) throw new IllegalStateException("Key id " + id + " is configured more than once (IVOROVI_DATA_KEY_ID / IVOROVI_DATA_KEYS_PREVIOUS)");
                ring.put(id, derive(s));
            }
        }
        String iid = indexKeyId == null || indexKeyId.isBlank() ? LEGACY_KEY_ID : indexKeyId.strip();
        requireId(iid, "IVOROVI_DATA_INDEX_KEY_ID");
        if (!ring.containsKey(iid)) {
            throw new IllegalStateException("The blind-index key id " + iid + " (IVOROVI_DATA_INDEX_KEY_ID) is not configured: keep that key as the current "
                    + "key or in IVOROVI_DATA_KEYS_PREVIOUS, otherwise session codes, e-mail indexes and answer-key hashes no longer match");
        }
        this.keys = Collections.unmodifiableMap(ring);
        this.currentId = cid;
        this.current = ring.get(cid);
        this.indexKey = ring.get(iid);
    }

    private static void requireId(String id, String setting) {
        if (!KEY_ID.matcher(id).matches()) throw new IllegalStateException(setting + ": key ids are 1-16 characters of a-z, 0-9 and '-'");
    }

    private static SecretKeySpec derive(String secret) {
        try {
            byte[] material = MessageDigest.getInstance("SHA-256").digest(("ivorovi-field-encryption-v1:" + secret).getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(material, "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The id of the key new values are encrypted with. */
    public String currentKeyId() {
        return currentId;
    }

    /** Every configured key id (current first). */
    public Set<String> configuredKeyIds() {
        return keys.keySet();
    }

    /**
     * The key id a stored value names: {@code 1} for format 1, the label for format 2, {@code null} for anything else.
     * Never decrypts and never looks at the payload.
     */
    public static String keyIdOf(String stored) {
        if (stored == null) return null;
        if (stored.startsWith(V1)) return LEGACY_KEY_ID;
        if (stored.startsWith(V2)) {
            int end = stored.indexOf(':', V2.length());
            if (end < 0) return null;
            String id = stored.substring(V2.length(), end);
            return KEY_ID.matcher(id).matches() ? id : null;
        }
        return null;
    }

    /** True when the stored value is already encrypted with the current key (nothing to re-encrypt). */
    public boolean isCurrent(String stored) {
        return currentId.equals(keyIdOf(stored));
    }

    public String encrypt(String plaintext, String field, java.util.UUID tenantId) {
        if (plaintext == null || plaintext.isEmpty()) return null;
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, current, new GCMParameterSpec(TAG_BITS, iv));
            c.updateAAD(aad(field, tenantId));
            byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            String payload = Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
            return LEGACY_KEY_ID.equals(currentId) ? V1 + payload : V2 + currentId + ":" + payload;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public String decrypt(String stored, String field, java.util.UUID tenantId) {
        if (stored == null) return null;
        String id = keyIdOf(stored);
        if (id == null) throw new IllegalStateException("Unknown encrypted value format for " + field);
        SecretKeySpec key = keys.get(id);
        if (key == null) throw new IllegalStateException("Encrypted value of " + field + " uses key id " + id + ", which is not configured");
        String payload = stored.startsWith(V1) ? stored.substring(V1.length()) : stored.substring(V2.length() + id.length() + 1);
        try {
            byte[] all = Base64.getDecoder().decode(payload);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            c.updateAAD(aad(field, tenantId));
            return new String(c.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Decryption failed for " + field + " (key id " + id + ")", e);   // never include the value
        }
    }

    /** Decrypts with the value's own key and encrypts with the current key (re-encryption during rotation). */
    public String reencrypt(String stored, String field, java.util.UUID tenantId) {
        return encrypt(decrypt(stored, field, tenantId), field, tenantId);
    }

    private static byte[] aad(String field, java.util.UUID tenantId) {
        return (field + "|" + tenantId).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Keyed one-way index (HMAC-SHA-256 under a key derived from the blind-index data key, {@code IVOROVI_DATA_INDEX_KEY_ID})
     * for low-entropy secrets such as session codes, so a copied database does not allow offline guessing. Lower-case
     * hex, 64 characters. Independent of the current encryption key, so rotation never changes stored indexes.
     */
    public String blindIndex(String value, String purpose) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            byte[] k = MessageDigest.getInstance("SHA-256").digest(concat(indexKey.getEncoded(), ("blind-index-v1:" + purpose).getBytes(StandardCharsets.UTF_8)));
            mac.init(new SecretKeySpec(k, "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }
}
