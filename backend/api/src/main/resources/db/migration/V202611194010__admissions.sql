-- Session 5, add-on E: admission management and enrollment automation (demo programme, design approved 2026-11-16).
-- Tenant-owned tables with the standard RLS policy and composite foreign keys. Applicant personal data is encrypted
-- (FieldCipher, *_enc). Event logs, screening results, published merit lists and offers' history are append-only.
-- Renumbered above the highest version on main before merge (rule 5).

-- an admission cycle of one institution (university or college) for one academic year
CREATE TABLE admission_cycle (
    id                uuid PRIMARY KEY,
    tenant_id         uuid          NOT NULL REFERENCES tenant (id),
    institution_id    uuid          NOT NULL,
    academic_year_id  uuid          NOT NULL,
    portal_id         uuid          NOT NULL,                  -- the exam portal applicants sign in to (candidate realm)
    code              varchar(30)   NOT NULL CHECK (code ~ '^[A-Za-z0-9][A-Za-z0-9._/-]{0,29}$'),
    name              varchar(150)  NOT NULL CHECK (length(btrim(name)) > 0),
    opens_at          timestamptz   NOT NULL,
    closes_at         timestamptz   NOT NULL,
    status            varchar(20)   NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'OPEN', 'CLOSED', 'COMPLETED')),
    created_by        uuid          NOT NULL,
    created_at        timestamptz   NOT NULL DEFAULT now(),
    updated_at        timestamptz   NOT NULL DEFAULT now(),
    version           bigint        NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    CHECK (closes_at > opens_at),
    FOREIGN KEY (tenant_id, institution_id) REFERENCES org_unit (tenant_id, id),
    FOREIGN KEY (tenant_id, academic_year_id) REFERENCES academic_year (tenant_id, id),
    FOREIGN KEY (tenant_id, portal_id) REFERENCES exam_portal (tenant_id, id)
);
CREATE UNIQUE INDEX admission_cycle_code_uq ON admission_cycle (tenant_id, institution_id, lower(code));

-- a programme and course on offer in a cycle, with seats, category quotas and merit weights
CREATE TABLE admission_offering (
    id                      uuid PRIMARY KEY,
    tenant_id               uuid          NOT NULL REFERENCES tenant (id),
    cycle_id                uuid          NOT NULL,
    program_id              uuid          NOT NULL,
    course_id               uuid          NOT NULL,
    batch_id                uuid,                              -- the batch admitted students are enrolled in (optional)
    seats                   integer       NOT NULL CHECK (seats BETWEEN 1 AND 10000),
    category_quotas         jsonb         NOT NULL DEFAULT '{}'::jsonb,   -- {"GENERAL": 30, ...}; codes are admission categories
    qualifying_weight       smallint      NOT NULL DEFAULT 100 CHECK (qualifying_weight BETWEEN 0 AND 100),
    test_weight             smallint      NOT NULL DEFAULT 0 CHECK (test_weight BETWEEN 0 AND 100),
    entrance_schedule_id    uuid,                              -- an ordinary exam schedule (examination engine)
    required_documents      text[]        NOT NULL DEFAULT '{}',
    status                  varchar(20)   NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'WITHDRAWN')),
    created_at              timestamptz   NOT NULL DEFAULT now(),
    updated_at              timestamptz   NOT NULL DEFAULT now(),
    version                 bigint        NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, cycle_id, course_id),
    CHECK (qualifying_weight + test_weight = 100),
    FOREIGN KEY (tenant_id, cycle_id) REFERENCES admission_cycle (tenant_id, id),
    FOREIGN KEY (tenant_id, program_id) REFERENCES program (tenant_id, id),
    FOREIGN KEY (tenant_id, course_id) REFERENCES course (tenant_id, id),
    FOREIGN KEY (tenant_id, batch_id) REFERENCES batch (tenant_id, id),
    FOREIGN KEY (tenant_id, entrance_schedule_id) REFERENCES exam_schedule (tenant_id, id)
);

-- typed eligibility rules (no free expressions or regular expressions)
CREATE TABLE admission_eligibility_rule (
    id           uuid PRIMARY KEY,
    tenant_id    uuid          NOT NULL REFERENCES tenant (id),
    offering_id  uuid          NOT NULL,
    kind         varchar(30)   NOT NULL CHECK (kind IN ('MIN_QUALIFYING_PERCENT', 'AGE_RANGE', 'REQUIRED_DOCUMENT', 'MIN_TEST_PERCENT')),
    parameters   jsonb         NOT NULL,
    position     smallint      NOT NULL DEFAULT 1,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, offering_id) REFERENCES admission_offering (tenant_id, id)
);
CREATE INDEX admission_rule_offering_idx ON admission_eligibility_rule (tenant_id, offering_id);

