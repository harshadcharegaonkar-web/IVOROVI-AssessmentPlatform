-- Phase 1, increment 1.4: academic structure.
-- Program (degree) → Course (branch/specialization) → Subject (paper), academic calendar (years and terms),
-- batches, subject offerings and faculty allocation. Every table is tenant-owned with RLS; nothing is hard-deleted
-- except curriculum/prerequisite links (audited), so historical records survive deactivation.

-- ---------------------------------------------------------------------------------------------------------
-- Programs: owned by a university or college.
-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE program (
    id                    uuid PRIMARY KEY,
    tenant_id             uuid         NOT NULL REFERENCES tenant (id),
    owner_org_unit_id     uuid         NOT NULL,
    code                  varchar(30)  NOT NULL CHECK (code ~ '^[A-Za-z0-9][A-Za-z0-9._/-]{0,29}$'),
    name                  varchar(200) NOT NULL CHECK (length(btrim(name)) > 0),
    qualification_level   varchar(20)  NOT NULL CHECK (qualification_level IN ('CERTIFICATE', 'DIPLOMA', 'UNDERGRADUATE', 'POSTGRADUATE', 'DOCTORAL', 'OTHER')),
    awarded_qualification varchar(200),
    term_system           varchar(20)  NOT NULL CHECK (term_system IN ('SEMESTER', 'TRIMESTER', 'QUARTER', 'ANNUAL', 'OTHER')),
    terms_per_year        smallint     NOT NULL CHECK (terms_per_year BETWEEN 1 AND 6),
    duration_years        numeric(3,1) NOT NULL CHECK (duration_years > 0 AND duration_years <= 10),
    total_terms           smallint     NOT NULL CHECK (total_terms BETWEEN 1 AND 40),
    description           varchar(1000),
    status                varchar(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at            timestamptz  NOT NULL DEFAULT now(),
    created_by            uuid,
    updated_at            timestamptz  NOT NULL DEFAULT now(),
    updated_by            uuid,
    version               bigint       NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, owner_org_unit_id) REFERENCES org_unit (tenant_id, id)
);
CREATE UNIQUE INDEX program_code_uq ON program (tenant_id, owner_org_unit_id, lower(code));
CREATE INDEX program_owner_idx ON program (tenant_id, owner_org_unit_id, status);

-- ---------------------------------------------------------------------------------------------------------
-- Courses (branches / specializations): belong to a program, owned by a department of the program's institution.
-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE course (
    id             uuid PRIMARY KEY,
    tenant_id      uuid         NOT NULL REFERENCES tenant (id),
    program_id     uuid         NOT NULL,
    department_id  uuid         NOT NULL,
    code           varchar(30)  NOT NULL CHECK (code ~ '^[A-Za-z0-9][A-Za-z0-9._/-]{0,29}$'),
    name           varchar(200) NOT NULL CHECK (length(btrim(name)) > 0),
    specialization varchar(200),
    total_terms    smallint     CHECK (total_terms IS NULL OR total_terms BETWEEN 1 AND 40),
    description    varchar(1000),
    status         varchar(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at     timestamptz  NOT NULL DEFAULT now(),
    created_by     uuid,
    updated_at     timestamptz  NOT NULL DEFAULT now(),
    updated_by     uuid,
    version        bigint       NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, program_id) REFERENCES program (tenant_id, id),
    FOREIGN KEY (tenant_id, department_id) REFERENCES org_unit (tenant_id, id)
);
CREATE UNIQUE INDEX course_code_uq ON course (tenant_id, program_id, lower(code));
CREATE INDEX course_department_idx ON course (tenant_id, department_id, status);

