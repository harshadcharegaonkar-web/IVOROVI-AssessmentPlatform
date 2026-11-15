# Field-encryption key (`IVOROVI_DATA_KEY`): management, backup, recovery and rotation

Status: **required before production** (follow-up of the increment 1.5 acceptance). This runbook describes the
procedures; executing them in a real environment (secret store, access lists, a recovery drill) is a release task that
has not been performed yet. Tracking: KI-49 (rotation tooling), KI-51 (secret-store operation and recovery drill).

## What the key protects

`FieldCipher` encrypts sensitive fields with AES-256-GCM. The AES key is derived (SHA-256) from the configured key
secret, and the column name and organization are bound as associated data. The encrypted fields are:
- student and guardian personal data, and student import rows;
- candidate and enrolment e-mail addresses;
- e-mail delivery recipients;
- integration and webhook secrets;
- answer keys and explanations, and question import rows;
- paper seeds.

The complete list of 19 columns is `platform/crypto/EncryptedColumns.java`, which a test keeps equal to the schema.

Stored formats:
- `v1:<base64(iv | ciphertext+tag)>`: every value written with key id `1`, the only key before rotation;
- `v2:<keyId>:<base64(…)>`: values written with another key.

The same key also derives the **blind-index key**: keyed hashes of session codes, candidate and enrolment e-mail
indexes, and answer-key hashes (`IVOROVI_DATA_INDEX_KEY_ID`, see Rotation).

* Losing the key makes every encrypted field **permanently unreadable**. Database backups without the key cannot restore
  that data.
* Anyone who has both the key and a database copy can read the fields. The key must never be stored next to database
  backups, in Git, in images or in logs.

## Secret management (production and staging)

| Requirement | How |
|---|---|
| Source | A managed secret store (for example a cloud KMS-backed secret manager or HashiCorp Vault), injected into the API at start-up as an environment variable or a mounted file. Never in `application.yml`, compose files or images |
| Generation | At least 32 random bytes from a CSPRNG, encoded (for example `openssl rand -base64 48`). One key per environment; staging and production never share a key |
| Access | Readable only by the API's runtime identity and by two named key custodians (break-glass). Every read is logged by the secret store |
| Separation | Database backups and key backups are held by different systems and different administrators |
| Start-up check | The API refuses to start when the key is missing or shorter than 24 characters, when the key configuration is malformed, or when a stored value names a key id that is not configured (`KeyRingStartupCheck`; message lists field, key id and counts, never values) |
| Monitoring | Alert on secret-store reads outside deployments, and on API start-up failures that mention `IVOROVI_DATA_KEY` |

## Backup

1. When a key is created, store an **offline escrow copy** (for example split into two shares held by two custodians, or
   sealed in an HSM-backed escrow) in addition to the secret store's own versioned backup.
2. Record the key version (secret-store version id, creation date, custodians) in the operations register. Never record
   the key value.
3. Verify the escrow copy **every quarter** during the recovery drill below.

## Recovery

| Situation | Procedure |
|---|---|
| API cannot read the secret (outage) | Restore secret-store access; the API retries on restart. Do not generate a new key: data encrypted with the old key would become unreadable |
| Secret deleted or corrupted | Restore the previous secret version from the secret store's history, or rebuild it from the escrow shares; restart the API; verify with the recovery check below |
| Database restored from backup | Use the key that was active when the backup was taken (the operations register links backups to key versions) |
| Key suspected compromised | Rotate (below) immediately, review secret-store access logs, and follow the incident-response process (including DPDP breach-notification assessment by the data-protection officer) |

**Recovery check:** sign in as an administrator holding `student.personal.read` and open a student's *Personal
information* tab (or run the smoke test). A wrong key produces `Decryption failed for <field>` errors in the API log
(the field name is logged, never the value), and the page reports an error instead of showing data.

**Quarterly recovery drill (staging):** restore the latest staging database backup and the escrowed key into an
isolated environment, start the API, run the recovery check, and record the result.

## Rotation (RB-3 / KI-49)

Rotation needs **no downtime**. The API holds a key ring: one current key, which encrypts new values, plus
decrypt-only previous keys. A leased background job re-encrypts stored values to the current key.

### Configuration

