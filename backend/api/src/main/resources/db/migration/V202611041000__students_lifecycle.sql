-- Phase 1, increment 1.5: student lifecycle management.
-- Student profiles (independent of sign-in accounts), admission records, enrolments (history preserved: every change
-- closes the current row and opens a new one), guardians, documents (quarantine + malware-scan state), lifecycle
-- status history and a PII-free event log. Sensitive personal fields are stored encrypted by the application
-- (columns *_enc, AES-GCM); names and identifiers stay searchable. Every table is tenant-owned with RLS.

-- Configurable option lists (admission types and categories, document categories); built-in codes need no rows.
CREATE TABLE student_option (
    id         uuid PRIMARY KEY,
    tenant_id  uuid         NOT NULL REFERENCES tenant (id),
    kind       varchar(30)  NOT NULL CHECK (kind IN ('ADMISSION_TYPE', 'ADMISSION_CATEGORY', 'DOCUMENT_CATEGORY')),
    code       varchar(40)  NOT NULL CHECK (code ~ '^[A-Z][A-Z0-9_]{1,39}$'),
    label      varchar(100) NOT NULL CHECK (length(btrim(label)) > 0),
    active     boolean      NOT NULL DEFAULT true,
    created_at timestamptz  NOT NULL DEFAULT now(),
    created_by uuid,
    UNIQUE (tenant_id, kind, code)
);

-- Student ID numbering rules per university or college (no global format is assumed).
CREATE TABLE student_number_rule (
    id                   uuid PRIMARY KEY,
    tenant_id            uuid         NOT NULL REFERENCES tenant (id),
    org_unit_id          uuid         NOT NULL,
    mode                 varchar(20)  NOT NULL CHECK (mode IN ('GENERATED', 'MANUAL')),
    template             varchar(100),
    manual_pattern       varchar(200),
    registration_pattern varchar(200),
    created_at           timestamptz  NOT NULL DEFAULT now(),
    updated_at           timestamptz  NOT NULL DEFAULT now(),
    updated_by           uuid,
    version              bigint       NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, org_unit_id),
    CHECK (mode <> 'GENERATED' OR template IS NOT NULL),
    FOREIGN KEY (tenant_id, org_unit_id) REFERENCES org_unit (tenant_id, id)
);

CREATE TABLE student_number_counter (
    tenant_id  uuid        NOT NULL REFERENCES tenant (id),
    rule_id    uuid        NOT NULL,
    period     varchar(10) NOT NULL,
    next_value bigint      NOT NULL CHECK (next_value > 0),
    PRIMARY KEY (tenant_id, rule_id, period),
    FOREIGN KEY (tenant_id, rule_id) REFERENCES student_number_rule (tenant_id, id)
);