-- the applicant: one per candidate account (candidate realm); personal data encrypted
CREATE TABLE applicant (
    id               uuid PRIMARY KEY,
    tenant_id        uuid          NOT NULL REFERENCES tenant (id),
    candidate_id     uuid          NOT NULL,
    email_index      char(64)      NOT NULL,
    first_name       varchar(100)  NOT NULL,
    middle_name      varchar(100),
    last_name        varchar(100),
    date_of_birth_enc text,
    mobile_enc       text,
    address_enc      text,
    category_enc     text,                                      -- admission category (personal information)
    gender           varchar(20)   CHECK (gender IN ('FEMALE', 'MALE', 'OTHER', 'UNDISCLOSED')),
    nationality      varchar(60),
    created_at       timestamptz   NOT NULL DEFAULT now(),
    updated_at       timestamptz   NOT NULL DEFAULT now(),
    version          bigint        NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, candidate_id),
    FOREIGN KEY (tenant_id, candidate_id) REFERENCES candidate_account (tenant_id, id)
);

CREATE TABLE admission_application (
    id                      uuid PRIMARY KEY,
    tenant_id               uuid          NOT NULL REFERENCES tenant (id),
    cycle_id                uuid          NOT NULL,
    offering_id             uuid          NOT NULL,
    applicant_id            uuid          NOT NULL,
    application_no          varchar(40)   NOT NULL,
    qualifying_percent      numeric(5,2)  CHECK (qualifying_percent BETWEEN 0 AND 100),
    qualifying_board        varchar(150),
    status                  varchar(20)   NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'SUBMITTED', 'ELIGIBLE', 'INELIGIBLE', 'SHORTLISTED',
                                              'REJECTED', 'TESTED', 'RANKED', 'OFFERED', 'ACCEPTED', 'DECLINED', 'EXPIRED', 'CONVERTED', 'WITHDRAWN')),
    test_percent            numeric(5,2),                       -- from the released entrance-test result
    composite_score         numeric(6,3),
    submitted_at            timestamptz,
    student_id              uuid,                               -- after conversion
    created_at              timestamptz   NOT NULL DEFAULT now(),
    updated_at              timestamptz   NOT NULL DEFAULT now(),
    version                 bigint        NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, offering_id, applicant_id),
    FOREIGN KEY (tenant_id, cycle_id) REFERENCES admission_cycle (tenant_id, id),
    FOREIGN KEY (tenant_id, offering_id) REFERENCES admission_offering (tenant_id, id),
    FOREIGN KEY (tenant_id, applicant_id) REFERENCES applicant (tenant_id, id),
    FOREIGN KEY (tenant_id, student_id) REFERENCES student (tenant_id, id)
);
CREATE UNIQUE INDEX admission_application_no_uq ON admission_application (tenant_id, cycle_id, application_no);
CREATE INDEX admission_application_status_idx ON admission_application (tenant_id, offering_id, status);

-- every status change of an application (append-only; reason codes and staff notes, never personal values)
CREATE TABLE admission_application_event (
    id              uuid PRIMARY KEY,
    tenant_id       uuid          NOT NULL REFERENCES tenant (id),
    application_id  uuid          NOT NULL,
    from_status     varchar(20),
    to_status       varchar(20)   NOT NULL,
    reason          varchar(500),
    actor_id        uuid,                                       -- staff user; null for the applicant or the system
    actor_kind      varchar(20)   NOT NULL CHECK (actor_kind IN ('STAFF', 'APPLICANT', 'SYSTEM')),
    occurred_at     timestamptz   NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, application_id) REFERENCES admission_application (tenant_id, id)
);
CREATE INDEX admission_event_application_idx ON admission_application_event (tenant_id, application_id, occurred_at);