-- ---------------------------------------------------------------------------------------------------------
-- Subjects (papers): masters owned by a unit, reusable across courses through the curriculum mapping.
-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE subject (
    id                uuid PRIMARY KEY,
    tenant_id         uuid         NOT NULL REFERENCES tenant (id),
    owner_org_unit_id uuid         NOT NULL,
    code              varchar(30)  NOT NULL CHECK (code ~ '^[A-Za-z0-9][A-Za-z0-9._/-]{0,29}$'),
    name              varchar(200) NOT NULL CHECK (length(btrim(name)) > 0),
    description       varchar(2000),
    credits           numeric(4,1) NOT NULL CHECK (credits >= 0 AND credits <= 40),
    delivery          varchar(20)  NOT NULL CHECK (delivery IN ('THEORY', 'PRACTICAL', 'THEORY_AND_PRACTICAL')),
    elective          boolean      NOT NULL DEFAULT false,
    category          varchar(30)  NOT NULL CHECK (category IN ('CORE', 'ELECTIVE', 'OPEN_ELECTIVE', 'FOUNDATION', 'LABORATORY', 'PROJECT', 'SEMINAR', 'INTERNSHIP', 'AUDIT', 'OTHER')),
    status            varchar(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at        timestamptz  NOT NULL DEFAULT now(),
    created_by        uuid,
    updated_at        timestamptz  NOT NULL DEFAULT now(),
    updated_by        uuid,
    version           bigint       NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, owner_org_unit_id) REFERENCES org_unit (tenant_id, id)
);
CREATE UNIQUE INDEX subject_code_uq ON subject (tenant_id, owner_org_unit_id, lower(code));
CREATE INDEX subject_owner_idx ON subject (tenant_id, owner_org_unit_id, status);

-- Curriculum: in which term of which course a subject is taught (semester applicability).
CREATE TABLE curriculum_entry (
    id          uuid PRIMARY KEY,
    tenant_id   uuid        NOT NULL REFERENCES tenant (id),
    course_id   uuid        NOT NULL,
    subject_id  uuid        NOT NULL,
    term_number smallint    NOT NULL CHECK (term_number BETWEEN 1 AND 40),
    elective    boolean     NOT NULL DEFAULT false,
    created_at  timestamptz NOT NULL DEFAULT now(),
    created_by  uuid,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, course_id, subject_id, term_number),
    FOREIGN KEY (tenant_id, course_id) REFERENCES course (tenant_id, id),
    FOREIGN KEY (tenant_id, subject_id) REFERENCES subject (tenant_id, id)
);

CREATE TABLE subject_prerequisite (
    tenant_id       uuid        NOT NULL REFERENCES tenant (id),
    subject_id      uuid        NOT NULL,
    prerequisite_id uuid        NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    created_by      uuid,
    PRIMARY KEY (tenant_id, subject_id, prerequisite_id),
    CHECK (subject_id <> prerequisite_id),
    FOREIGN KEY (tenant_id, subject_id) REFERENCES subject (tenant_id, id),
    FOREIGN KEY (tenant_id, prerequisite_id) REFERENCES subject (tenant_id, id)
);

-- ---------------------------------------------------------------------------------------------------------
-- Academic calendar: years (organization-wide or per university/college) and any number of terms per year.
-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE academic_year (
    id                uuid PRIMARY KEY,
    tenant_id         uuid         NOT NULL REFERENCES tenant (id),
    owner_org_unit_id uuid,
    code              varchar(30)  NOT NULL CHECK (code ~ '^[A-Za-z0-9][A-Za-z0-9._/-]{0,29}$'),
    name              varchar(100) NOT NULL CHECK (length(btrim(name)) > 0),
    start_date        date         NOT NULL,
    end_date          date         NOT NULL,
    status            varchar(20)  NOT NULL DEFAULT 'PLANNED' CHECK (status IN ('PLANNED', 'ACTIVE', 'COMPLETED')),
    created_at        timestamptz  NOT NULL DEFAULT now(),
    created_by        uuid,
    updated_at        timestamptz  NOT NULL DEFAULT now(),
    updated_by        uuid,
    version           bigint       NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    CHECK (end_date > start_date),
    FOREIGN KEY (tenant_id, owner_org_unit_id) REFERENCES org_unit (tenant_id, id)
);
CREATE UNIQUE INDEX academic_year_code_uq ON academic_year
    (tenant_id, coalesce(owner_org_unit_id, '00000000-0000-0000-0000-000000000000'::uuid), lower(code));

