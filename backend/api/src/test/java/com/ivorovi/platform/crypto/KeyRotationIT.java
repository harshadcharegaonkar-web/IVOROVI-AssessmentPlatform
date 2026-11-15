package com.ivorovi.platform.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ivorovi.platform.audit.AuditLog;
import com.ivorovi.platform.config.DeploymentProperties;
import com.ivorovi.platform.ids.Ids;
import com.ivorovi.platform.jobs.JobLeases;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.support.IntegrationTest;
import com.ivorovi.support.TestOrg;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * RB-3 / KI-49 against PostgreSQL with RLS: rotation of real encrypted columns (webhook secrets, e-mail recipients) of
 * one organization from key 1 to a new key, with mixed-key data, interruption and resume, a lost progress update,
 * concurrent writers (current and stale configuration), lease exclusivity, fail-closed missing keys, the startup key
 * check, rollback to key 1, operator-only progress, audit without values, and the column registry against the schema.
 * Synthetic keys and data only; every row this test creates is removed afterwards.
 */
class KeyRotationIT extends IntegrationTest {

    private static final DeploymentProperties ENV = new DeploymentProperties(DeploymentProperties.Mode.SAAS, DeploymentProperties.Environment.TEST);
    private static final String OLD = "keyrot-old-test-key-0123456789abcdef";
    private static final String NEW = "keyrot-new-test-key-fedcba9876543210";
    private static final FieldCipher BEFORE = new FieldCipher(OLD, ENV);
    private static final FieldCipher AFTER = new FieldCipher(NEW, "2026a", "1:" + OLD, "1", ENV);
    private static final FieldCipher BACK = new FieldCipher(OLD, "1", "2026a:" + NEW, "1", ENV);
    private static final EncryptedColumns.Column WEBHOOK = EncryptedColumns.ALL.stream().filter(c -> c.table().equals("webhook_endpoint")).findFirst().orElseThrow();
    private static final EncryptedColumns.Column EMAIL = EncryptedColumns.ALL.stream().filter(c -> c.table().equals("email_delivery")).findFirst().orElseThrow();
    /** Real registry entries, webhook secrets first (the batch-count assertions below rely on this order). */
    private static final List<EncryptedColumns.Column> COLUMNS = List.of(WEBHOOK, EMAIL);

    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired AuditLog audit;
    @Autowired KeyRingStartupCheck startupCheck;

    UUID tenant;
    UUID otherTenant;
    final Map<UUID, String> plainSecrets = new java.util.LinkedHashMap<>();
    final Map<UUID, String> plainRecipients = new java.util.LinkedHashMap<>();

    @BeforeEach
    void seed() {
        tenant = new TestOrg(ownerJdbc(), mvc, "keyrot").tenant;
        otherTenant = new TestOrg(ownerJdbc(), mvc, "keyrot-other").tenant;
        for (int i = 0; i < 10; i++) {
            UUID id = Ids.newId();
            String secret = "whsec-synthetic-" + i + "-" + UUID.randomUUID();
            plainSecrets.put(id, secret);
            ownerJdbc().sql("INSERT INTO webhook_endpoint (id, tenant_id, name, url, event_types, secret_enc) VALUES (?, ?, ?, 'https://example.test/hook', '{student.created}', ?)")
                    .params(id, tenant, "Hook " + i, BEFORE.encrypt(secret, WEBHOOK.field(), tenant)).update();
        }
        for (int i = 0; i < 7; i++) {
            UUID id = Ids.newId();
            String to = "person" + i + "@example.test";
            plainRecipients.put(id, to);
            // mixed keys before rotation starts: two values were already written with the new key
            FieldCipher writer = i < 2 ? AFTER : BEFORE;
            ownerJdbc().sql("INSERT INTO email_delivery (id, tenant_id, recipient_enc, category, subject, body, status) VALUES (?, ?, ?, 'TEST', 'Subject', 'Body', 'SENT')")
                    .params(id, tenant, writer.encrypt(to, EMAIL.field(), tenant)).update();
        }
        // another organization's data is never touched by a run limited to this one
        ownerJdbc().sql("INSERT INTO webhook_endpoint (id, tenant_id, name, url, event_types, secret_enc) VALUES (?, ?, 'Other', 'https://example.test/o', '{student.created}', ?)")
                .params(Ids.newId(), otherTenant, BEFORE.encrypt("other-org-secret", WEBHOOK.field(), otherTenant)).update();
    }