-- applicant documents through the shared quarantine pipeline (platform.documents.QuarantinedDocuments, ADR-0006)
CREATE TABLE admission_document (
    id                   uuid PRIMARY KEY,
    tenant_id            uuid          NOT NULL REFERENCES tenant (id),
    application_id       uuid          NOT NULL,
    category             varchar(40)   NOT NULL,
    file_name            varchar(200)  NOT NULL,
    content_type         varchar(40)   NOT NULL CHECK (content_type IN ('application/pdf', 'image/jpeg', 'image/png')),
    size_bytes           integer       NOT NULL CHECK (size_bytes BETWEEN 1 AND 5242880),
    sha256               char(64)      NOT NULL,
    object_path          varchar(300)  NOT NULL,
    scan_status          varchar(20)   NOT NULL DEFAULT 'PENDING' CHECK (scan_status IN ('PENDING', 'CLEAN', 'INFECTED', 'ERROR')),
    scan_detail          varchar(300),
    scanned_at           timestamptz,
    verification_status  varchar(20)   NOT NULL DEFAULT 'PENDING' CHECK (verification_status IN ('PENDING', 'VERIFIED', 'REJECTED')),
    verification_note    varchar(500),
    verified_by          uuid,
    verified_at          timestamptz,
    status               varchar(20)   NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'REPLACED')),
    uploaded_at          timestamptz   NOT NULL DEFAULT now(),
    purged_at            timestamptz,
    UNIQUE (tenant_id, id),
    -- a document is verified only after a CLEAN scan (D-19: never treated as clean otherwise)
    CHECK (verification_status = 'PENDING' OR scan_status = 'CLEAN'),
    FOREIGN KEY (tenant_id, application_id) REFERENCES admission_application (tenant_id, id)
);
CREATE INDEX admission_document_app_idx ON admission_document (tenant_id, application_id, status);
CREATE INDEX admission_document_scan_idx ON admission_document (scan_status) WHERE scan_status = 'PENDING';

-- one evaluation of every rule for an application (append-only; the inputs are shown to staff)
CREATE TABLE admission_screening (
    id              uuid PRIMARY KEY,
    tenant_id       uuid          NOT NULL REFERENCES tenant (id),
    application_id  uuid          NOT NULL,
    rule_id         uuid,
    kind            varchar(30)   NOT NULL,
    result          varchar(10)   NOT NULL CHECK (result IN ('PASS', 'FAIL', 'MANUAL')),
    detail          varchar(300)  NOT NULL,                     -- inputs and threshold, e.g. "qualifying 72.50% >= 60%"
    evaluated_at    timestamptz   NOT NULL DEFAULT now(),
    evaluation_id   uuid          NOT NULL,                     -- groups the rows of one screening run
    FOREIGN KEY (tenant_id, application_id) REFERENCES admission_application (tenant_id, id)
);
CREATE INDEX admission_screening_app_idx ON admission_screening (tenant_id, application_id, evaluated_at);

CREATE TABLE merit_list (
    id              uuid PRIMARY KEY,
    tenant_id       uuid          NOT NULL REFERENCES tenant (id),
    offering_id     uuid          NOT NULL,
    list_no         integer       NOT NULL,
    status          varchar(20)   NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'PENDING_APPROVAL', 'PUBLISHED', 'REJECTED', 'SUPERSEDED')),
    entries_hash    char(64)      NOT NULL,
    approval_id     uuid,
    prepared_by     uuid          NOT NULL,
    published_at    timestamptz,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    version         bigint        NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, offering_id, list_no),
    FOREIGN KEY (tenant_id, offering_id) REFERENCES admission_offering (tenant_id, id)
);
CREATE UNIQUE INDEX merit_list_open_uq ON merit_list (tenant_id, offering_id) WHERE status IN ('DRAFT', 'PENDING_APPROVAL');

CREATE TABLE merit_list_entry (
    tenant_id        uuid          NOT NULL REFERENCES tenant (id),
    merit_list_id    uuid          NOT NULL,
    rank             integer       NOT NULL CHECK (rank >= 1),
    application_id   uuid          NOT NULL,
    composite_score  numeric(6,3)  NOT NULL,
    qualifying_percent numeric(5,2),
    test_percent     numeric(5,2),
    within_seats     boolean       NOT NULL,
    PRIMARY KEY (merit_list_id, rank),
    UNIQUE (merit_list_id, application_id),
    FOREIGN KEY (tenant_id, merit_list_id) REFERENCES merit_list (tenant_id, id),
    FOREIGN KEY (tenant_id, application_id) REFERENCES admission_application (tenant_id, id)
);