CREATE TABLE academic_term (
    id                 uuid PRIMARY KEY,
    tenant_id          uuid         NOT NULL REFERENCES tenant (id),
    academic_year_id   uuid         NOT NULL,
    sequence           smallint     NOT NULL CHECK (sequence BETWEEN 1 AND 12),
    name               varchar(100) NOT NULL CHECK (length(btrim(name)) > 0),
    start_date         date         NOT NULL,
    end_date           date         NOT NULL,
    registration_start date,
    registration_end   date,
    exam_start         date,
    exam_end           date,
    status             varchar(20)  NOT NULL DEFAULT 'PLANNED' CHECK (status IN ('PLANNED', 'ACTIVE', 'COMPLETED')),
    created_at         timestamptz  NOT NULL DEFAULT now(),
    created_by         uuid,
    updated_at         timestamptz  NOT NULL DEFAULT now(),
    updated_by         uuid,
    version            bigint       NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, academic_year_id, sequence),
    CHECK (end_date > start_date),
    CHECK ((registration_start IS NULL) = (registration_end IS NULL) AND (registration_end IS NULL OR registration_end >= registration_start)),
    CHECK ((exam_start IS NULL) = (exam_end IS NULL) AND (exam_end IS NULL OR exam_end >= exam_start)),
    FOREIGN KEY (tenant_id, academic_year_id) REFERENCES academic_year (tenant_id, id)
);

-- ---------------------------------------------------------------------------------------------------------
-- Batches: an intake of a course, optionally a section.
-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE batch (
    id                       uuid PRIMARY KEY,
    tenant_id                uuid         NOT NULL REFERENCES tenant (id),
    course_id                uuid         NOT NULL,
    academic_year_id         uuid         NOT NULL,
    code                     varchar(40)  NOT NULL CHECK (code ~ '^[A-Za-z0-9][A-Za-z0-9._/-]{0,39}$'),
    name                     varchar(200) NOT NULL CHECK (length(btrim(name)) > 0),
    admission_year           smallint     NOT NULL CHECK (admission_year BETWEEN 1990 AND 2200),
    expected_graduation_year smallint     NOT NULL CHECK (expected_graduation_year BETWEEN 1990 AND 2210),
    section                  varchar(20),
    capacity                 integer      CHECK (capacity IS NULL OR capacity BETWEEN 1 AND 100000),
    status                   varchar(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('PLANNED', 'ACTIVE', 'GRADUATED', 'INACTIVE')),
    created_at               timestamptz  NOT NULL DEFAULT now(),
    created_by               uuid,
    updated_at               timestamptz  NOT NULL DEFAULT now(),
    updated_by               uuid,
    version                  bigint       NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    CHECK (expected_graduation_year >= admission_year),
    FOREIGN KEY (tenant_id, course_id) REFERENCES course (tenant_id, id),
    FOREIGN KEY (tenant_id, academic_year_id) REFERENCES academic_year (tenant_id, id)
);
CREATE UNIQUE INDEX batch_code_uq ON batch (tenant_id, course_id, lower(code));

