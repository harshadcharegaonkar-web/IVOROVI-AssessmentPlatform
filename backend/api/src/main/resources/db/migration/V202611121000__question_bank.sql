-- Increment 2.1: question bank (plan docs/phase-2/increment-2.1-plan.md, ADR-0009, ADR-0010).
-- Institution-scoped banks, configurable taxonomy, immutable question versions, encrypted answer keys in their own
-- table, review/approval policies enforced by the service AND by the integrity rules below, image assets with
-- quarantine + scanning, imports with preview, and a reference registry that protects versions used elsewhere.

CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ===================================================================================================== banks, policy
CREATE TABLE question_bank (
    id                      uuid PRIMARY KEY,
    tenant_id               uuid          NOT NULL REFERENCES tenant (id),
    org_unit_id             uuid,                                   -- NULL = organization-wide bank
    code                    varchar(40)   NOT NULL CHECK (code ~ '^[A-Za-z0-9][A-Za-z0-9._-]{1,39}$'),
    name                    varchar(150)  NOT NULL CHECK (length(btrim(name)) > 0),
    description             varchar(1000),
    intended_uses           text[]        NOT NULL DEFAULT '{}'
                            CHECK (intended_uses <@ ARRAY['ADMISSION_EXAM','ADMISSION_SCREENING','ACADEMIC','PLACEMENT','PRACTICE']::text[]),
    approval_policy         varchar(20)   NOT NULL DEFAULT 'STANDARD' CHECK (approval_policy IN ('STANDARD', 'HIGH_STAKES')),
    required_reviews        smallint      NOT NULL DEFAULT 1 CHECK (required_reviews BETWEEN 1 AND 3),
    approver_distinct       boolean       NOT NULL DEFAULT false,      -- approver must not be one of the reviewers
    free_tags               boolean       NOT NULL DEFAULT true,
    default_marks           numeric(7,2)  NOT NULL DEFAULT 1 CHECK (default_marks > 0),
    default_negative_marks  numeric(7,2)  NOT NULL DEFAULT 0 CHECK (default_negative_marks >= 0),
    status                  varchar(20)   NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    created_by              uuid          NOT NULL,
    created_at              timestamptz   NOT NULL DEFAULT now(),
    updated_at              timestamptz   NOT NULL DEFAULT now(),
    version                 bigint        NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, org_unit_id) REFERENCES org_unit (tenant_id, id),
    -- high-stakes admission banks: three distinct people (author, reviewer, approver)
    CONSTRAINT question_bank_admission_high_stakes CHECK (NOT ('ADMISSION_EXAM' = ANY (intended_uses)) OR approval_policy = 'HIGH_STAKES'),
    CONSTRAINT question_bank_high_stakes_distinct CHECK (approval_policy <> 'HIGH_STAKES' OR approver_distinct)
);
CREATE UNIQUE INDEX question_bank_code_uq ON question_bank (tenant_id, lower(code));

-- Institution floors: every bank at or below the unit is at least this strict (NULL unit = whole organization).
CREATE TABLE question_policy_floor (
    id                uuid PRIMARY KEY,
    tenant_id         uuid         NOT NULL REFERENCES tenant (id),
    org_unit_id       uuid,
    minimum_policy    varchar(20)  NOT NULL CHECK (minimum_policy IN ('STANDARD', 'HIGH_STAKES')),
    minimum_reviews   smallint     NOT NULL DEFAULT 1 CHECK (minimum_reviews BETWEEN 1 AND 3),
    approver_distinct boolean      NOT NULL DEFAULT false,
    updated_by        uuid         NOT NULL,
    updated_at        timestamptz  NOT NULL DEFAULT now(),
    version           bigint       NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, org_unit_id) REFERENCES org_unit (tenant_id, id)
);
CREATE UNIQUE INDEX question_policy_floor_unit_uq ON question_policy_floor (tenant_id, coalesce(org_unit_id, '00000000-0000-0000-0000-000000000000'::uuid));

-- ===================================================================================================== taxonomy
CREATE TABLE question_subject (
    id                  uuid PRIMARY KEY,
    tenant_id           uuid         NOT NULL REFERENCES tenant (id),
    org_unit_id         uuid,
    code                varchar(30)  NOT NULL CHECK (code ~ '^[A-Za-z0-9][A-Za-z0-9._/-]{0,29}$'),
    name                varchar(150) NOT NULL CHECK (length(btrim(name)) > 0),
    academic_subject_id uuid,
    status              varchar(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at          timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, org_unit_id) REFERENCES org_unit (tenant_id, id),
    FOREIGN KEY (tenant_id, academic_subject_id) REFERENCES subject (tenant_id, id)
);
CREATE UNIQUE INDEX question_subject_code_uq ON question_subject (tenant_id, coalesce(org_unit_id, '00000000-0000-0000-0000-000000000000'::uuid), lower(code));

CREATE TABLE question_topic (
    id          uuid PRIMARY KEY,
    tenant_id   uuid         NOT NULL REFERENCES tenant (id),
    subject_id  uuid         NOT NULL,
    parent_id   uuid,
    level       smallint     NOT NULL CHECK (level IN (1, 2)),          -- 1 = topic, 2 = subtopic
    name        varchar(150) NOT NULL CHECK (length(btrim(name)) > 0),
    status      varchar(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at  timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, subject_id) REFERENCES question_subject (tenant_id, id),
    FOREIGN KEY (tenant_id, parent_id) REFERENCES question_topic (tenant_id, id),
    CHECK ((level = 1) = (parent_id IS NULL))
);
CREATE UNIQUE INDEX question_topic_name_uq ON question_topic (tenant_id, subject_id, coalesce(parent_id, '00000000-0000-0000-0000-000000000000'::uuid), lower(name));