| Variable | Meaning |
|---|---|
| `IVOROVI_DATA_KEY` | Secret of the **current** key (≥ 24 characters, from the secret store) |
| `IVOROVI_DATA_KEY_ID` | Id of the current key, `[a-z0-9-]{1,16}`. Default `1`. While it is `1`, values are written in format `v1:` exactly as before rotation existed |
| `IVOROVI_DATA_KEYS_PREVIOUS` | Decrypt-only keys: `id:secret,id:secret` (from the secret store). Ids must be unique |
| `IVOROVI_DATA_INDEX_KEY_ID` | Key of the blind indexes. Default `1`. **Must stay configured**, as the current key or in the previous list: changing it changes every stored session-code hash, e-mail index and answer-key hash |
| `IVOROVI_KEY_ROTATION_ENABLED` | `true` runs the re-encryption job. Default `false` |
| `IVOROVI_KEY_ROTATION_BATCH_SIZE` / `_BATCHES_PER_RUN` / `_INTERVAL` | Defaults: 500 values per batch, 20 batches per run, a run every 30 s |
| `IVOROVI_KEY_STARTUP_CHECK` | Default `true`. Turn it off only temporarily, with a written reason. It reads only key-id prefixes, once per organization and table at start-up |

**How the job works** (`platform/crypto/KeyRotationJob.java`):
- One instance holds the lease `platform.key-rotation` at a time.
- It works one organization at a time, inside that organization's row-level-security context, in id order. It selects
  only values not on the current key and replaces each with compare-and-set on the old ciphertext, so a concurrent
  application write always wins.
- Progress lives in `key_rotation_progress`. It is visible only in platform context; organizations cannot read it.
  Stopping and restarting at any point is safe: no value is processed twice and none is skipped.
- When a field of an organization completes, the job writes an audit record in that organization:
  `platform.key_rotation.field_completed`, with the target key id and counts.
- A completed field is re-checked on later runs and reopened if old-key values appear, for example written by an
  instance that still had the old configuration.
- Logs, audit records and errors contain field names, key ids and counts, never values or key material.

**Not yet covered (open, see KI-49):**
- **Answer keys and explanations** (`question_answer_key`) are not handled by the platform job or the startup check at
  all. By ADR-0009 only the question module's answer-key store may read that table, and its trigger refuses updates of
  non-draft versions. Their key ids show up only in the verification query below.
- **Paper seeds** (`assessment_paper`) are immutable; the trigger refuses updates. The job counts them and reports them
  as pending guarded fields, but does not rewrite them.
- Until both are resolved, key `1` cannot be retired.
- Rotating the blind-index key needs re-hashing of every index column; no tool does that yet.

### Procedure: rotate to a new key

Example new id: `2026a`. Run it in staging first.

1. **Prepare.** Generate the new secret (`openssl rand -base64 48`). Store it in the secret store as a new secret and in
   escrow, and record id, version and custodians in the operations register (never the value). Take a database backup
   and link it to the current key version.
2. **Roll the new configuration out to every API and delivery instance:**
   - `IVOROVI_DATA_KEY` = new secret;
   - `IVOROVI_DATA_KEY_ID` = `2026a`;
   - `IVOROVI_DATA_KEYS_PREVIOUS` = `1:<old secret>`;
   - `IVOROVI_DATA_INDEX_KEY_ID` = `1` (unchanged).

   New writes now use `v2:2026a:`, and old values stay readable. During a rolling deployment, instances still on the
   old configuration cannot read `2026a` values and still write key-1 values. Keep the rollout short; the job's
   verification picks up the stray key-1 values afterwards.
3. **Start re-encryption.** Set `IVOROVI_KEY_ROTATION_ENABLED=true` on the API instances (restart or redeploy). Watch:
   - the log line `Key rotation to key id 2026a: … values re-encrypted … finished=…`;
   - the progress query below.
4. **Verify.** Run the verification query below as the schema owner. Every field must show key id `2026a` only, except
   the guarded fields listed above. `key_rotation_progress` must show `completed_at` for every organization and field,
   and the recovery check must pass.
5. **Finish.** Set `IVOROVI_KEY_ROTATION_ENABLED=false`, or leave it on: idle runs only count. Keep `1:<old secret>` in
   `IVOROVI_DATA_KEYS_PREVIOUS` while any of the following holds:
   - guarded fields still use key 1;
   - key 1 is the blind-index key, which it is today;
   - backups taken before the rotation may still be restored.

   Removing a key that stored values still name makes the API refuse to start, by design (fail closed).

### Rollback

- **During or after re-encryption:** roll the previous configuration back with the new key kept for decryption:
  - `IVOROVI_DATA_KEY` = old secret;
  - `IVOROVI_DATA_KEY_ID` = `1`;
  - `IVOROVI_DATA_KEYS_PREVIOUS` = `2026a:<new secret>`.

  Leave the job enabled: it re-encrypts the `2026a` values back to key 1 (format `v1:`). When the verification query
  shows only key `1`, the build can even be rolled back to one without key rotation.