    @AfterEach
    void cleanUp() {
        for (UUID t : List.of(tenant, otherTenant)) {
            ownerJdbc().sql("DELETE FROM webhook_endpoint WHERE tenant_id = ?").param(t).update();
            ownerJdbc().sql("DELETE FROM email_delivery WHERE tenant_id = ?").param(t).update();
        }
    }

    private KeyRotationJob job(FieldCipher ring, String owner, int batchSize, int batchesPerRun) {
        KeyRotationJob j = new KeyRotationJob(jdbc, tx, ring, new JobLeases(jdbc, tx, owner), audit, true, batchSize, batchesPerRun, COLUMNS);
        j.tenantFilter = tenant::equals;
        return j;
    }

    private Map<UUID, String> stored(EncryptedColumns.Column c, UUID t) {
        Map<UUID, String> m = new java.util.HashMap<>();
        ownerJdbc().sql("SELECT " + c.idColumn() + ", " + c.column() + " FROM " + c.table() + " WHERE tenant_id = ?").param(t)
                .query((rs, n) -> m.put(rs.getObject(1, UUID.class), rs.getString(2))).list();
        return m;
    }

    private Set<String> keyIds(EncryptedColumns.Column c, UUID t) {
        return stored(c, t).values().stream().map(FieldCipher::keyIdOf).collect(Collectors.toSet());
    }

    private void assertAllDecrypt(FieldCipher ring) {
        stored(WEBHOOK, tenant).forEach((id, v) -> assertThat(ring.decrypt(v, WEBHOOK.field(), tenant)).isEqualTo(plainSecrets.get(id)));
        stored(EMAIL, tenant).forEach((id, v) -> assertThat(ring.decrypt(v, EMAIL.field(), tenant)).isEqualTo(plainRecipients.get(id)));
    }

    private Map<String, Object> progress(String target, String field) {
        return ownerJdbc().sql("SELECT last_id, rewritten, conflicts, passes, completed_at IS NOT NULL AS done FROM key_rotation_progress WHERE target_key_id = ? AND tenant_id = ? AND field = ?")
                .params(target, tenant, field).query().singleRow();
    }

    @Test
    void rotatesMixedKeyDataInResumableBatchesAndCanRollBack() {
        assertThat(keyIds(WEBHOOK, tenant)).containsExactly("1");
        assertThat(keyIds(EMAIL, tenant)).containsExactlyInAnyOrder("1", "2026a");
        String otherBefore = stored(WEBHOOK, otherTenant).values().iterator().next();

        // interrupted: one batch of 3 per run; progress (resume point) is stored after each batch
        KeyRotationJob first = job(AFTER, "instance-a", 3, 1);
        KeyRotationJob.RunResult r1 = first.runOnce();
        assertThat(r1.batches()).isEqualTo(1);
        assertThat(r1.rewritten()).isEqualTo(3);
        assertThat(r1.finished()).isFalse();
        assertThat(progress("2026a", WEBHOOK.field()).get("last_id")).isNotNull();
        assertThat(keyIds(WEBHOOK, tenant)).containsExactlyInAnyOrder("1", "2026a");
        assertAllDecrypt(AFTER);                                            // mixed data stays readable mid-rotation

        // a lost progress update (crash after the batch committed): the resume point goes back; no value is processed twice
        ownerJdbc().sql("UPDATE key_rotation_progress SET last_id = NULL WHERE target_key_id = '2026a' AND tenant_id = ?").param(tenant).update();

        // resumed by another instance (the first one stopped)
        KeyRotationJob second = job(AFTER, "instance-b", 3, 1000);
        KeyRotationJob.RunResult r2 = second.runOnce();
        assertThat(r2.finished()).isTrue();
        assertThat(r1.rewritten() + r2.rewritten()).as("10 webhook secrets + 5 old-key recipients, each exactly once").isEqualTo(15);
        assertThat(r2.conflicts()).isZero();
        assertThat(keyIds(WEBHOOK, tenant)).containsExactly("2026a");
        assertThat(keyIds(EMAIL, tenant)).containsExactly("2026a");
        assertAllDecrypt(AFTER);
        assertAllDecrypt(new FieldCipher(NEW, "2026a", "", "2026a", ENV));  // the old key is no longer needed for these values
        assertThat(progress("2026a", WEBHOOK.field())).containsEntry("done", true).containsEntry("rewritten", 10L);
        assertThat(progress("2026a", EMAIL.field())).containsEntry("done", true).containsEntry("rewritten", 5L);
        assertThat(stored(WEBHOOK, otherTenant).values()).containsExactly(otherBefore);

        // idempotent: another run changes nothing
        Map<UUID, String> snapshot = stored(WEBHOOK, tenant);
        KeyRotationJob.RunResult r3 = second.runOnce();
        assertThat(r3.rewritten()).isZero();
        assertThat(r3.finished()).isTrue();
        assertThat(stored(WEBHOOK, tenant)).isEqualTo(snapshot);

        // rollback: key 1 current again, the new key kept as previous; values return to format 1
        KeyRotationJob.RunResult back = job(BACK, "instance-a", 50, 100).runOnce();
        assertThat(back.rewritten()).isEqualTo(17);
        assertThat(keyIds(WEBHOOK, tenant)).containsExactly("1");
        assertThat(stored(WEBHOOK, tenant).values()).allMatch(v -> v.startsWith("v1:"));
        assertAllDecrypt(BEFORE);                                           // readable by a build without key rotation

        // audit per organization and field: key ids and counts only
        List<String> details = ownerJdbc().sql("SELECT details::text FROM audit_event WHERE tenant_id = ? AND action = 'platform.key_rotation.field_completed'")
                .param(tenant).query(String.class).list();
        assertThat(details).hasSizeGreaterThanOrEqualTo(4);
        for (String d : details) {
            assertThat(d).contains("targetKeyId").doesNotContain("v1:").doesNotContain("v2:").doesNotContain("whsec-").doesNotContain("@example.test")
                    .doesNotContain(OLD).doesNotContain(NEW);
        }
        // progress is operator-only: invisible in the organization's own context and without context
        long visible = TenantContext.callInTenant(tenant, () -> tx.execute(s -> jdbc.sql("SELECT count(*) FROM key_rotation_progress").query(Long.class).single()));
        assertThat(visible).isZero();
        assertThat(rawAppJdbc().sql("SELECT count(*) FROM key_rotation_progress").query(Long.class).single()).isZero();
        String progressText = ownerJdbc().sql("SELECT string_agg(k::text, '|') FROM key_rotation_progress k WHERE tenant_id = ?").param(tenant).query(String.class).single();
        assertThat(progressText).doesNotContain("v1:").doesNotContain("v2:").doesNotContain("whsec-").doesNotContain("@example.test");
    }