CREATE TABLE learning_outcome (
    id           uuid PRIMARY KEY,
    tenant_id    uuid          NOT NULL REFERENCES tenant (id),
    subject_id   uuid          NOT NULL,
    code         varchar(30)   NOT NULL,
    description  varchar(500)  NOT NULL,
    bloom_level  varchar(20)   CHECK (bloom_level IN ('REMEMBER', 'UNDERSTAND', 'APPLY', 'ANALYSE', 'EVALUATE', 'CREATE')),
    status       varchar(20)   NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at   timestamptz   NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, subject_id) REFERENCES question_subject (tenant_id, id)
);
CREATE UNIQUE INDEX learning_outcome_code_uq ON learning_outcome (tenant_id, subject_id, lower(code));

CREATE TABLE difficulty_level (
    id          uuid PRIMARY KEY,
    tenant_id   uuid         NOT NULL REFERENCES tenant (id),
    code        varchar(30)  NOT NULL,
    label       varchar(80)  NOT NULL,
    rank        smallint     NOT NULL CHECK (rank BETWEEN 1 AND 20),
    status      varchar(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    UNIQUE (tenant_id, id)
);
CREATE UNIQUE INDEX difficulty_level_code_uq ON difficulty_level (tenant_id, lower(code));

CREATE TABLE question_tag (
    id          uuid PRIMARY KEY,
    tenant_id   uuid         NOT NULL REFERENCES tenant (id),
    name        varchar(50)  NOT NULL CHECK (name ~ '^[\w .+#/-]{1,50}$'),
    UNIQUE (tenant_id, id)
);
CREATE UNIQUE INDEX question_tag_name_uq ON question_tag (tenant_id, lower(name));

CREATE TABLE question_attribute_definition (
    id                    uuid PRIMARY KEY,
    tenant_id             uuid          NOT NULL REFERENCES tenant (id),
    key                   varchar(40)   NOT NULL CHECK (key ~ '^[a-z][a-z0-9_]{1,39}$'),
    label                 varchar(80)   NOT NULL,
    type                  varchar(20)   NOT NULL CHECK (type IN ('TEXT', 'NUMBER', 'BOOLEAN', 'CHOICE', 'DATE')),
    allowed_values        text[]        NOT NULL DEFAULT '{}',
    required_for_approval boolean       NOT NULL DEFAULT false,
    status                varchar(20)   NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    UNIQUE (tenant_id, id),
    CHECK (type <> 'CHOICE' OR cardinality(allowed_values) > 0)
);
CREATE UNIQUE INDEX question_attribute_key_uq ON question_attribute_definition (tenant_id, key);

-- ===================================================================================================== questions
CREATE TABLE question (
    id                   uuid PRIMARY KEY,
    tenant_id            uuid         NOT NULL REFERENCES tenant (id),
    bank_id              uuid         NOT NULL,
    current_version_id   uuid,
    approved_version_id  uuid,
    status               varchar(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'RETIRED')),
    created_by           uuid         NOT NULL,
    created_at           timestamptz  NOT NULL DEFAULT now(),
    updated_at           timestamptz  NOT NULL DEFAULT now(),
    version              bigint       NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, bank_id) REFERENCES question_bank (tenant_id, id)
);
CREATE INDEX question_bank_idx ON question (tenant_id, bank_id);

