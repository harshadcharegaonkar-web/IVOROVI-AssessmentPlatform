-- Phase 1, increment 1.6: student bulk import and data migration.
-- Import jobs (one uploaded CSV/XLSX file for one university or college), one row per data row of the file with its
-- validation result, duplicate classification, decision and final outcome, saved column mappings and per-institution
-- duplicate-matching rules. Raw row values are personal data: stored encrypted by the application (cells_enc, AES-GCM)
-- and purged with the source file after the retention period; outcomes and counts stay. Every table has RLS.

CREATE TABLE student_import_job (
    id                       uuid PRIMARY KEY,
    tenant_id                uuid         NOT NULL REFERENCES tenant (id),
    institution_id           uuid         NOT NULL,
    import_key               varchar(100),
    file_name                varchar(255) NOT NULL,
    file_format              varchar(10)  NOT NULL CHECK (file_format IN ('CSV', 'XLSX')),
    file_size                bigint       NOT NULL CHECK (file_size > 0),
    file_sha256              char(64)     NOT NULL,
    object_path              varchar(300),
    encoding                 varchar(80),
    delimiter                varchar(20),
    sheet_name               varchar(100),
    scan_status              varchar(20)  NOT NULL CHECK (scan_status IN ('CLEAN', 'NOT_SCANNED')),
    headers                  jsonb        NOT NULL,
    mapping                  jsonb,
    mapping_template_id      uuid,
    status                   varchar(30)  NOT NULL CHECK (status IN ('UPLOADED', 'QUEUED', 'VALIDATING', 'READY', 'PROCESSING', 'COMPLETED',
                                                                     'PARTIALLY_COMPLETED', 'FAILED', 'CANCELLED')),
    phase                    varchar(20)  CHECK (phase IN ('VALIDATE', 'IMPORT')),
    mode                     varchar(20)  CHECK (mode IN ('ALL_OR_NOTHING', 'VALID_ROWS_ONLY')),
    total_rows               integer      NOT NULL DEFAULT 0,
    valid_rows               integer      NOT NULL DEFAULT 0,
    warning_rows             integer      NOT NULL DEFAULT 0,
    invalid_rows             integer      NOT NULL DEFAULT 0,
    duplicate_rows           integer      NOT NULL DEFAULT 0,
    potential_duplicate_rows integer      NOT NULL DEFAULT 0,
    conflict_rows            integer      NOT NULL DEFAULT 0,
    unresolved_rows          integer      NOT NULL DEFAULT 0,
    to_create_rows           integer      NOT NULL DEFAULT 0,
    to_update_rows           integer      NOT NULL DEFAULT 0,
    progress_total           integer      NOT NULL DEFAULT 0,
    processed_rows           integer      NOT NULL DEFAULT 0,
    imported_rows            integer      NOT NULL DEFAULT 0,
    updated_rows             integer      NOT NULL DEFAULT 0,
    skipped_rows             integer      NOT NULL DEFAULT 0,
    failed_rows              integer      NOT NULL DEFAULT 0,
    cancel_requested         boolean      NOT NULL DEFAULT false,
    failure_kind             varchar(20)  CHECK (failure_kind IN ('DATA', 'SYSTEM')),
    failure_message          varchar(500),
    rolled_back              boolean      NOT NULL DEFAULT false,
    attempts                 integer      NOT NULL DEFAULT 0,
    lease_owner              varchar(100),
    lease_until              timestamptz,
    previous_job_id          uuid,
    phase_requested_by       uuid,
    created_by               uuid         NOT NULL,
    created_at               timestamptz  NOT NULL DEFAULT now(),
    validated_at             timestamptz,
    confirmed_by             uuid,
    confirmed_at             timestamptz,
    started_at               timestamptz,
    finished_at              timestamptz,
    source_purged_at         timestamptz,
    updated_at               timestamptz  NOT NULL DEFAULT now(),
    version                  bigint       NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, institution_id) REFERENCES org_unit (tenant_id, id),
    FOREIGN KEY (tenant_id, created_by) REFERENCES user_account (tenant_id, id),
    FOREIGN KEY (tenant_id, confirmed_by) REFERENCES user_account (tenant_id, id)
);
-- An import key identifies one source file: reusing it with other content is rejected by the service.
CREATE UNIQUE INDEX student_import_key_uq ON student_import_job (tenant_id, import_key) WHERE import_key IS NOT NULL;
CREATE INDEX student_import_job_list_idx ON student_import_job (tenant_id, created_at DESC);
CREATE INDEX student_import_job_active_idx ON student_import_job (tenant_id, status) WHERE status IN ('QUEUED', 'VALIDATING', 'PROCESSING');
CREATE INDEX student_import_job_sha_idx ON student_import_job (tenant_id, file_sha256);

