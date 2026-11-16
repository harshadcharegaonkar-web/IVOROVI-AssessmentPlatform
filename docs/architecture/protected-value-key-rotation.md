# Key rotation for protected values: answer keys, explanations, paper seeds (design for review)

Status: **proposed 2026-11-16, for the product owner's review. Not implemented.** KI-49 stays open until every encrypted
value and every hashed lookup key has a documented and tested rotation strategy.

Decision context (Day 6 review):
- no broad database exception that lets anyone rewrite approved answer-key ciphertext or published paper seeds;
- equally, the original key must not stay impossible to retire;
- the mechanism must preserve logical immutability;
- it must meet ten stated requirements (R1–R10 below).

## 1. What is protected today

| Value | Table, column | Guard today | Independent integrity reference |
|---|---|---|---|
| Correct answer of an approved question version | `question_answer_key.key_enc` | `question_answer_key_guard`: no update unless the version is DRAFT; read only through `AnswerKeyStore` (ADR-0009) | `key_hash`, an HMAC (blind-index key) of the canonical key |
| Explanation | `question_answer_key.explanation_enc` | same | none yet; added below (§3.4) |
| Seed of a generated paper | `assessment_paper.seed_enc` | `assessment_paper_guard`: no update ever | `paper_hash` (to be confirmed: it must cover the seed, otherwise a digest is added as in §3.4) |

All three use AES-256-GCM under the field key, with associated data (AAD) `field|tenant`. A ciphertext therefore cannot
be moved to another column or organization. It **can** be swapped between two rows of the same column and organization.
That residual risk is closed below (§3.2).

## 2. Requirements and how the design meets them

| # | Requirement (Day 6 review) | Design element |
|---|---|---|
| R1 | Approved answers and explanations stay logically immutable | The decrypted value never changes. Only the ciphertext's key changes, and only through §3.3, verified as in R5 |
| R2 | Published paper seeds stay logically immutable | Same mechanism, verified against `paper_hash` or the digest |
| R3 | Only an authorized rotation process may re-encrypt | A dedicated database role `ivorovi_key_rotator`, used only by an operator command, never by the application; plus an approved rotation run (R9) |
| R4 | Normal users and services cannot modify approved values | The application role keeps exactly today's refusals. The guards accept a rewrite only from the rotator role, only of the `*_enc` column, only against a matching ledger row (§3.3) |
| R5 | Decrypted values identical before and after | The rotator decrypts the old value, re-encrypts it, decrypts the new value and compares the bytes. It also checks the independent reference (`key_hash`, `paper_hash` or the stored digest). Any mismatch aborts the row; nothing is written |
| R6 | Authenticated encryption and integrity verification | AES-256-GCM (unchanged). New format `v3` additionally binds the **row id** in the associated data (§3.2), so a ciphertext cannot be swapped between rows. Integrity is also checked against the independent references |
| R7 | Record old and new key version, actor, timestamp, operation, outcome | A permanent `crypto_rotation_ledger` row per value (no plaintext, no ciphertext), and an audit event per batch with counts |
| R8 | Interruption recovery and rollback without losing the original values | Per-row atomic transactions with a resume point. The old key stays configured until retirement (§3.6). Rollback is a rotation back to the old key id through the same path. Nothing is deleted before verification |
| R9 | Prevent unauthorized or partial rotation | A rotation run needs a four-eyes approval (two different platform security officers). The run is "complete" only when no value of the protected columns references the old key. Key retirement is refused by the startup check while any reference remains |
| R10 | Answer keys stay inaccessible through APIs, logs and interfaces | The rotator has no HTTP surface and logs counts only. `AnswerKeyStore` remains the only application reader. Tests assert no API, log line, audit record or metric contains a key during and after rotation (§4) |

## 3. Design

### 3.1 Roles and connection

- **New database role `ivorovi_key_rotator`** (NOLOGIN, granted to a dedicated login used only by the operator command):
  - SELECT on the protected tables;
  - UPDATE on exactly the protected `*_enc` columns (column-level GRANT);
  - INSERT on the ledger.
  - It holds no other right. The application role gets nothing new.
- **Operator command** `key-rotation protected`:
  - a one-shot process with the same packaging as the privileged-role bootstrap;
  - takes the approved run id, uses the rotator connection, and processes one organization at a time under RLS;
  - has no HTTP endpoint.

### 3.2 Ciphertext format v3 (row-bound)

- **Format:** `v3:<keyId>:<base64(iv | ciphertext+tag)>`.
- **Associated data:** `field|tenant|rowId`.
- **Reading:** `FieldCipher` reads v1, v2 and v3. It writes v3 only for the protected fields, and only through the rotator.
- **Effect:** a ciphertext copied to another row of the same column fails authentication. This closes the swap risk
  Session 2 identified. Application writes of new draft keys may adopt v3 later, in a separate change.