    @Test
    void concurrentWritersWinAndStaleWritersAreCaughtByVerification() {
        UUID victim = plainSecrets.keySet().iterator().next();
        UUID stale = new ArrayList<>(plainSecrets.keySet()).get(1);
        AtomicBoolean once = new AtomicBoolean();
        KeyRotationJob j = job(AFTER, "instance-a", 100, 100);
        // between the job's read and its write, an application instance changes two secrets:
        // one already on the new configuration, one still on the old configuration (rolling deployment)
        j.beforeWrite = () -> {
            if (!once.compareAndSet(false, true)) return;
            plainSecrets.put(victim, "rotated-by-writer");
            plainSecrets.put(stale, "written-by-old-instance");
            TenantContext.runInTenant(tenant, () -> tx.executeWithoutResult(s -> {
                jdbc.sql("UPDATE webhook_endpoint SET secret_enc = ? WHERE id = ?").params(AFTER.encrypt("rotated-by-writer", WEBHOOK.field(), tenant), victim).update();
                jdbc.sql("UPDATE webhook_endpoint SET secret_enc = ? WHERE id = ?").params(BEFORE.encrypt("written-by-old-instance", WEBHOOK.field(), tenant), stale).update();
            }));
        };
        KeyRotationJob.RunResult r = j.runOnce();
        assertThat(r.conflicts()).as("the job never overwrites a concurrent writer").isEqualTo(2);
        Map<UUID, String> now = stored(WEBHOOK, tenant);
        assertThat(AFTER.decrypt(now.get(victim), WEBHOOK.field(), tenant)).isEqualTo("rotated-by-writer");
        assertThat(FieldCipher.keyIdOf(now.get(stale))).as("stale writer's value kept as written").isEqualTo("1");
        assertThat(progress("2026a", WEBHOOK.field())).containsEntry("done", true);

        // the next run's verification finds the value not on the current key, reopens the field and re-encrypts it
        KeyRotationJob.RunResult verify = j.runOnce();
        assertThat(verify.rewritten()).isEqualTo(1);
        assertThat(keyIds(WEBHOOK, tenant)).containsExactly("2026a");
        assertAllDecrypt(AFTER);
        assertThat(progress("2026a", WEBHOOK.field())).containsEntry("done", true).containsEntry("passes", 2);
    }