CREATE TABLE question_version (
    id                   uuid PRIMARY KEY,
    tenant_id            uuid          NOT NULL REFERENCES tenant (id),
    question_id          uuid          NOT NULL,
    version_no           integer       NOT NULL CHECK (version_no >= 1),
    state                varchar(20)   NOT NULL DEFAULT 'DRAFT'
                         CHECK (state IN ('DRAFT', 'IN_REVIEW', 'REVIEWED', 'APPROVED', 'SUPERSEDED', 'RETIRED')),
    type_code            varchar(40)   NOT NULL,
    type_schema_version  smallint      NOT NULL DEFAULT 1,
    content              jsonb         NOT NULL,                 -- statement (ivorovi.content.v1); never answer information
    body                 jsonb         NOT NULL DEFAULT '{}',    -- type-specific, non-secret (options, unit, precision shown)
    marking              jsonb         NOT NULL DEFAULT '{}',    -- non-secret marking parameters (e.g. scoring mode)
    marks                numeric(7,2)  NOT NULL CHECK (marks > 0 AND marks <= 1000),
    negative_marks       numeric(7,2)  NOT NULL DEFAULT 0 CHECK (negative_marks >= 0 AND negative_marks <= marks),
    subject_id           uuid,
    topic_id             uuid,
    subtopic_id          uuid,
    difficulty_id        uuid,
    outcome_ids          uuid[]        NOT NULL DEFAULT '{}',
    tags                 text[]        NOT NULL DEFAULT '{}',
    attributes           jsonb         NOT NULL DEFAULT '{}',
    language             varchar(10)   NOT NULL DEFAULT 'en',
    plain_text           text          NOT NULL DEFAULT '',      -- statement + option text only (search); never keys
    normalized_text      text          NOT NULL DEFAULT '',      -- for near-duplicate detection
    dup_hash             char(64)      NOT NULL,                 -- exact-duplicate hash (statement + options, no key)
    review_hash          char(64)      NOT NULL,                 -- everything a reviewer approves, key included (computed by the service)
    search_vector        tsvector      GENERATED ALWAYS AS (to_tsvector('simple', plain_text)) STORED,
    policy_snapshot      jsonb,
    author_id            uuid          NOT NULL,
    created_at           timestamptz   NOT NULL DEFAULT now(),
    updated_at           timestamptz   NOT NULL DEFAULT now(),
    submitted_at         timestamptz,
    first_submitted_at   timestamptz,
    approved_by          uuid,
    approved_at          timestamptz,
    retired_by           uuid,
    retired_at           timestamptz,
    source               varchar(20)   NOT NULL DEFAULT 'MANUAL' CHECK (source IN ('MANUAL', 'IMPORT')),
    import_id            uuid,
    version              bigint        NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    UNIQUE (question_id, version_no),
    FOREIGN KEY (tenant_id, question_id) REFERENCES question (tenant_id, id),
    FOREIGN KEY (tenant_id, subject_id) REFERENCES question_subject (tenant_id, id),
    FOREIGN KEY (tenant_id, topic_id) REFERENCES question_topic (tenant_id, id),
    FOREIGN KEY (tenant_id, subtopic_id) REFERENCES question_topic (tenant_id, id),
    FOREIGN KEY (tenant_id, difficulty_id) REFERENCES difficulty_level (tenant_id, id),
    -- never self-approval (all policies)
    CONSTRAINT question_version_no_self_approval CHECK (approved_by IS NULL OR approved_by <> author_id),
    CONSTRAINT question_version_approval_recorded CHECK ((state IN ('APPROVED', 'SUPERSEDED', 'RETIRED')) = (approved_by IS NOT NULL))
);
-- one open (editable or in-workflow) version per question
CREATE UNIQUE INDEX question_version_open_uq ON question_version (question_id) WHERE state IN ('DRAFT', 'IN_REVIEW', 'REVIEWED');
CREATE INDEX question_version_state_idx ON question_version (tenant_id, state);
CREATE INDEX question_version_search_idx ON question_version USING gin (search_vector);
CREATE INDEX question_version_tags_idx ON question_version USING gin (tags);
CREATE INDEX question_version_trgm_idx ON question_version USING gin (normalized_text gin_trgm_ops);
CREATE INDEX question_version_dup_idx ON question_version (tenant_id, dup_hash);
ALTER TABLE question ADD FOREIGN KEY (tenant_id, current_version_id) REFERENCES question_version (tenant_id, id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE question ADD FOREIGN KEY (tenant_id, approved_version_id) REFERENCES question_version (tenant_id, id) DEFERRABLE INITIALLY DEFERRED;

-- Answer keys and explanations: their own table, encrypted (FieldCipher, AAD = column + tenant). Only AnswerKeyStore reads it.
CREATE TABLE question_answer_key (
    version_id       uuid PRIMARY KEY,
    tenant_id        uuid         NOT NULL REFERENCES tenant (id),
    key_enc          text         NOT NULL,
    explanation_enc  text,
    key_hash         char(64)     NOT NULL,                -- HMAC of the canonical key, for review hashing/integrity
    updated_at       timestamptz  NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, version_id) REFERENCES question_version (tenant_id, id)
);

CREATE TABLE question_review (
    id                  uuid PRIMARY KEY,
    tenant_id           uuid          NOT NULL REFERENCES tenant (id),
    version_id          uuid          NOT NULL,
    reviewer_id         uuid          NOT NULL,
    decision            varchar(30)   NOT NULL CHECK (decision IN ('REVIEWED', 'CHANGES_REQUESTED')),
    reviewed_hash       char(64)      NOT NULL,
    checklist           jsonb         NOT NULL DEFAULT '{}',
    comment             varchar(2000),
    created_at          timestamptz   NOT NULL DEFAULT now(),
    invalidated_at      timestamptz,
    invalidated_reason  varchar(200),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, version_id) REFERENCES question_version (tenant_id, id)
);
CREATE INDEX question_review_version_idx ON question_review (tenant_id, version_id);

-- Append-only record of every state transition (actor, time, from, to, reason).
CREATE TABLE question_transition (
    id          uuid PRIMARY KEY,
    tenant_id   uuid          NOT NULL REFERENCES tenant (id),
    version_id  uuid          NOT NULL,
    from_state  varchar(20),
    to_state    varchar(20)   NOT NULL,
    actor_id    uuid          NOT NULL,
    reason      varchar(500),
    at          timestamptz   NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, version_id) REFERENCES question_version (tenant_id, id)
);
CREATE INDEX question_transition_version_idx ON question_transition (tenant_id, version_id, at);

-- Records that keep a version alive (assessments from 2.2, exam papers, results, demonstrations). A referenced version can
-- never be deleted; only APPROVED, SUPERSEDED or RETIRED versions can be referenced.
CREATE TABLE question_version_reference (
    id             uuid PRIMARY KEY,
    tenant_id      uuid          NOT NULL REFERENCES tenant (id),
    version_id     uuid          NOT NULL,
    referrer_type  varchar(40)   NOT NULL,
    referrer_id    varchar(64)   NOT NULL,
    created_at     timestamptz   NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, version_id, referrer_type, referrer_id),
    FOREIGN KEY (tenant_id, version_id) REFERENCES question_version (tenant_id, id)
);