- **Never** roll back to a configuration that lacks a key id still present in the data. The startup check refuses it
  and names the fields and key ids.
- **Restoring a database backup:** configure every key id that the backup's values name. Run the verification query
  on the restored database first.

### Verification and progress queries (schema owner, read-only)

Key ids in use, per field, for all organizations and all 19 encrypted columns: the `EncryptedColumns` list plus the
two answer-key columns. Update it when an encrypted column is added; `KeyRotationIT` fails when the list and the
schema differ.

```sql
SELECT 'assessment_paper.seed' AS field, CASE WHEN seed_enc LIKE 'v1:%' THEN '1' WHEN seed_enc ~ '^v2:[a-z0-9-]{1,16}:' THEN split_part(seed_enc, ':', 2) ELSE '?' END AS key_id, count(*) FROM ivorovi.assessment_paper WHERE seed_enc IS NOT NULL GROUP BY 1, 2
UNION ALL SELECT 'candidate_account.email' AS field, CASE WHEN email_enc LIKE 'v1:%' THEN '1' WHEN email_enc ~ '^v2:[a-z0-9-]{1,16}:' THEN split_part(email_enc, ':', 2) ELSE '?' END AS key_id, count(*) FROM ivorovi.candidate_account WHERE email_enc IS NOT NULL GROUP BY 1, 2
UNION ALL SELECT 'email_delivery.recipient' AS field, CASE WHEN recipient_enc LIKE 'v1:%' THEN '1' WHEN recipient_enc ~ '^v2:[a-z0-9-]{1,16}:' THEN split_part(recipient_enc, ':', 2) ELSE '?' END AS key_id, count(*) FROM ivorovi.email_delivery WHERE recipient_enc IS NOT NULL GROUP BY 1, 2
UNION ALL SELECT 'exam_enrolment.email' AS field, CASE WHEN email_enc LIKE 'v1:%' THEN '1' WHEN email_enc ~ '^v2:[a-z0-9-]{1,16}:' THEN split_part(email_enc, ':', 2) ELSE '?' END AS key_id, count(*) FROM ivorovi.exam_enrolment WHERE email_enc IS NOT NULL GROUP BY 1, 2
UNION ALL SELECT 'guardian.address' AS field, CASE WHEN address_enc LIKE 'v1:%' THEN '1' WHEN address_enc ~ '^v2:[a-z0-9-]{1,16}:' THEN split_part(address_enc, ':', 2) ELSE '?' END AS key_id, count(*) FROM ivorovi.guardian WHERE address_enc IS NOT NULL GROUP BY 1, 2
UNION ALL SELECT 'guardian.email' AS field, CASE WHEN email_enc LIKE 'v1:%' THEN '1' WHEN email_enc ~ '^v2:[a-z0-9-]{1,16}:' THEN split_part(email_enc, ':', 2) ELSE '?' END AS key_id, count(*) FROM ivorovi.guardian WHERE email_enc IS NOT NULL GROUP BY 1, 2
UNION ALL SELECT 'guardian.mobile' AS field, CASE WHEN mobile_enc LIKE 'v1:%' THEN '1' WHEN mobile_enc ~ '^v2:[a-z0-9-]{1,16}:' THEN split_part(mobile_enc, ':', 2) ELSE '?' END AS key_id, count(*) FROM ivorovi.guardian WHERE mobile_enc IS NOT NULL GROUP BY 1, 2
UNION ALL SELECT 'integration.secret' AS field, CASE WHEN secret_enc LIKE 'v1:%' THEN '1' WHEN secret_enc ~ '^v2:[a-z0-9-]{1,16}:' THEN split_part(secret_enc, ':', 2) ELSE '?' END AS key_id, count(*) FROM ivorovi.integration WHERE secret_enc IS NOT NULL GROUP BY 1, 2
UNION ALL SELECT 'question_answer_key.explanation' AS field, CASE WHEN explanation_enc LIKE 'v1:%' THEN '1' WHEN explanation_enc ~ '^v2:[a-z0-9-]{1,16}:' THEN split_part(explanation_enc, ':', 2) ELSE '?' END AS key_id, count(*) FROM ivorovi.question_answer_key WHERE explanation_enc IS NOT NULL GROUP BY 1, 2
UNION ALL SELECT 'question_answer_key.key' AS field, CASE WHEN key_enc LIKE 'v1:%' THEN '1' WHEN key_enc ~ '^v2:[a-z0-9-]{1,16}:' THEN split_part(key_enc, ':', 2) ELSE '?' END AS key_id, count(*) FROM ivorovi.question_answer_key WHERE key_enc IS NOT NULL GROUP BY 1, 2
UNION ALL SELECT 'question_import_row.key' AS field, CASE WHEN key_enc LIKE 'v1:%' THEN '1' WHEN key_enc ~ '^v2:[a-z0-9-]{1,16}:' THEN split_part(key_enc, ':', 2) ELSE '?' END AS key_id, count(*) FROM ivorovi.question_import_row WHERE key_enc IS NOT NULL GROUP BY 1, 2
UNION ALL SELECT 'student.alternate_phone' AS field, CASE WHEN alternate_phone_enc LIKE 'v1:%' THEN '1' WHEN alternate_phone_enc ~ '^v2:[a-z0-9-]{1,16}:' THEN split_part(alternate_phone_enc, ':', 2) ELSE '?' END AS key_id, count(*) FROM ivorovi.student WHERE alternate_phone_enc IS NOT NULL GROUP BY 1, 2
UNION ALL SELECT 'student.current_address' AS field, CASE WHEN current_address_enc LIKE 'v1:%' THEN '1' WHEN current_address_enc ~ '^v2:[a-z0-9-]{1,16}:' THEN split_part(current_address_enc, ':', 2) ELSE '?' END AS key_id, count(*) FROM ivorovi.student WHERE current_address_enc IS NOT NULL GROUP BY 1, 2
UNION ALL SELECT 'student.date_of_birth' AS field, CASE WHEN date_of_birth_enc LIKE 'v1:%' THEN '1' WHEN date_of_birth_enc ~ '^v2:[a-z0-9-]{1,16}:' THEN split_part(date_of_birth_enc, ':', 2) ELSE '?' END AS key_id, count(*) FROM ivorovi.student WHERE date_of_birth_enc IS NOT NULL GROUP BY 1, 2
UNION ALL SELECT 'student.mobile' AS field, CASE WHEN mobile_enc LIKE 'v1:%' THEN '1' WHEN mobile_enc ~ '^v2:[a-z0-9-]{1,16}:' THEN split_part(mobile_enc, ':', 2) ELSE '?' END AS key_id, count(*) FROM ivorovi.student WHERE mobile_enc IS NOT NULL GROUP BY 1, 2
UNION ALL SELECT 'student.permanent_address' AS field, CASE WHEN permanent_address_enc LIKE 'v1:%' THEN '1' WHEN permanent_address_enc ~ '^v2:[a-z0-9-]{1,16}:' THEN split_part(permanent_address_enc, ':', 2) ELSE '?' END AS key_id, count(*) FROM ivorovi.student WHERE permanent_address_enc IS NOT NULL GROUP BY 1, 2
UNION ALL SELECT 'student.personal_email' AS field, CASE WHEN personal_email_enc LIKE 'v1:%' THEN '1' WHEN personal_email_enc ~ '^v2:[a-z0-9-]{1,16}:' THEN split_part(personal_email_enc, ':', 2) ELSE '?' END AS key_id, count(*) FROM ivorovi.student WHERE personal_email_enc IS NOT NULL GROUP BY 1, 2
UNION ALL SELECT 'student_import_row.cells' AS field, CASE WHEN cells_enc LIKE 'v1:%' THEN '1' WHEN cells_enc ~ '^v2:[a-z0-9-]{1,16}:' THEN split_part(cells_enc, ':', 2) ELSE '?' END AS key_id, count(*) FROM ivorovi.student_import_row WHERE cells_enc IS NOT NULL GROUP BY 1, 2
UNION ALL SELECT 'webhook.secret' AS field, CASE WHEN secret_enc LIKE 'v1:%' THEN '1' WHEN secret_enc ~ '^v2:[a-z0-9-]{1,16}:' THEN split_part(secret_enc, ':', 2) ELSE '?' END AS key_id, count(*) FROM ivorovi.webhook_endpoint WHERE secret_enc IS NOT NULL GROUP BY 1, 2
ORDER BY 1, 2;
```

Progress of a rotation (platform context or schema owner):

```sql
SELECT target_key_id, field, count(*) AS organizations, count(completed_at) AS completed, sum(rewritten) AS rewritten,
       sum(conflicts) AS left_to_writers, max(updated_at) AS last_activity
FROM ivorovi.key_rotation_progress WHERE target_key_id = '2026a' GROUP BY 1, 2 ORDER BY 2;
```

## Development

`scripts/dev-setup.sh` generates `DATA_ENCRYPTION_KEY` in `deploy/compose/.env` (Git-ignored). Tests use a random key
per run. Development keys must never be used in staging or production, and production keys must never be copied to a
developer machine.