CREATE TABLE student (
    id                      uuid PRIMARY KEY,
    tenant_id               uuid         NOT NULL REFERENCES tenant (id),
    institution_id          uuid         NOT NULL,
    scope_unit_id           uuid         NOT NULL,
    student_no              varchar(40)  NOT NULL,
    registration_no         varchar(40),
    roll_no                 varchar(40),
    previous_institution_ref varchar(100),
    first_name              varchar(100) NOT NULL CHECK (length(btrim(first_name)) > 0),
    middle_name             varchar(100),
    last_name               varchar(100),
    gender                  varchar(20)  CHECK (gender IN ('FEMALE', 'MALE', 'OTHER', 'UNDISCLOSED')),
    nationality             varchar(60),
    date_of_birth_enc       text,
    personal_email_enc      text,
    institutional_email     varchar(320) CHECK (institutional_email IS NULL OR (institutional_email = lower(btrim(institutional_email)) AND institutional_email LIKE '%_@_%')),
    mobile_enc              text,
    alternate_phone_enc     text,
    current_address_enc     text,
    permanent_address_enc   text,
    status                  varchar(20)  NOT NULL DEFAULT 'PROSPECTIVE'
        CHECK (status IN ('PROSPECTIVE', 'ADMITTED', 'ENROLLED', 'ACTIVE', 'SUSPENDED', 'WITHDRAWN', 'GRADUATED', 'ALUMNI', 'CANCELLED')),
    status_effective_on     date         NOT NULL DEFAULT current_date,
    active                  boolean      NOT NULL DEFAULT true,
    user_account_id         uuid,
    created_at              timestamptz  NOT NULL DEFAULT now(),
    created_by              uuid,
    updated_at              timestamptz  NOT NULL DEFAULT now(),
    updated_by              uuid,
    version                 bigint       NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, institution_id) REFERENCES org_unit (tenant_id, id),
    FOREIGN KEY (tenant_id, scope_unit_id) REFERENCES org_unit (tenant_id, id),
    FOREIGN KEY (tenant_id, user_account_id) REFERENCES user_account (tenant_id, id)
);
CREATE UNIQUE INDEX student_no_uq ON student (tenant_id, lower(student_no));
CREATE UNIQUE INDEX student_registration_uq ON student (tenant_id, lower(registration_no)) WHERE registration_no IS NOT NULL;
CREATE UNIQUE INDEX student_user_uq ON student (tenant_id, user_account_id) WHERE user_account_id IS NOT NULL;
CREATE INDEX student_scope_idx ON student (tenant_id, scope_unit_id, status);
CREATE INDEX student_name_idx ON student (tenant_id, lower(first_name), lower(last_name));

CREATE TABLE student_status_history (
    id           uuid PRIMARY KEY,
    tenant_id    uuid         NOT NULL REFERENCES tenant (id),
    student_id   uuid         NOT NULL,
    from_status  varchar(20),
    to_status    varchar(20)  NOT NULL,
    effective_on date         NOT NULL,
    reason       varchar(500) NOT NULL,
    actor_id     uuid,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, student_id) REFERENCES student (tenant_id, id)
);
CREATE INDEX student_status_history_idx ON student_status_history (tenant_id, student_id, created_at);

-- Human-readable change log of a student (no personal values; field names and codes only).
CREATE TABLE student_event (
    id         uuid PRIMARY KEY,
    tenant_id  uuid         NOT NULL REFERENCES tenant (id),
    student_id uuid         NOT NULL,
    event_type varchar(60)  NOT NULL,
    summary    varchar(300) NOT NULL,
    actor_id   uuid,
    created_at timestamptz  NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, student_id) REFERENCES student (tenant_id, id)
);
CREATE INDEX student_event_idx ON student_event (tenant_id, student_id, created_at);

CREATE TABLE admission_record (
    id                  uuid PRIMARY KEY,
    tenant_id           uuid         NOT NULL REFERENCES tenant (id),
    student_id          uuid         NOT NULL,
    admission_no        varchar(40)  NOT NULL CHECK (admission_no ~ '^[A-Za-z0-9][A-Za-z0-9._/-]{0,39}$'),
    admission_date      date         NOT NULL,
    admission_type      varchar(40)  NOT NULL,
    admission_category  varchar(40),
    entry_academic_year_id uuid      NOT NULL,
    program_id          uuid         NOT NULL,
    course_id           uuid         NOT NULL,
    institution_id      uuid         NOT NULL,
    department_id       uuid         NOT NULL,
    entry_term_number   smallint     NOT NULL DEFAULT 1 CHECK (entry_term_number BETWEEN 1 AND 40),
    status              varchar(20)  NOT NULL DEFAULT 'CONFIRMED' CHECK (status IN ('PROVISIONAL', 'CONFIRMED', 'CANCELLED')),
    previous_institution_name    varchar(200),
    previous_institution_details varchar(500),
    status_reason       varchar(500),
    created_at          timestamptz  NOT NULL DEFAULT now(),
    created_by          uuid,
    updated_at          timestamptz  NOT NULL DEFAULT now(),
    updated_by          uuid,
    version             bigint       NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, student_id) REFERENCES student (tenant_id, id),
    FOREIGN KEY (tenant_id, entry_academic_year_id) REFERENCES academic_year (tenant_id, id),
    FOREIGN KEY (tenant_id, program_id) REFERENCES program (tenant_id, id),
    FOREIGN KEY (tenant_id, course_id) REFERENCES course (tenant_id, id),
    FOREIGN KEY (tenant_id, institution_id) REFERENCES org_unit (tenant_id, id),
    FOREIGN KEY (tenant_id, department_id) REFERENCES org_unit (tenant_id, id)
);
CREATE UNIQUE INDEX admission_no_uq ON admission_record (tenant_id, institution_id, lower(admission_no));
CREATE UNIQUE INDEX admission_open_uq ON admission_record (tenant_id, student_id) WHERE status <> 'CANCELLED';