-- ===================================================================================================== assets
CREATE TABLE question_asset (
    id            uuid PRIMARY KEY,
    tenant_id     uuid          NOT NULL REFERENCES tenant (id),
    bank_id       uuid          NOT NULL,
    sha256        char(64)      NOT NULL,
    media_type    varchar(20)   NOT NULL CHECK (media_type IN ('image/png', 'image/jpeg')),
    width         integer       NOT NULL CHECK (width BETWEEN 1 AND 4096),
    height        integer       NOT NULL CHECK (height BETWEEN 1 AND 4096),
    bytes         bigint        NOT NULL CHECK (bytes BETWEEN 1 AND 5242880),
    scan_status   varchar(20)   NOT NULL CHECK (scan_status IN ('PENDING', 'CLEAN', 'INFECTED', 'ERROR', 'NOT_SCANNED_DEV')),
    quarantine_key varchar(300) NOT NULL,
    served_key    varchar(300),
    file_name     varchar(200),
    alt_text      varchar(250),
    uploaded_by   uuid          NOT NULL,
    created_at    timestamptz   NOT NULL DEFAULT now(),
    scanned_at    timestamptz,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, bank_id) REFERENCES question_bank (tenant_id, id),
    CHECK ((served_key IS NOT NULL) = (scan_status IN ('CLEAN', 'NOT_SCANNED_DEV')))
);
CREATE INDEX question_asset_bank_idx ON question_asset (tenant_id, bank_id);

CREATE TABLE question_version_asset (
    tenant_id   uuid          NOT NULL REFERENCES tenant (id),
    version_id  uuid          NOT NULL,
    asset_id    uuid          NOT NULL,
    usage       varchar(20)   NOT NULL CHECK (usage IN ('STATEMENT', 'OPTION')),
    option_id   varchar(20)   NOT NULL DEFAULT '',
    alt_text    varchar(250),
    decorative  boolean       NOT NULL DEFAULT false,
    PRIMARY KEY (tenant_id, version_id, asset_id, usage, option_id),
    FOREIGN KEY (tenant_id, version_id) REFERENCES question_version (tenant_id, id),
    FOREIGN KEY (tenant_id, asset_id) REFERENCES question_asset (tenant_id, id),
    CHECK (decorative OR length(btrim(coalesce(alt_text, ''))) BETWEEN 1 AND 250)
);
CREATE INDEX question_version_asset_asset_idx ON question_version_asset (tenant_id, asset_id);

-- Institution storage quotas (closest unit at or above the bank's unit; NULL unit = organization default).
CREATE TABLE question_asset_quota (
    id           uuid PRIMARY KEY,
    tenant_id    uuid         NOT NULL REFERENCES tenant (id),
    org_unit_id  uuid,
    bytes_limit  bigint       NOT NULL CHECK (bytes_limit > 0),
    updated_by   uuid         NOT NULL,
    updated_at   timestamptz  NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, org_unit_id) REFERENCES org_unit (tenant_id, id)
);
CREATE UNIQUE INDEX question_asset_quota_unit_uq ON question_asset_quota (tenant_id, coalesce(org_unit_id, '00000000-0000-0000-0000-000000000000'::uuid));

-- ===================================================================================================== import
CREATE TABLE question_import (
    id            uuid PRIMARY KEY,
    tenant_id     uuid          NOT NULL REFERENCES tenant (id),
    bank_id       uuid          NOT NULL,
    file_name     varchar(200)  NOT NULL,
    format        varchar(10)   NOT NULL CHECK (format IN ('CSV', 'XLSX')),
    bytes         bigint        NOT NULL,
    sha256        char(64)      NOT NULL,
    object_key    varchar(300)  NOT NULL,
    scan_status   varchar(20)   NOT NULL,
    status        varchar(20)   NOT NULL DEFAULT 'PREVIEWED' CHECK (status IN ('PREVIEWED', 'COMMITTED', 'FAILED', 'EXPIRED')),
    total_rows    integer       NOT NULL DEFAULT 0,
    valid_rows    integer       NOT NULL DEFAULT 0,
    invalid_rows  integer       NOT NULL DEFAULT 0,
    duplicate_rows integer      NOT NULL DEFAULT 0,
    near_duplicate_rows integer NOT NULL DEFAULT 0,
    created_rows  integer       NOT NULL DEFAULT 0,
    created_by    uuid          NOT NULL,
    created_at    timestamptz   NOT NULL DEFAULT now(),
    committed_at  timestamptz,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, bank_id) REFERENCES question_bank (tenant_id, id)
);

CREATE TABLE question_import_row (
    id                   uuid PRIMARY KEY,
    tenant_id            uuid          NOT NULL REFERENCES tenant (id),
    import_id            uuid          NOT NULL,
    row_no               integer       NOT NULL,
    outcome              varchar(30)   NOT NULL CHECK (outcome IN ('VALID', 'INVALID', 'DUPLICATE_IN_FILE', 'DUPLICATE_IN_BANK', 'NEAR_DUPLICATE')),
    errors               jsonb         NOT NULL DEFAULT '[]',
    payload              jsonb,                     -- validated question without answer information
    key_enc              text,                      -- encrypted answer key (FieldCipher)
    dup_hash             char(64),
    matched_question_id  uuid,
    similarity           numeric(4,3),
    created_question_id  uuid,
    UNIQUE (tenant_id, id),
    UNIQUE (import_id, row_no),
    FOREIGN KEY (tenant_id, import_id) REFERENCES question_import (tenant_id, id)
);

