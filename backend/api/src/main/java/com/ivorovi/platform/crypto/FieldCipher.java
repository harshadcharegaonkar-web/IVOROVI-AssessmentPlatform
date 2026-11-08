package com.ivorovi.platform.crypto;

import com.ivorovi.platform.config.DeploymentProperties;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Application-level encryption of sensitive personal fields (AES-256-GCM, JDK only). Values are stored as
 * {@code v1:<base64(iv | ciphertext+tag)>}; the column name and tenant id are bound as associated data, so a value
 * copied to another column or organization does not decrypt. The key is derived (SHA-256) from
 * {@code ivorovi.security.data-key}, which must be supplied from the environment's secret store; the application
 * refuses to start without it.
 */
@Component
public class FieldCipher {

    private static final String PREFIX = "v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    FieldCipher(@Value("${ivorovi.security.data-key:}") String secret, DeploymentProperties deployment) {
        if (secret == null || secret.length() < 24) {
            throw new IllegalStateException("ivorovi.security.data-key (IVOROVI_DATA_KEY) must be set to at least 24 random characters ("
                    + deployment.environment() + ")");
        }
        try {
            byte[] material = MessageDigest.getInstance("SHA-256").digest(("ivorovi-field-encryption-v1:" + secret).getBytes(StandardCharsets.UTF_8));
            this.key = new SecretKeySpec(material, "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public String encrypt(String plaintext, String field, java.util.UUID tenantId) {
        if (plaintext == null || plaintext.isEmpty()) return null;
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            c.updateAAD(aad(field, tenantId));
            byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public String decrypt(String stored, String field, java.util.UUID tenantId) {
        if (stored == null) return null;
        if (!stored.startsWith(PREFIX)) throw new IllegalStateException("Unknown encrypted value format for " + field);
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            c.updateAAD(aad(field, tenantId));
            return new String(c.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Decryption failed for " + field, e);   // never include the value
        }
    }

    private static byte[] aad(String field, java.util.UUID tenantId) {
        return (field + "|" + tenantId).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Keyed one-way index (HMAC-SHA-256 under a key derived from the data key) for low-entropy secrets such as session
     * codes, so a copied database does not allow offline guessing. Lower-case hex, 64 characters.
     */
    public String blindIndex(String value, String purpose) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            byte[] k = MessageDigest.getInstance("SHA-256").digest(concat(key.getEncoded(), ("blind-index-v1:" + purpose).getBytes(StandardCharsets.UTF_8)));
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