### 3.3 The narrow, logged exception in the guards

`question_answer_key_guard` and `assessment_paper_guard` keep every current refusal. They add one permitted case,
taken only when **all** of the following hold:
1. `current_user = 'ivorovi_key_rotator'`;
2. the only changed columns are the protected `*_enc` columns of the row;
3. a `crypto_rotation_ledger` row exists in the same transaction, matching all of:
   - table and row id;
   - `old_digest = sha256(OLD.<col>)` and `new_digest = sha256(NEW.<col>)`;
   - the old and new key ids named by the two values (the new one differs from the old one);
   - a `run_id` whose run is APPROVED and not finished.

A write from any other role, or without its ledger row, is refused exactly as today. No SECURITY DEFINER function is
needed and the application role cannot reach this path at all.

### 3.4 Verification of identity (R5)

For each value, inside its transaction:
1. decrypt the old value with its key;
2. encrypt it with the target key (v3, row-bound AAD);
3. decrypt the new value and compare the bytes with step 1;
4. verify an independent reference:
   - answer key: `key_hash == blindIndex(canonical(plaintext))`;
   - seed: `paper_hash` recomputed, if it covers the seed;
   - explanation (and the seed, if `paper_hash` does not cover it): a keyed digest `HMAC(indexKey, plaintext)`. It is
     recorded once, at the first rotation, in a side table written only by the rotator; later rotations compare
     against it.

Plaintext exists only in the rotator's memory for the duration of one row. It is never written to logs, the ledger,
the audit trail or errors.

### 3.5 Data model

| Table | Columns | Rules |
|---|---|---|
| `crypto_rotation_run` | id, target_key_id, scope (fields), requested_by, approved_by, status, created_at, finished_at | `approved_by` must differ from `requested_by`, and both are platform security officers; four-eyes like P2-08 |
| `crypto_rotation_ledger` | run_id, table, row_id, field, old_key_id, new_key_id, old_digest, new_digest, actor, at, outcome (REWRITTEN / VERIFIED_UNCHANGED / ABORTED with reason code) | Append-only (UPDATE and DELETE refused); no plaintext or ciphertext |
| `protected_value_digest` | table, row_id, field, digest | Written only by the rotator; never changes once written |

### 3.6 Interruption, rollback, retirement

- **Interruption:** each row is its own transaction. The run resumes from the last ledger row. A crash leaves rows
  either fully old-key or fully new-key, each with a matching ledger row or none.
- **Rollback:** run again with the old key id as the target, through the same path and the same verification. This is
  possible because the old key stays configured as a previous key.
- **Retirement:** remove the old key from the configuration only after the startup key check (Session 2's
  `KeyRingStartupCheck`, extended to the protected columns through an SPI implemented by the owning modules) reports
  zero references to it. The application refuses to start otherwise.

### 3.7 Hashed lookup keys (blind indexes): rotation strategy for KI-49

The index key is pinned (`IVOROVI_DATA_INDEX_KEY_ID`). Rotating it means recomputing every index from its plaintext:

| Index | Plaintext available? | Strategy |
|---|---|---|
| Candidate and enrolment e-mail index | Yes, encrypted next to it | Dual index during the change: write both and look up both; re-hash every row from the decrypted e-mail (leased job); then drop the old index |
| Session-code hashes | No (only the hash is stored) | Codes are short-lived. New codes use the new index key, lookups try both keys until the longest validity (days) has passed, then the old key is removed |
| Answer-key `key_hash` | Yes, through the rotator | Recomputed by the rotator in the same per-row transaction as §3.4, with both values recorded in the ledger |

## 4. Tests required before acceptance

1. Real rotation of answer keys, explanations and seeds in mixed key states. Plaintext is identical before and after,
   and the references verify.
2. The application role cannot update any protected column, with or without a ledger row.
3. The rotator cannot change a plaintext: the guard refuses a ledger mismatch, and the rotator's own comparison aborts.
4. Swapping two v3 ciphertexts between rows fails authentication.
5. Interruption at every step; then resume and complete; then rollback to the old key; all values are still readable
   throughout.
6. An unapproved, self-approved or finished run is refused.
7. No API response, log line, audit record or metric contains an answer key or seed during and after rotation; the
   existing `AnswerKeySecurityIT` stays green.
8. Retirement is refused while any reference remains, and allowed after.

## 5. Effort and ownership

| Item | Effort |
|---|---|
| Roles, guards, tables and migration | 1 day |
| Rotator command | 1.5 days |
| v3 format and SPI for module-owned columns | 0.5 day |
| Index-key strategy | 1 day |
| Tests above | 1 day |
| **Total** | **≈ 5 engineering days** |

Session 2 (author of the rotation job) is the natural owner after approval; review by Session A, then the external
penetration-test scope (P-3) includes it.