CREATE TABLE enrolment (
    id                    uuid PRIMARY KEY,
    tenant_id             uuid         NOT NULL REFERENCES tenant (id),
    student_id            uuid         NOT NULL,
    admission_id          uuid,
    institution_id        uuid         NOT NULL,
    campus_id             uuid,
    department_id         uuid         NOT NULL,
    program_id            uuid         NOT NULL,
    course_id             uuid         NOT NULL,
    batch_id              uuid         NOT NULL,
    academic_year_id      uuid         NOT NULL,
    term_id               uuid,
    term_number           smallint     NOT NULL CHECK (term_number BETWEEN 1 AND 40),
    enrolled_on           date         NOT NULL,
    ended_on              date,
    status                varchar(20)  NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED', 'COMPLETED', 'TRANSFERRED', 'WITHDRAWN', 'GRADUATED')),
    change_type           varchar(20)  NOT NULL CHECK (change_type IN ('INITIAL', 'PROGRESSION', 'PROMOTION', 'BATCH_TRANSFER', 'COURSE_TRANSFER')),
    is_current            boolean      NOT NULL DEFAULT true,
    previous_enrolment_id uuid,
    reason                varchar(500),
    created_at            timestamptz  NOT NULL DEFAULT now(),
    created_by            uuid,
    UNIQUE (tenant_id, id),
    CHECK (ended_on IS NULL OR ended_on >= enrolled_on),
    FOREIGN KEY (tenant_id, student_id) REFERENCES student (tenant_id, id),
    FOREIGN KEY (tenant_id, admission_id) REFERENCES admission_record (tenant_id, id),
    FOREIGN KEY (tenant_id, institution_id) REFERENCES org_unit (tenant_id, id),
    FOREIGN KEY (tenant_id, campus_id) REFERENCES org_unit (tenant_id, id),
    FOREIGN KEY (tenant_id, department_id) REFERENCES org_unit (tenant_id, id),
    FOREIGN KEY (tenant_id, program_id) REFERENCES program (tenant_id, id),
    FOREIGN KEY (tenant_id, course_id) REFERENCES course (tenant_id, id),
    FOREIGN KEY (tenant_id, batch_id) REFERENCES batch (tenant_id, id),
    FOREIGN KEY (tenant_id, academic_year_id) REFERENCES academic_year (tenant_id, id),
    FOREIGN KEY (tenant_id, term_id) REFERENCES academic_term (tenant_id, id),
    FOREIGN KEY (tenant_id, previous_enrolment_id) REFERENCES enrolment (tenant_id, id)
);
CREATE UNIQUE INDEX enrolment_current_uq ON enrolment (tenant_id, student_id) WHERE is_current;
CREATE INDEX enrolment_batch_idx ON enrolment (tenant_id, batch_id) WHERE is_current;