-- ---------------------------------------------------------------------------------------------------------
-- Subject offerings: a subject delivered to a batch in a term (no duplication of subject masters).
-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE subject_offering (
    id               uuid PRIMARY KEY,
    tenant_id        uuid         NOT NULL REFERENCES tenant (id),
    subject_id       uuid         NOT NULL,
    course_id        uuid         NOT NULL,
    batch_id         uuid         NOT NULL,
    academic_year_id uuid         NOT NULL,
    term_id          uuid         NOT NULL,
    term_number      smallint     NOT NULL CHECK (term_number BETWEEN 1 AND 40),
    section          varchar(20),
    credits          numeric(4,1) NOT NULL CHECK (credits >= 0 AND credits <= 40),
    status           varchar(20)  NOT NULL DEFAULT 'PLANNED' CHECK (status IN ('PLANNED', 'ACTIVE', 'COMPLETED', 'CANCELLED')),
    created_at       timestamptz  NOT NULL DEFAULT now(),
    created_by       uuid,
    updated_at       timestamptz  NOT NULL DEFAULT now(),
    updated_by       uuid,
    version          bigint       NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, subject_id) REFERENCES subject (tenant_id, id),
    FOREIGN KEY (tenant_id, course_id) REFERENCES course (tenant_id, id),
    FOREIGN KEY (tenant_id, batch_id) REFERENCES batch (tenant_id, id),
    FOREIGN KEY (tenant_id, academic_year_id) REFERENCES academic_year (tenant_id, id),
    FOREIGN KEY (tenant_id, term_id) REFERENCES academic_term (tenant_id, id)
);
CREATE UNIQUE INDEX subject_offering_uq ON subject_offering (tenant_id, batch_id, term_id, subject_id, coalesce(lower(section), ''))
    WHERE status <> 'CANCELLED';
CREATE INDEX subject_offering_term_idx ON subject_offering (tenant_id, term_id, status);

-- Faculty allocation: existing staff profiles teach offerings; ended assignments stay as history.
CREATE TABLE faculty_assignment (
    id               uuid PRIMARY KEY,
    tenant_id        uuid         NOT NULL REFERENCES tenant (id),
    offering_id      uuid         NOT NULL,
    staff_profile_id uuid         NOT NULL,
    is_primary       boolean      NOT NULL DEFAULT false,
    starts_on        date         NOT NULL,
    ends_on          date,
    ended_at         timestamptz,
    ended_by         uuid,
    end_reason       varchar(500),
    created_at       timestamptz  NOT NULL DEFAULT now(),
    created_by       uuid,
    UNIQUE (tenant_id, id),
    CHECK (ends_on IS NULL OR ends_on >= starts_on),
    FOREIGN KEY (tenant_id, offering_id) REFERENCES subject_offering (tenant_id, id),
    FOREIGN KEY (tenant_id, staff_profile_id) REFERENCES staff_profile (tenant_id, id)
);
-- one open assignment per person per offering, and at most one open primary per offering
CREATE UNIQUE INDEX faculty_assignment_open_uq ON faculty_assignment (tenant_id, offering_id, staff_profile_id) WHERE ended_at IS NULL;
CREATE UNIQUE INDEX faculty_assignment_primary_uq ON faculty_assignment (tenant_id, offering_id) WHERE ended_at IS NULL AND is_primary;

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['program', 'course', 'subject', 'curriculum_entry', 'subject_prerequisite', 'academic_year',
                             'academic_term', 'batch', 'subject_offering', 'faculty_assignment'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id())', t);
    END LOOP;
END $$;

-- ---------------------------------------------------------------------------------------------------------
-- Permissions (catalogue domain ACADEMICS) for system roles.
-- ---------------------------------------------------------------------------------------------------------
UPDATE role SET permissions = permissions || ARRAY['academics.read', 'program.manage', 'subject.manage', 'academic_calendar.manage', 'batch.manage', 'faculty.assign']
    WHERE system_role AND code IN ('ORG_ADMIN', 'INSTITUTION_ADMIN');
UPDATE role SET permissions = permissions || ARRAY['academics.read', 'batch.manage', 'faculty.assign']
    WHERE system_role AND code = 'DEPARTMENT_HEAD';
UPDATE role SET permissions = permissions || ARRAY['academics.read']
    WHERE system_role AND code IN ('CAMPUS_ADMIN', 'FACULTY', 'EXAM_CONTROLLER', 'MANAGEMENT', 'EVALUATOR', 'PLACEMENT_OFFICER', 'ASSESSMENT_DESIGNER');
