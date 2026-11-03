# Field-encryption key (`IVOROVI_DATA_KEY`): management, backup, recovery and rotation

Status: **required before production** (follow-up of the increment 1.5 acceptance). This runbook describes the
procedures; executing them in a real environment (secret store, access lists, a recovery drill) is a release task that
has not been performed yet. Tracking: KI-49 (rotation tooling), KI-51 (secret-store operation and recovery drill).

## What the key protects

`FieldCipher` encrypts sensitive student and guardian fields (dates of birth, contact details, addresses) and student
import row data (increment 1.6) with AES-256-GCM. The AES key is derived (SHA-256) from `IVOROVI_DATA_KEY`. Every value
is stored as `v1:<base64(iv | ciphertext+tag)>`, with the column name and organization bound as associated data.

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
| Start-up check | The API refuses to start when the key is missing or shorter than 24 characters (already enforced) |
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

## Rotation

Current state (KI-49): one active key. The `v1:` prefix is reserved so that a key identifier can be added.
Until the rotation job ships, rotation is an **offline maintenance procedure**:

1. Announce a maintenance window, stop the API replicas and take a database backup (linked to the current key version).
2. Run the re-encryption tool against the database with both keys (old for decrypt, new for encrypt), covering every
   `*_enc` column of `student`, `guardian` and `student_import_row`. *This tool is part of the KI-49 work and has not
   been written yet*; until then, a key cannot be rotated without data loss and the key must be protected accordingly.
3. Store the new key in the secret store (new version) and in escrow; update the operations register.
4. Start the API with the new key and run the recovery check; keep the old key in escrow until the next backup made with
   the new key has been restore-tested.

Target design (before production, KI-49): values written as `v2:<keyId>:…`, the API holds the active key plus
decrypt-only previous keys, and a background job re-encrypts rows in batches (with audit records containing counts only)
so rotation needs no downtime.

## Development

`scripts/dev-setup.sh` generates `DATA_ENCRYPTION_KEY` in `deploy/compose/.env` (Git-ignored). Tests use a random key
per run. Development keys must never be used in staging or production, and production keys must never be copied to a
developer machine.