CREATE TABLE guardian (
    id           uuid PRIMARY KEY,
    tenant_id    uuid         NOT NULL REFERENCES tenant (id),
    student_id   uuid         NOT NULL,
    name         varchar(200) NOT NULL CHECK (length(btrim(name)) > 0),
    relationship varchar(40)  NOT NULL,
    mobile_enc   text,
    email_enc    text,
    address_enc  text,
    is_primary   boolean      NOT NULL DEFAULT false,
    is_emergency boolean      NOT NULL DEFAULT false,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    created_by   uuid,
    updated_at   timestamptz  NOT NULL DEFAULT now(),
    updated_by   uuid,
    version      bigint       NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, student_id) REFERENCES student (tenant_id, id)
);
CREATE UNIQUE INDEX guardian_primary_uq ON guardian (tenant_id, student_id) WHERE is_primary;

-- Documents: stored in quarantine until scanned CLEAN (ADR-0006); only CLEAN documents can be downloaded.
CREATE TABLE student_document (
    id                  uuid PRIMARY KEY,
    tenant_id           uuid         NOT NULL REFERENCES tenant (id),
    student_id          uuid         NOT NULL,
    category            varchar(40)  NOT NULL,
    title               varchar(200) NOT NULL,
    file_name           varchar(255) NOT NULL,
    content_type        varchar(100) NOT NULL CHECK (content_type IN ('application/pdf', 'image/jpeg', 'image/png')),
    size_bytes          bigint       NOT NULL CHECK (size_bytes > 0),
    sha256              char(64)     NOT NULL,
    object_path         varchar(512) NOT NULL,
    scan_status         varchar(20)  NOT NULL DEFAULT 'PENDING' CHECK (scan_status IN ('PENDING', 'CLEAN', 'INFECTED', 'ERROR')),
    scan_detail         varchar(300),
    scanned_at          timestamptz,
    verification_status varchar(20)  NOT NULL DEFAULT 'PENDING' CHECK (verification_status IN ('PENDING', 'VERIFIED', 'REJECTED')),
    verification_note   varchar(500),
    verified_by         uuid,
    verified_at         timestamptz,
    status              varchar(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'REMOVED')),
    removed_reason      varchar(500),
    removed_by          uuid,
    removed_at          timestamptz,
    purged_at           timestamptz,
    uploaded_by         uuid,
    uploaded_at         timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, student_id) REFERENCES student (tenant_id, id)
);
CREATE INDEX student_document_idx ON student_document (tenant_id, student_id, status);
CREATE INDEX student_document_scan_idx ON student_document (scan_status) WHERE scan_status = 'PENDING';

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['student_option', 'student_number_rule', 'student_number_counter', 'student', 'student_status_history',
                             'student_event', 'admission_record', 'enrolment', 'guardian', 'student_document'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id())', t);
    END LOOP;
END $$;

-- The status log and the event log cannot be altered by the application role (rows are only removed together with a
-- profile deleted because it was created in error; the hash-chained audit trail keeps the record of that deletion).
REVOKE UPDATE ON student_status_history, student_event FROM ${appRole};

-- ---------------------------------------------------------------------------------------------------------
-- Permissions (catalogue domain STUDENTS) for system roles.
-- ---------------------------------------------------------------------------------------------------------
UPDATE role SET permissions = permissions || ARRAY['student.read', 'student.personal.read', 'student.manage', 'student.document.read',
                                                   'student.document.manage', 'student.account.manage']
    WHERE system_role AND code IN ('ORG_ADMIN', 'INSTITUTION_ADMIN');
UPDATE role SET permissions = permissions || ARRAY['student.read', 'student.personal.read', 'student.manage', 'student.document.read',
                                                   'student.document.manage', 'academics.read']
    WHERE system_role AND code = 'ADMISSION_OFFICER';
UPDATE role SET permissions = permissions || ARRAY['student.read', 'student.personal.read', 'student.document.read']
    WHERE system_role AND code = 'DEPARTMENT_HEAD';
UPDATE role SET permissions = permissions || ARRAY['student.read']
    WHERE system_role AND code IN ('CAMPUS_ADMIN', 'FACULTY', 'EXAM_CONTROLLER', 'MANAGEMENT');