-- ===================================================================================================== integrity triggers
-- Effective policy for a bank: the bank's own settings, raised by every floor at or above its unit.
CREATE FUNCTION question_effective_policy(p_bank uuid, OUT required_reviews int, OUT approver_distinct boolean, OUT high_stakes boolean)
    LANGUAGE sql STABLE AS
$$ SELECT greatest(b.required_reviews, coalesce(max(f.minimum_reviews), 1))::int,
          b.approver_distinct OR b.approval_policy = 'HIGH_STAKES' OR coalesce(bool_or(f.approver_distinct OR f.minimum_policy = 'HIGH_STAKES'), false),
          b.approval_policy = 'HIGH_STAKES' OR coalesce(bool_or(f.minimum_policy = 'HIGH_STAKES'), false)
   FROM question_bank b
   LEFT JOIN org_unit bu ON bu.id = b.org_unit_id
   LEFT JOIN question_policy_floor f ON f.tenant_id = b.tenant_id
        AND (f.org_unit_id IS NULL OR (bu.path IS NOT NULL AND EXISTS (SELECT 1 FROM org_unit fu WHERE fu.id = f.org_unit_id AND bu.path <@ fu.path)))
   WHERE b.id = p_bank
   GROUP BY b.required_reviews, b.approver_distinct, b.approval_policy $$;

CREATE FUNCTION question_version_guard() RETURNS trigger LANGUAGE plpgsql AS
$$
DECLARE
    content_changed boolean;
    pol record;
    valid_reviews int;
    approver_reviewed boolean;
    bank uuid;
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.state <> 'DRAFT' OR OLD.first_submitted_at IS NOT NULL THEN
            RAISE EXCEPTION 'question version % cannot be deleted (state %)', OLD.id, OLD.state USING ERRCODE = '23514', CONSTRAINT = 'question_version_immutable';
        END IF;
        IF EXISTS (SELECT 1 FROM question_version_reference r WHERE r.version_id = OLD.id) THEN
            RAISE EXCEPTION 'question version % is referenced', OLD.id USING ERRCODE = '23514', CONSTRAINT = 'question_version_referenced';
        END IF;
        RETURN OLD;
    END IF;

    content_changed := (OLD.type_code, OLD.type_schema_version, OLD.content, OLD.body, OLD.marking, OLD.marks, OLD.negative_marks,
                        OLD.subject_id, OLD.topic_id, OLD.subtopic_id, OLD.difficulty_id, OLD.outcome_ids, OLD.tags, OLD.attributes,
                        OLD.language, OLD.review_hash, OLD.question_id, OLD.version_no, OLD.author_id, OLD.plain_text, OLD.normalized_text, OLD.dup_hash)
                       IS DISTINCT FROM
                       (NEW.type_code, NEW.type_schema_version, NEW.content, NEW.body, NEW.marking, NEW.marks, NEW.negative_marks,
                        NEW.subject_id, NEW.topic_id, NEW.subtopic_id, NEW.difficulty_id, NEW.outcome_ids, NEW.tags, NEW.attributes,
                        NEW.language, NEW.review_hash, NEW.question_id, NEW.version_no, NEW.author_id, NEW.plain_text, NEW.normalized_text, NEW.dup_hash);

    -- approved, superseded and retired versions are immutable: every column except the forward state change, the retirement
    -- record and row bookkeeping is compared (allowlist, so columns added later are protected by default)
    IF OLD.state IN ('APPROVED', 'SUPERSEDED', 'RETIRED') THEN
        IF (to_jsonb(OLD) - ARRAY['state', 'retired_by', 'retired_at', 'version', 'updated_at', 'search_vector'])
               IS DISTINCT FROM (to_jsonb(NEW) - ARRAY['state', 'retired_by', 'retired_at', 'version', 'updated_at', 'search_vector'])
           OR NOT ((OLD.state = NEW.state) OR (OLD.state = 'APPROVED' AND NEW.state IN ('SUPERSEDED', 'RETIRED')) OR (OLD.state = 'SUPERSEDED' AND NEW.state = 'RETIRED')) THEN
            RAISE EXCEPTION 'question version % is % and immutable', OLD.id, OLD.state USING ERRCODE = '23514', CONSTRAINT = 'question_version_immutable';
        END IF;
        RETURN NEW;
    END IF;

    -- in review: content can change only together with a return to DRAFT; that invalidates every review of the version
    IF OLD.state IN ('IN_REVIEW', 'REVIEWED') AND content_changed THEN
        IF NEW.state <> 'DRAFT' THEN
            RAISE EXCEPTION 'reviewed content of % can only change by returning it to draft', OLD.id USING ERRCODE = '23514', CONSTRAINT = 'question_version_review_lock';
        END IF;
    END IF;
    IF OLD.review_hash IS DISTINCT FROM NEW.review_hash OR (OLD.state IN ('IN_REVIEW', 'REVIEWED') AND NEW.state = 'DRAFT') THEN
        UPDATE question_review SET invalidated_at = now(), invalidated_reason = 'content modified after review'
        WHERE version_id = OLD.id AND invalidated_at IS NULL;
    END IF;

    -- approval: policy re-checked at the database (never self-approval; enough valid, current reviews; distinct approver when required)
    IF NEW.state = 'APPROVED' AND OLD.state <> 'APPROVED' THEN
        IF OLD.state <> 'REVIEWED' THEN
            RAISE EXCEPTION 'only a reviewed version can be approved' USING ERRCODE = '23514', CONSTRAINT = 'question_approval_policy';
        END IF;
        IF NEW.approved_by IS NULL OR NEW.approved_by = NEW.author_id THEN
            RAISE EXCEPTION 'the author cannot approve' USING ERRCODE = '23514', CONSTRAINT = 'question_approval_policy';
        END IF;
        SELECT q.bank_id INTO bank FROM question q WHERE q.id = NEW.question_id;
        SELECT * INTO pol FROM question_effective_policy(bank);
        SELECT count(DISTINCT r.reviewer_id), coalesce(bool_or(r.reviewer_id = NEW.approved_by), false)
          INTO valid_reviews, approver_reviewed
          FROM question_review r
         WHERE r.version_id = NEW.id AND r.decision = 'REVIEWED' AND r.invalidated_at IS NULL
           AND r.reviewed_hash = NEW.review_hash AND r.reviewer_id <> NEW.author_id;
        IF valid_reviews < pol.required_reviews THEN
            RAISE EXCEPTION 'approval needs % valid review(s), has %', pol.required_reviews, valid_reviews USING ERRCODE = '23514', CONSTRAINT = 'question_approval_policy';
        END IF;
        IF pol.approver_distinct AND approver_reviewed THEN
            RAISE EXCEPTION 'the approver reviewed this version; this bank requires a different approver' USING ERRCODE = '23514', CONSTRAINT = 'question_approval_policy';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER question_version_guard BEFORE UPDATE OR DELETE ON question_version FOR EACH ROW EXECUTE FUNCTION question_version_guard();

