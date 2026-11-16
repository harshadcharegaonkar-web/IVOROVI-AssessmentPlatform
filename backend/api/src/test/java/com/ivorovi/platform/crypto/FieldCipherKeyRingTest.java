package com.ivorovi.platform.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ivorovi.platform.config.DeploymentProperties;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * RB-3 key ring: backward compatibility with values written by the single-key FieldCipher, mixed-key decryption,
 * encryption with the current key only, fail-closed unknown and wrong keys, stable blind indexes, and configuration
 * validation. Synthetic keys and data only.
 */
class FieldCipherKeyRingTest {

    private static final DeploymentProperties ENV = new DeploymentProperties(DeploymentProperties.Mode.SAAS, DeploymentProperties.Environment.TEST);
    private static final UUID TENANT = UUID.fromString("0192e000-0000-7000-8000-0000000000aa");
    private static final String FIELD = "candidate_account.email";
    private static final String OLD = "golden-test-key-0123456789abcdef";
    private static final String NEW = "rotated-test-key-fedcba9876543210";

    /** Written by the unmodified single-key FieldCipher (main a89b893) with key OLD; must decrypt for ever. */
    private static final String GOLDEN_V1 = "v1:mnBUA9U0zNnRZLI912gYI8C7ZsFJrwkEcKFRUjLZNPRaC+Qt3YbcLzL4iluqn88E9DIDfUT+";
    private static final String GOLDEN_PLAIN = "golden.person@example.test";
    /** blindIndex("golden-value", "golden-purpose") of the same unmodified class and key. */
    private static final String GOLDEN_INDEX = "85fdd7b89322742526bbe0d0ff38cc6b7ae0337df7f2b52ff1da3fe882cc7461";

    private static FieldCipher ring(String current, String id, String previous) {
        return new FieldCipher(current, id, previous, "1", ENV);
    }

    @Test
    void valuesWrittenByTheSingleKeyFormatStillDecryptAndIndexesAreUnchanged() {
        FieldCipher untouched = new FieldCipher(OLD, ENV);
        assertThat(untouched.decrypt(GOLDEN_V1, FIELD, TENANT)).isEqualTo(GOLDEN_PLAIN);
        assertThat(untouched.blindIndex("golden-value", "golden-purpose")).isEqualTo(GOLDEN_INDEX);
        // an untouched deployment (id 1) keeps writing format 1, readable by the previous build
        assertThat(untouched.encrypt("x", FIELD, TENANT)).startsWith("v1:");
        assertThat(ring(OLD, "1", "").encrypt("x", FIELD, TENANT)).startsWith("v1:");
        // after rotation: the old key as previous id 1 still reads format-1 values; indexes stay on key 1
        FieldCipher rotated = ring(NEW, "2026a", "1:" + OLD);
        assertThat(rotated.decrypt(GOLDEN_V1, FIELD, TENANT)).isEqualTo(GOLDEN_PLAIN);
        assertThat(rotated.blindIndex("golden-value", "golden-purpose")).isEqualTo(GOLDEN_INDEX);
    }