    @Test
    void onlyTheLeaseHolderRunsAndAMissingKeyFailsClosed() {
        KeyRotationJob a = job(AFTER, "instance-a", 2, 1);
        KeyRotationJob b = job(AFTER, "instance-b", 2, 1000);
        ownerJdbc().sql("DELETE FROM job_lease WHERE name = ?").param(KeyRotationJob.JOB).update();
        a.scheduled();                                                      // A takes the lease and does one batch
        Map<UUID, String> afterA = stored(WEBHOOK, tenant);
        b.scheduled();                                                      // B is refused while A's lease is valid
        assertThat(stored(WEBHOOK, tenant)).isEqualTo(afterA);
        assertThat(keyIds(WEBHOOK, tenant)).containsExactlyInAnyOrder("1", "2026a");
        ownerJdbc().sql("DELETE FROM job_lease WHERE name = ?").param(KeyRotationJob.JOB).update();

        // the configuration lost the old key: nothing is rewritten, the error names no value
        FieldCipher lost = new FieldCipher(NEW, "2026a", "", "2026a", ENV);
        Map<UUID, String> before = stored(WEBHOOK, tenant);
        assertThatThrownBy(() -> job(lost, "instance-c", 100, 100).runOnce()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("key id 1").hasMessageContaining("not configured").hasMessageNotContaining("whsec-").hasMessageNotContaining("v1:");
        assertThat(stored(WEBHOOK, tenant)).isEqualTo(before);
        // a wrong secret behind the right id: decryption fails, nothing is written
        FieldCipher wrong = new FieldCipher(NEW, "2026a", "1:another-wrong-test-key-0000000000", "1", ENV);
        assertThatThrownBy(() -> job(wrong, "instance-c", 100, 100).runOnce()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Decryption failed").hasMessageNotContaining("whsec-");
        assertThat(stored(WEBHOOK, tenant)).isEqualTo(before);
    }

    @Test
    void startupCheckRefusesKeyIdsThatAreNotConfigured() {
        job(AFTER, "instance-a", 100, 100).runOnce();                      // this organization's values now name key 2026a
        assertThat(startupCheck.findMissing(AFTER)).isEmpty();
        // a configuration without key 2026a (e.g. a rollback of the configuration only)
        List<KeyRingStartupCheck.Missing> missing = startupCheck.findMissing(BEFORE);
        assertThat(missing).extracting(KeyRingStartupCheck.Missing::field).contains(WEBHOOK.field(), EMAIL.field());
        assertThat(missing).allMatch(m -> m.keyId().equals("2026a"));
        assertThat(missing.stream().filter(m -> m.field().equals(WEBHOOK.field())).findFirst().orElseThrow().values()).isEqualTo(10);
        KeyRingStartupCheck check = new KeyRingStartupCheck(jdbc, tx, BEFORE, true);
        assertThatThrownBy(check::onStarted).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Refusing to start").hasMessageContaining("webhook.secret key id 2026a").hasMessageContaining("IVOROVI_DATA_KEYS_PREVIOUS")
                .hasMessageNotContaining("v2:").hasMessageNotContaining("whsec-").hasMessageNotContaining(NEW);
        // an unknown format is reported as such
        ownerJdbc().sql("UPDATE webhook_endpoint SET secret_enc = 'not-a-ciphertext' WHERE id = ?").param(plainSecrets.keySet().iterator().next()).update();
        assertThat(startupCheck.findMissing(AFTER)).extracting(KeyRingStartupCheck.Missing::keyId).containsExactly("?");
        assertThatThrownBy(new KeyRingStartupCheck(jdbc, tx, AFTER, true)::onStarted).hasMessageContaining("(unknown format)")
                .hasMessageNotContaining("not-a-ciphertext");
    }

    @Test
    void theRegistryListsEveryEncryptedColumnOfTheSchema() {
        Set<String> schema = new HashSet<>(ownerJdbc().sql("""
                SELECT table_name || '.' || column_name FROM information_schema.columns
                WHERE table_schema = current_schema() AND column_name LIKE '%\\_enc'""").query(String.class).list());
        assertThat(EncryptedColumns.ALL).extracting(EncryptedColumns.Column::name).containsExactlyInAnyOrderElementsOf(schema);
        for (EncryptedColumns.Column c : EncryptedColumns.ALL) {
            assertThat(ownerJdbc().sql("SELECT count(*) FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?")
                    .params(c.table(), c.idColumn()).query(Long.class).single()).as(c.name() + " id column").isEqualTo(1);
        }
    }
}