CREATE FUNCTION question_review_guard() RETURNS trigger LANGUAGE plpgsql AS
$$
DECLARE v record;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        IF (OLD.version_id, OLD.reviewer_id, OLD.decision, OLD.reviewed_hash, OLD.checklist, OLD.comment, OLD.created_at)
           IS DISTINCT FROM (NEW.version_id, NEW.reviewer_id, NEW.decision, NEW.reviewed_hash, NEW.checklist, NEW.comment, NEW.created_at)
           OR (OLD.invalidated_at IS NOT NULL AND NEW.invalidated_at IS DISTINCT FROM OLD.invalidated_at) THEN
            RAISE EXCEPTION 'reviews are append-only (only invalidation is recorded)' USING ERRCODE = '23514', CONSTRAINT = 'question_review_append_only';
        END IF;
        RETURN NEW;
    END IF;
    SELECT author_id, state, review_hash INTO v FROM question_version WHERE id = NEW.version_id;
    IF NEW.reviewer_id = v.author_id THEN
        RAISE EXCEPTION 'the author cannot review own question' USING ERRCODE = '23514', CONSTRAINT = 'question_review_not_author';
    END IF;
    IF v.state NOT IN ('IN_REVIEW', 'REVIEWED') THEN
        RAISE EXCEPTION 'only versions in review can be reviewed' USING ERRCODE = '23514', CONSTRAINT = 'question_review_state';
    END IF;
    IF NEW.reviewed_hash <> v.review_hash THEN
        RAISE EXCEPTION 'review does not match the current content' USING ERRCODE = '23514', CONSTRAINT = 'question_review_hash';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER question_review_guard BEFORE INSERT OR UPDATE OR DELETE ON question_review FOR EACH ROW EXECUTE FUNCTION question_review_guard();

-- Answer keys and asset links change only while their version is a draft.
CREATE FUNCTION question_draft_only_guard() RETURNS trigger LANGUAGE plpgsql AS
$$
DECLARE s text;
BEGIN
    SELECT state INTO s FROM question_version WHERE id = CASE WHEN TG_OP = 'DELETE' THEN OLD.version_id ELSE NEW.version_id END;
    IF s IS DISTINCT FROM 'DRAFT' THEN
        RAISE EXCEPTION '% of a % version cannot change', TG_TABLE_NAME, s USING ERRCODE = '23514', CONSTRAINT = 'question_version_immutable';
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END $$;
CREATE TRIGGER question_answer_key_guard BEFORE INSERT OR UPDATE OR DELETE ON question_answer_key FOR EACH ROW EXECUTE FUNCTION question_draft_only_guard();
CREATE TRIGGER question_version_asset_guard BEFORE INSERT OR UPDATE OR DELETE ON question_version_asset FOR EACH ROW EXECUTE FUNCTION question_draft_only_guard();

CREATE FUNCTION question_reference_guard() RETURNS trigger LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NOT EXISTS (SELECT 1 FROM question_version WHERE id = NEW.version_id AND state IN ('APPROVED', 'SUPERSEDED', 'RETIRED')) THEN
            RAISE EXCEPTION 'only approved question versions can be referenced' USING ERRCODE = '23514', CONSTRAINT = 'question_version_reference_state';
        END IF;
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'question version references are permanent' USING ERRCODE = '23514', CONSTRAINT = 'question_version_reference_permanent';
END $$;
CREATE TRIGGER question_reference_guard BEFORE INSERT OR UPDATE OR DELETE ON question_version_reference FOR EACH ROW EXECUTE FUNCTION question_reference_guard();

