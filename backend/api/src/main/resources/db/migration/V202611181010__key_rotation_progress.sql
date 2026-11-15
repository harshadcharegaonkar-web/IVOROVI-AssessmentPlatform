-- RB-3 / KI-49: progress of the field-encryption key rotation job (platform module).
-- One row per target key id, organization and encrypted field. It holds counts, the last processed row id and
-- timestamps only: never ciphertext, plaintext or key material. Visible to platform context only (like job_lease), so
-- organizations' users cannot read it; operators read it with the queries in runbooks/data-encryption-key.md.
CREATE TABLE key_rotation_progress (
    target_key_id  varchar(16)  NOT NULL CHECK (target_key_id ~ '^[a-z0-9-]{1,16}$'),
    tenant_id      uuid         NOT NULL REFERENCES tenant (id),
    field          varchar(100) NOT NULL,
    last_id        uuid,                                   -- resume point (rows are processed in id order)
    rewritten      bigint       NOT NULL DEFAULT 0,        -- values re-encrypted to the target key
    conflicts      bigint       NOT NULL DEFAULT 0,        -- a concurrent writer changed the value first (writer wins)
    passes         integer      NOT NULL DEFAULT 0,        -- full passes (a verification pass reopens a field that still has old values)
    started_at     timestamptz  NOT NULL DEFAULT now(),
    updated_at     timestamptz  NOT NULL DEFAULT now(),
    completed_at   timestamptz,
    PRIMARY KEY (target_key_id, tenant_id, field)
);
ALTER TABLE key_rotation_progress ENABLE ROW LEVEL SECURITY;
CREATE POLICY key_rotation_progress_platform ON key_rotation_progress USING (is_platform_context()) WITH CHECK (is_platform_context());
REVOKE DELETE ON key_rotation_progress FROM ${appRole};