    @Test
    void encryptsWithTheCurrentKeyAndDecryptsMixedKeys() {
        FieldCipher before = new FieldCipher(OLD, ENV);
        FieldCipher after = ring(NEW, "2026a", "1:" + OLD);
        String oldValue = before.encrypt("alice@example.test", FIELD, TENANT);
        String newValue = after.encrypt("bob@example.test", FIELD, TENANT);
        assertThat(newValue).startsWith("v2:2026a:");
        assertThat(FieldCipher.keyIdOf(oldValue)).isEqualTo("1");
        assertThat(FieldCipher.keyIdOf(newValue)).isEqualTo("2026a");
        assertThat(after.decrypt(oldValue, FIELD, TENANT)).isEqualTo("alice@example.test");
        assertThat(after.decrypt(newValue, FIELD, TENANT)).isEqualTo("bob@example.test");
        assertThat(after.isCurrent(newValue)).isTrue();
        assertThat(after.isCurrent(oldValue)).isFalse();
        String re = after.reencrypt(oldValue, FIELD, TENANT);
        assertThat(re).startsWith("v2:2026a:").isNotEqualTo(oldValue);
        assertThat(after.decrypt(re, FIELD, TENANT)).isEqualTo("alice@example.test");
        // the new key alone (old key retired; index key moved too, which needs index re-hashing) reads re-encrypted values only
        FieldCipher retired = new FieldCipher(NEW, "2026a", "", "2026a", ENV);
        assertThat(retired.decrypt(re, FIELD, TENANT)).isEqualTo("alice@example.test");
        assertThatThrownBy(() -> retired.decrypt(oldValue, FIELD, TENANT)).isInstanceOf(IllegalStateException.class);
        // associated data still binds field and tenant
        assertThatThrownBy(() -> after.decrypt(newValue, "guardian.email", TENANT)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> after.decrypt(newValue, FIELD, UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unknownAndWrongKeysFailClosedWithoutRevealingValues() {
        FieldCipher after = ring(NEW, "2026a", "1:" + OLD);
        String value = after.encrypt("secret-person@example.test", FIELD, TENANT);
        // old build / old configuration: does not know key id 2026a
        FieldCipher before = new FieldCipher(OLD, ENV);
        assertThatThrownBy(() -> before.decrypt(value, FIELD, TENANT)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("2026a").hasMessageContaining("not configured").hasMessageNotContaining(value).hasMessageNotContaining("secret-person");
        // right id, wrong secret behind it
        FieldCipher wrong = ring("another-test-key-000000000000000", "2026a", "1:" + OLD);
        assertThatThrownBy(() -> wrong.decrypt(value, FIELD, TENANT)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Decryption failed").hasMessageNotContaining(value).hasMessageNotContaining("another-test-key");
        // malformed and unknown formats
        for (String bad : new String[] {"plain text", "v3:x:abc", "v2:", "v2:UPPER:abc", "v2:toolongkeyid-0123456789:abc"}) {
            assertThatThrownBy(() -> after.decrypt(bad, FIELD, TENANT)).isInstanceOf(IllegalStateException.class).hasMessageNotContaining(bad);
            assertThat(FieldCipher.keyIdOf(bad)).isNull();
        }
        assertThatThrownBy(() -> after.decrypt("v2:2026a:%%%notbase64", FIELD, TENANT)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void configurationIsValidated() {
        assertThatThrownBy(() -> ring("short", "1", "")).hasMessageContaining("at least 24");
        assertThatThrownBy(() -> ring(NEW, "Bad Id", "")).hasMessageContaining("key ids");
        assertThatThrownBy(() -> ring(NEW, "2026a", "1")).hasMessageContaining("<keyId>:<secret>");
        assertThatThrownBy(() -> ring(NEW, "2026a", "1:short")).hasMessageContaining("shorter than 24").hasMessageNotContaining("short,");
        assertThatThrownBy(() -> ring(NEW, "2026a", "2026a:" + OLD)).hasMessageContaining("more than once");
        // the blind-index key must stay configured
        assertThatThrownBy(() -> ring(NEW, "2026a", "")).hasMessageContaining("IVOROVI_DATA_INDEX_KEY_ID");
        assertThatThrownBy(() -> new FieldCipher(NEW, "2026a", "", "2026a", ENV).decrypt(GOLDEN_V1, FIELD, TENANT)).isInstanceOf(IllegalStateException.class);
        // messages never contain secrets
        assertThatThrownBy(() -> ring(NEW, "2026a", "1:" + OLD + ",x:tiny")).hasMessageNotContaining(OLD).hasMessageNotContaining(NEW);
        FieldCipher ok = ring(NEW, "2026a", " 1:" + OLD + " , ");
        assertThat(ok.configuredKeyIds()).containsExactly("2026a", "1");
        assertThat(ok.currentKeyId()).isEqualTo("2026a");
    }
}