CREATE TABLE student_import_row (
    id                uuid PRIMARY KEY,
    tenant_id         uuid         NOT NULL REFERENCES tenant (id),
    job_id            uuid         NOT NULL,
    row_no            integer      NOT NULL CHECK (row_no > 0),
    cells_enc         text,
    display_name      varchar(310),
    student_no        varchar(40),
    registration_no   varchar(40),
    validation_status varchar(20)  NOT NULL CHECK (validation_status IN ('VALID', 'WARNING', 'INVALID', 'DUPLICATE', 'NEEDS_DECISION', 'CONFLICT')),
    issues            jsonb        NOT NULL DEFAULT '[]',
    match_category    varchar(40)  CHECK (match_category IN ('EXACT_DUPLICATE', 'POTENTIAL_DUPLICATE', 'CONFLICTING_IDENTITY', 'EXISTING_NEW_ENROLMENT',
                                                            'CONFLICTING_ACADEMIC', 'IN_FILE_DUPLICATE', 'IN_FILE_POTENTIAL')),
    match_student_id  uuid,
    match_row_no      integer,
    match_reason      varchar(300),
    plan              jsonb,
    decision          varchar(20)  CHECK (decision IN ('CREATE_NEW', 'ADD_TO_EXISTING', 'SKIP')),
    decided_by        uuid,
    decided_at        timestamptz,
    outcome           varchar(20)  NOT NULL DEFAULT 'PENDING'
        CHECK (outcome IN ('PENDING', 'IMPORTED', 'UPDATED', 'SKIPPED', 'FAILED', 'ROLLED_BACK', 'NOT_PROCESSED')),
    outcome_code      varchar(60),
    outcome_message   varchar(500),
    student_id        uuid,
    processed_tx      timestamptz,
    prior_state       jsonb,
    processed_at      timestamptz,
    UNIQUE (tenant_id, job_id, row_no),
    FOREIGN KEY (tenant_id, job_id) REFERENCES student_import_job (tenant_id, id)
);
CREATE INDEX student_import_row_status_idx ON student_import_row (tenant_id, job_id, validation_status);
CREATE INDEX student_import_row_outcome_idx ON student_import_row (tenant_id, job_id, outcome);

-- Saved column mappings ("mapping templates"), reusable for later imports of the institution (or the organization).
CREATE TABLE student_import_mapping (
    id             uuid PRIMARY KEY,
    tenant_id      uuid         NOT NULL REFERENCES tenant (id),
    institution_id uuid,
    name           varchar(100) NOT NULL CHECK (length(btrim(name)) > 0),
    description    varchar(300),
    columns        jsonb        NOT NULL,
    created_by     uuid,
    created_at     timestamptz  NOT NULL DEFAULT now(),
    updated_by     uuid,
    updated_at     timestamptz  NOT NULL DEFAULT now(),
    version        bigint       NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, institution_id) REFERENCES org_unit (tenant_id, id)
);
CREATE UNIQUE INDEX student_import_mapping_name_uq ON student_import_mapping
    (tenant_id, coalesce(institution_id, '00000000-0000-0000-0000-000000000000'::uuid), lower(name));

-- Duplicate-matching rules and required-field policy per university or college.
CREATE TABLE student_import_settings (
    id                        uuid PRIMARY KEY,
    tenant_id                 uuid        NOT NULL REFERENCES tenant (id),
    institution_id            uuid        NOT NULL,
    match_personal_email      boolean     NOT NULL DEFAULT true,
    match_institutional_email boolean     NOT NULL DEFAULT true,
    match_name_dob            boolean     NOT NULL DEFAULT true,
    match_name_mobile         boolean     NOT NULL DEFAULT true,
    require_date_of_birth     boolean     NOT NULL DEFAULT false,
    updated_by                uuid,
    updated_at                timestamptz NOT NULL DEFAULT now(),
    version                   bigint      NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, institution_id),
    FOREIGN KEY (tenant_id, institution_id) REFERENCES org_unit (tenant_id, id)
);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['student_import_job', 'student_import_row', 'student_import_mapping', 'student_import_settings'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id())', t);
    END LOOP;
END $$;

-- Duplicate detection looks students up by institutional email (unique per student, see the import rules).
CREATE INDEX IF NOT EXISTS student_institutional_email_idx ON student (tenant_id, institutional_email) WHERE institutional_email IS NOT NULL;

-- Permission (catalogue domain STUDENTS) for system roles that already manage students.
UPDATE role SET permissions = permissions || ARRAY['student.import']
    WHERE system_role AND code IN ('ORG_ADMIN', 'INSTITUTION_ADMIN', 'ADMISSION_OFFICER');