-- Assets: never deleted while linked to any version (drafts included) or while a referenced version uses them.
CREATE FUNCTION question_asset_guard() RETURNS trigger LANGUAGE plpgsql AS
$$
BEGIN
    IF EXISTS (SELECT 1 FROM question_version_asset a WHERE a.asset_id = OLD.id) THEN
        RAISE EXCEPTION 'asset % is used by a question version', OLD.id USING ERRCODE = '23514', CONSTRAINT = 'question_asset_in_use';
    END IF;
    RETURN OLD;
END $$;
CREATE TRIGGER question_asset_guard BEFORE DELETE ON question_asset FOR EACH ROW EXECUTE FUNCTION question_asset_guard();

CREATE FUNCTION question_transition_append_only() RETURNS trigger LANGUAGE plpgsql AS
$$ BEGIN RAISE EXCEPTION 'question transitions are append-only' USING ERRCODE = '23514', CONSTRAINT = 'question_transition_append_only'; END $$;
CREATE TRIGGER question_transition_append_only BEFORE UPDATE OR DELETE ON question_transition FOR EACH ROW EXECUTE FUNCTION question_transition_append_only();

-- ===================================================================================================== RLS
DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['question_bank', 'question_policy_floor', 'question_subject', 'question_topic', 'learning_outcome', 'difficulty_level',
                             'question_tag', 'question_attribute_definition', 'question', 'question_version', 'question_answer_key', 'question_review',
                             'question_transition', 'question_version_reference', 'question_asset', 'question_version_asset', 'question_asset_quota',
                             'question_import', 'question_import_row'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id())', t);
    END LOOP;
END $$;

-- ===================================================================================================== permissions (P2-07)
-- P2-08: administrators hold read, author, review and bank management (so the 1.3 ceiling still lets them assign FACULTY
-- and ASSESSMENT_DESIGNER), never the privileged question.approve / question.key.read
UPDATE role SET permissions = permissions || ARRAY['question.read', 'question.author', 'question.review', 'question.bank.manage']
    WHERE system_role AND code IN ('ORG_ADMIN', 'INSTITUTION_ADMIN');
UPDATE role SET permissions = permissions || ARRAY['question.read', 'question.review', 'question.approve', 'question.key.read', 'question.bank.manage']
    WHERE system_role AND code = 'EXAM_CONTROLLER';
UPDATE role SET permissions = permissions || ARRAY['question.read', 'question.author', 'question.review']
    WHERE system_role AND code = 'ASSESSMENT_DESIGNER';
UPDATE role SET permissions = permissions || ARRAY['question.read', 'question.author']
    WHERE system_role AND code = 'FACULTY';

-- ===================================================================================================== privileged role assignment (P2-08)
-- Roles containing a privileged permission are assigned only through an APPROVED four-eyes request (type
-- role.privileged_assignment) whose approver currently holds every privileged permission of the role at the scope, or,
-- when nobody holds them yet, by the operator bootstrap (schema owner connection, ticket reference, no existing holder).
-- These rules bind the application role; the schema owner can change the schema anyway (operational control, runbook).
ALTER TABLE role_assignment
    ADD COLUMN approval_request_id uuid,
    ADD COLUMN bootstrap_reference varchar(200);
CREATE UNIQUE INDEX role_assignment_approval_uq ON role_assignment (approval_request_id) WHERE approval_request_id IS NOT NULL;

CREATE FUNCTION privileged_permissions() RETURNS text[] LANGUAGE sql IMMUTABLE AS $$ SELECT ARRAY['question.approve', 'question.key.read'] $$;

-- Live check (no caches): does the ACTIVE user hold the permission through an active assignment of an active role, for
-- the whole organization or at the unit or one of its ancestors? p_unit NULL means organization-wide.
CREATE FUNCTION holds_permission(p_tenant uuid, p_user uuid, p_permission text, p_unit uuid) RETURNS boolean LANGUAGE sql STABLE AS
$$
    SELECT EXISTS (
        SELECT 1 FROM role_assignment ra
        JOIN role r ON r.id = ra.role_id
        JOIN user_account u ON u.id = ra.user_account_id AND u.tenant_id = ra.tenant_id
        WHERE ra.tenant_id = p_tenant AND ra.user_account_id = p_user AND u.status = 'ACTIVE'
          AND ra.revoked_at IS NULL AND ra.valid_from <= now() AND (ra.valid_to IS NULL OR ra.valid_to > now())
          AND r.status = 'ACTIVE' AND r.archived_at IS NULL AND p_permission = ANY (r.permissions)
          AND (ra.scope_type = 'TENANT'
               OR (p_unit IS NOT NULL AND EXISTS (SELECT 1 FROM org_unit su, org_unit tu
                                                  WHERE su.id = ra.scope_id AND tu.id = p_unit AND tu.tenant_id = p_tenant AND tu.path <@ su.path))))
$$;