CREATE TABLE admission_offer (
    id               uuid PRIMARY KEY,
    tenant_id        uuid          NOT NULL REFERENCES tenant (id),
    application_id   uuid          NOT NULL,
    merit_list_id    uuid          NOT NULL,
    status           varchar(20)   NOT NULL DEFAULT 'PENDING_APPROVAL'
                     CHECK (status IN ('PENDING_APPROVAL', 'ISSUED', 'ACCEPTED', 'DECLINED', 'EXPIRED', 'WITHDRAWN', 'REJECTED')),
    expires_at       timestamptz   NOT NULL,
    approval_id      uuid,
    responded_at     timestamptz,
    created_at       timestamptz   NOT NULL DEFAULT now(),
    version          bigint        NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, application_id) REFERENCES admission_application (tenant_id, id),
    FOREIGN KEY (tenant_id, merit_list_id) REFERENCES merit_list (tenant_id, id)
);
CREATE UNIQUE INDEX admission_offer_open_uq ON admission_offer (tenant_id, application_id) WHERE status IN ('PENDING_APPROVAL', 'ISSUED', 'ACCEPTED');

-- guards: append-only logs; a published merit list and its entries never change
CREATE FUNCTION admission_append_only() RETURNS trigger LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION '% rows never change', TG_TABLE_NAME USING ERRCODE = '23514', CONSTRAINT = 'admission_append_only';
END $$;
CREATE TRIGGER admission_event_guard BEFORE UPDATE OR DELETE ON admission_application_event FOR EACH ROW EXECUTE FUNCTION admission_append_only();
CREATE TRIGGER admission_screening_guard BEFORE UPDATE OR DELETE ON admission_screening FOR EACH ROW EXECUTE FUNCTION admission_append_only();
CREATE TRIGGER merit_list_entry_guard BEFORE UPDATE OR DELETE ON merit_list_entry FOR EACH ROW EXECUTE FUNCTION admission_append_only();

CREATE FUNCTION merit_list_guard() RETURNS trigger LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'merit lists are superseded, never deleted' USING ERRCODE = '23514', CONSTRAINT = 'merit_list_permanent';
    END IF;
    IF NEW.entries_hash <> OLD.entries_hash OR NEW.offering_id <> OLD.offering_id OR NEW.list_no <> OLD.list_no THEN
        RAISE EXCEPTION 'a merit list''s entries never change' USING ERRCODE = '23514', CONSTRAINT = 'merit_list_frozen';
    END IF;
    IF OLD.status = 'PUBLISHED' AND NEW.status NOT IN ('PUBLISHED', 'SUPERSEDED') THEN
        RAISE EXCEPTION 'a published merit list is only superseded' USING ERRCODE = '23514', CONSTRAINT = 'merit_list_published';
    END IF;
    IF NEW.status = 'PUBLISHED' AND OLD.status <> 'PUBLISHED' AND (OLD.status <> 'PENDING_APPROVAL' OR NEW.approval_id IS NULL) THEN
        RAISE EXCEPTION 'a merit list is published only through an independent approval' USING ERRCODE = '23514', CONSTRAINT = 'merit_list_approval';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER merit_list_guard BEFORE UPDATE OR DELETE ON merit_list FOR EACH ROW EXECUTE FUNCTION merit_list_guard();

CREATE FUNCTION admission_offer_guard() RETURNS trigger LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'offers are withdrawn, never deleted' USING ERRCODE = '23514', CONSTRAINT = 'admission_offer_permanent';
    END IF;
    IF NEW.status = 'ISSUED' AND OLD.status <> 'ISSUED' AND (OLD.status <> 'PENDING_APPROVAL' OR NEW.approval_id IS NULL) THEN
        RAISE EXCEPTION 'an offer is issued only through an independent approval' USING ERRCODE = '23514', CONSTRAINT = 'admission_offer_approval';
    END IF;
    IF NEW.application_id <> OLD.application_id OR NEW.merit_list_id <> OLD.merit_list_id THEN
        RAISE EXCEPTION 'an offer''s application never changes' USING ERRCODE = '23514', CONSTRAINT = 'admission_offer_binding';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER admission_offer_guard BEFORE UPDATE OR DELETE ON admission_offer FOR EACH ROW EXECUTE FUNCTION admission_offer_guard();

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['admission_cycle', 'admission_offering', 'admission_eligibility_rule', 'applicant', 'admission_application',
                             'admission_application_event', 'admission_document', 'admission_screening', 'merit_list', 'merit_list_entry',
                             'admission_offer'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id())', t);
    END LOOP;
END $$;
REVOKE UPDATE, DELETE ON admission_application_event, admission_screening, merit_list_entry FROM ${appRole};
REVOKE DELETE ON admission_cycle, admission_offering, applicant, admission_application, admission_document, merit_list, admission_offer FROM ${appRole};