CREATE FUNCTION role_assignment_privileged_guard() RETURNS trigger LANGUAGE plpgsql AS
$$
DECLARE
    perms text[];
    priv text[];
    req record;
    unit uuid;
    p text;
    app boolean := current_user = '${appRole}';
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF (OLD.user_account_id, OLD.role_id, OLD.scope_type, OLD.scope_id, OLD.approval_request_id, OLD.bootstrap_reference)
           IS DISTINCT FROM (NEW.user_account_id, NEW.role_id, NEW.scope_type, NEW.scope_id, NEW.approval_request_id, NEW.bootstrap_reference) AND app THEN
            RAISE EXCEPTION 'role assignments cannot be re-pointed; revoke and assign' USING ERRCODE = '23514', CONSTRAINT = 'role_assignment_immutable';
        END IF;
        RETURN NEW;
    END IF;
    SELECT r.permissions INTO perms FROM role r WHERE r.id = NEW.role_id;
    priv := ARRAY(SELECT unnest(perms) INTERSECT SELECT unnest(privileged_permissions()));
    unit := CASE WHEN NEW.scope_type = 'ORG_UNIT' THEN NEW.scope_id END;
    IF cardinality(priv) = 0 THEN
        RETURN NEW;
    END IF;
    IF NEW.bootstrap_reference IS NOT NULL THEN
        -- operator bootstrap: owner connection only, and only while nobody holds the privileged permissions at or above the scope
        IF app OR NEW.approval_request_id IS NOT NULL OR length(trim(NEW.bootstrap_reference)) < 3 THEN
            RAISE EXCEPTION 'privileged bootstrap is an operator procedure' USING ERRCODE = '23514', CONSTRAINT = 'role_assignment_privileged';
        END IF;
        FOREACH p IN ARRAY priv LOOP
            IF EXISTS (SELECT 1 FROM user_account u WHERE u.tenant_id = NEW.tenant_id AND holds_permission(NEW.tenant_id, u.id, p, unit)) THEN
                RAISE EXCEPTION 'bootstrap refused: % is already held at or above this scope; use an approval', p
                    USING ERRCODE = '23514', CONSTRAINT = 'role_assignment_privileged';
            END IF;
        END LOOP;
        RETURN NEW;
    END IF;
    IF NEW.approval_request_id IS NULL THEN
        IF app THEN
            RAISE EXCEPTION 'a role with % needs an approved request', array_to_string(priv, ', ') USING ERRCODE = '23514', CONSTRAINT = 'role_assignment_privileged';
        END IF;
        RETURN NEW;   -- schema owner (migrations, tests' fixtures)
    END IF;
    SELECT * INTO req FROM approval_request a WHERE a.id = NEW.approval_request_id AND a.tenant_id = NEW.tenant_id;
    IF req.id IS NULL OR req.type <> 'role.privileged_assignment' OR req.status <> 'APPROVED' OR req.decided_by IS NULL
       OR req.decided_by = req.requested_by OR req.decided_by = NEW.user_account_id OR req.requested_by = NEW.user_account_id
       OR req.payload ->> 'userId' IS DISTINCT FROM NEW.user_account_id::text OR req.payload ->> 'roleId' IS DISTINCT FROM NEW.role_id::text
       OR req.payload ->> 'scopeType' IS DISTINCT FROM NEW.scope_type OR req.payload ->> 'scopeId' IS DISTINCT FROM NEW.scope_id::text THEN
        RAISE EXCEPTION 'the approval does not authorize this assignment' USING ERRCODE = '23514', CONSTRAINT = 'role_assignment_privileged';
    END IF;
    IF NOT holds_permission(NEW.tenant_id, req.requested_by, 'role.assign', unit) THEN
        RAISE EXCEPTION 'the requester no longer holds role.assign at this scope' USING ERRCODE = '23514', CONSTRAINT = 'role_assignment_privileged';
    END IF;
    FOREACH p IN ARRAY priv LOOP
        IF NOT holds_permission(NEW.tenant_id, req.decided_by, p, unit) THEN
            RAISE EXCEPTION 'the approver does not hold % at this scope', p USING ERRCODE = '23514', CONSTRAINT = 'role_assignment_privileged';
        END IF;
    END LOOP;
    RETURN NEW;
END $$;
CREATE TRIGGER role_assignment_privileged_guard BEFORE INSERT OR UPDATE ON role_assignment
    FOR EACH ROW EXECUTE FUNCTION role_assignment_privileged_guard();

-- A role in use cannot gain a privileged permission through the application (that would elevate existing holders
-- without approval); create a new role and assign it through approval instead.
CREATE FUNCTION role_privilege_gain_guard() RETURNS trigger LANGUAGE plpgsql AS
$$
BEGIN
    IF current_user = '${appRole}'
       AND EXISTS (SELECT unnest(NEW.permissions) INTERSECT SELECT unnest(privileged_permissions())
                   EXCEPT SELECT unnest(OLD.permissions))
       AND EXISTS (SELECT 1 FROM role_assignment ra WHERE ra.role_id = NEW.id AND ra.revoked_at IS NULL AND (ra.valid_to IS NULL OR ra.valid_to > now())) THEN
        RAISE EXCEPTION 'a role in use cannot gain a privileged permission' USING ERRCODE = '23514', CONSTRAINT = 'role_privileged_gain';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER role_privilege_gain_guard BEFORE UPDATE OF permissions ON role FOR EACH ROW EXECUTE FUNCTION role_privilege_gain_guard();
