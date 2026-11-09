-- Increment 2.2 core (programme Day 1): assessment authoring and publication.
-- Published versions are immutable; items and pools reference immutable question-version ids; papers (question order,
-- option order, encrypted seed, hash) are stored and never changed; publication needs an independent publisher
-- (Q-2.2-1, Q-2.2-2). All tables under RLS. New tables only, plus permission grants and the privileged set (Q-2.2-1).

CREATE TABLE assessment (
    id                    uuid PRIMARY KEY,
    tenant_id             uuid          NOT NULL REFERENCES tenant (id),
    org_unit_id           uuid,                                     -- institution (NULL = organization-wide)
    code                  varchar(40)   NOT NULL CHECK (code ~ '^[A-Za-z0-9][A-Za-z0-9._-]{1,39}$'),
    purpose               varchar(20)   NOT NULL CHECK (purpose IN ('ADMISSION', 'INTERNAL', 'SEMESTER', 'PLACEMENT', 'APTITUDE', 'PRACTICE')),
    status                varchar(20)   NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    current_version_id    uuid,
    published_version_id  uuid,
    created_by            uuid          NOT NULL,
    created_at            timestamptz   NOT NULL DEFAULT now(),
    updated_at            timestamptz   NOT NULL DEFAULT now(),
    version               bigint        NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, org_unit_id) REFERENCES org_unit (tenant_id, id)
);
CREATE UNIQUE INDEX assessment_code_uq ON assessment (tenant_id, lower(code));

CREATE TABLE assessment_version (
    id                    uuid PRIMARY KEY,
    tenant_id             uuid          NOT NULL REFERENCES tenant (id),
    assessment_id         uuid          NOT NULL,
    version_no            integer       NOT NULL CHECK (version_no >= 1),
    state                 varchar(20)   NOT NULL DEFAULT 'DRAFT'
                          CHECK (state IN ('DRAFT', 'IN_REVIEW', 'APPROVED', 'PUBLISHED', 'SUPERSEDED', 'WITHDRAWN')),
    governance            varchar(20)   NOT NULL CHECK (governance IN ('STANDARD', 'HIGH_STAKES')),
    title                 varchar(200)  NOT NULL CHECK (length(btrim(title)) > 0),
    description           varchar(2000),
    instructions          varchar(10000),
    duration_minutes      integer       NOT NULL CHECK (duration_minutes BETWEEN 1 AND 600),
    declared_total_marks  numeric(9,2)  CHECK (declared_total_marks IS NULL OR declared_total_marks > 0),
    total_marks           numeric(9,2)  NOT NULL DEFAULT 0,
    negative_marking      varchar(20)   NOT NULL DEFAULT 'QUESTION' CHECK (negative_marking IN ('OFF', 'QUESTION', 'CUSTOM')),
    shuffle_questions     boolean       NOT NULL DEFAULT false,
    shuffle_options       boolean       NOT NULL DEFAULT true,
    review_hash           char(64)      NOT NULL,
    author_id             uuid          NOT NULL,
    created_at            timestamptz   NOT NULL DEFAULT now(),
    updated_at            timestamptz   NOT NULL DEFAULT now(),
    submitted_at          timestamptz,
    first_submitted_at    timestamptz,
    published_by          uuid,
    published_at          timestamptz,
    superseded_at         timestamptz,
    version               bigint        NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    UNIQUE (assessment_id, version_no),
    FOREIGN KEY (tenant_id, assessment_id) REFERENCES assessment (tenant_id, id),
    CONSTRAINT assessment_version_no_self_publication CHECK (published_by IS NULL OR published_by <> author_id),
    CONSTRAINT assessment_version_publication_recorded CHECK ((state IN ('PUBLISHED', 'SUPERSEDED', 'WITHDRAWN')) = (published_by IS NOT NULL))
);
CREATE UNIQUE INDEX assessment_version_open_uq ON assessment_version (assessment_id) WHERE state IN ('DRAFT', 'IN_REVIEW', 'APPROVED');
CREATE INDEX assessment_version_state_idx ON assessment_version (tenant_id, state);

-- fixed questions: an immutable approved question version each
CREATE TABLE assessment_item (
    id                    uuid PRIMARY KEY,
    tenant_id             uuid          NOT NULL REFERENCES tenant (id),
    version_id            uuid          NOT NULL,
    section_no            smallint      NOT NULL DEFAULT 1 CHECK (section_no BETWEEN 1 AND 20),
    ordinal               integer       NOT NULL,
    question_version_id   uuid          NOT NULL,
    marks                 numeric(7,2)  NOT NULL CHECK (marks > 0 AND marks <= 1000),
    negative_marks        numeric(7,2)  NOT NULL DEFAULT 0 CHECK (negative_marks >= 0 AND negative_marks <= marks),
    UNIQUE (tenant_id, id),
    UNIQUE (version_id, question_version_id),
    FOREIGN KEY (tenant_id, version_id) REFERENCES assessment_version (tenant_id, id),
    FOREIGN KEY (tenant_id, question_version_id) REFERENCES question_version (tenant_id, id)
);
CREATE INDEX assessment_item_version_idx ON assessment_item (tenant_id, version_id);

-- random selection rules (one bank, filters, count, marks); the pool is frozen at publication
CREATE TABLE assessment_rule (
    id                    uuid PRIMARY KEY,
    tenant_id             uuid          NOT NULL REFERENCES tenant (id),
    version_id            uuid          NOT NULL,
    section_no            smallint      NOT NULL DEFAULT 1 CHECK (section_no BETWEEN 1 AND 20),
    ordinal               integer       NOT NULL,
    label                 varchar(150),
    bank_id               uuid          NOT NULL,
    subject_id            uuid,
    topic_id              uuid,
    difficulty_id         uuid,
    question_type         varchar(40),
    draw_count            integer       NOT NULL CHECK (draw_count BETWEEN 1 AND 500),
    marks                 numeric(7,2)  NOT NULL CHECK (marks > 0 AND marks <= 1000),
    negative_marks        numeric(7,2)  NOT NULL DEFAULT 0 CHECK (negative_marks >= 0 AND negative_marks <= marks),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, version_id) REFERENCES assessment_version (tenant_id, id),
    FOREIGN KEY (tenant_id, bank_id) REFERENCES question_bank (tenant_id, id)
);
CREATE INDEX assessment_rule_version_idx ON assessment_rule (tenant_id, version_id);

CREATE TABLE assessment_rule_pool (
    tenant_id             uuid          NOT NULL REFERENCES tenant (id),
    rule_id               uuid          NOT NULL,
    question_version_id   uuid          NOT NULL,
    PRIMARY KEY (rule_id, question_version_id),
    FOREIGN KEY (tenant_id, rule_id) REFERENCES assessment_rule (tenant_id, id),
    FOREIGN KEY (tenant_id, question_version_id) REFERENCES question_version (tenant_id, id)
);

CREATE TABLE assessment_review (
    id                    uuid PRIMARY KEY,
    tenant_id             uuid          NOT NULL REFERENCES tenant (id),
    version_id            uuid          NOT NULL,
    reviewer_id           uuid          NOT NULL,
    decision              varchar(20)   NOT NULL CHECK (decision IN ('REVIEWED', 'CHANGES_REQUESTED')),
    reviewed_hash         char(64)      NOT NULL,
    comment               varchar(2000),
    created_at            timestamptz   NOT NULL DEFAULT now(),
    invalidated_at        timestamptz,
    invalidated_reason    varchar(200),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, version_id) REFERENCES assessment_version (tenant_id, id)
);
CREATE INDEX assessment_review_version_idx ON assessment_review (tenant_id, version_id);

CREATE TABLE assessment_transition (
    id                    uuid PRIMARY KEY,
    tenant_id             uuid          NOT NULL REFERENCES tenant (id),
    version_id            uuid          NOT NULL,
    from_state            varchar(20),
    to_state              varchar(20)   NOT NULL,
    actor_id              uuid,
    reason                varchar(500),
    at                    timestamptz   NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, version_id) REFERENCES assessment_version (tenant_id, id)
);
CREATE INDEX assessment_transition_version_idx ON assessment_transition (tenant_id, version_id, at);

-- generated papers: the exact questions, marks and option order, the (encrypted) seed and a hash; never changed
CREATE TABLE assessment_paper (
    id                    uuid PRIMARY KEY,
    tenant_id             uuid          NOT NULL REFERENCES tenant (id),
    version_id            uuid          NOT NULL,
    purpose               varchar(20)   NOT NULL CHECK (purpose IN ('PREVIEW', 'ATTEMPT')),
    attempt_ref           varchar(100),
    seed_enc              text          NOT NULL,
    generator_version     smallint      NOT NULL,
    items                 jsonb         NOT NULL,
    total_marks           numeric(9,2)  NOT NULL,
    paper_hash            char(64)      NOT NULL,
    created_by            uuid,
    created_at            timestamptz   NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, version_id) REFERENCES assessment_version (tenant_id, id),
    CONSTRAINT assessment_paper_attempt_ref CHECK ((purpose = 'ATTEMPT') = (attempt_ref IS NOT NULL))
);
CREATE UNIQUE INDEX assessment_paper_attempt_uq ON assessment_paper (tenant_id, attempt_ref) WHERE attempt_ref IS NOT NULL;
CREATE INDEX assessment_paper_version_idx ON assessment_paper (tenant_id, version_id);

-- permanent references to published versions (examination sessions, attempts, results)
CREATE TABLE assessment_version_reference (
    id                    uuid PRIMARY KEY,
    tenant_id             uuid          NOT NULL REFERENCES tenant (id),
    version_id            uuid          NOT NULL,
    referrer_type         varchar(40)   NOT NULL,
    referrer_id           varchar(64)   NOT NULL,
    created_at            timestamptz   NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, version_id, referrer_type, referrer_id),
    FOREIGN KEY (tenant_id, version_id) REFERENCES assessment_version (tenant_id, id)
);

-- ===================================================================================================== guards
-- purpose and institution of an assessment never change
CREATE FUNCTION assessment_identity_guard() RETURNS trigger LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'assessments are archived, never deleted' USING ERRCODE = '23514', CONSTRAINT = 'assessment_permanent';
    END IF;
    IF (OLD.purpose, OLD.org_unit_id, OLD.code, OLD.tenant_id) IS DISTINCT FROM (NEW.purpose, NEW.org_unit_id, NEW.code, NEW.tenant_id) THEN
        RAISE EXCEPTION 'purpose, institution and code of an assessment cannot change' USING ERRCODE = '23514', CONSTRAINT = 'assessment_identity';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER assessment_identity_guard BEFORE UPDATE OR DELETE ON assessment FOR EACH ROW EXECUTE FUNCTION assessment_identity_guard();

-- governance follows the purpose (Q-2.2-2): ADMISSION, SEMESTER, PLACEMENT, APTITUDE are high-stakes (no relaxation yet)
CREATE FUNCTION assessment_required_governance(p_purpose text) RETURNS text LANGUAGE sql IMMUTABLE AS
$$ SELECT CASE WHEN p_purpose IN ('ADMISSION', 'SEMESTER', 'PLACEMENT', 'APTITUDE') THEN 'HIGH_STAKES' ELSE 'STANDARD' END $$;

CREATE FUNCTION assessment_version_guard() RETURNS trigger LANGUAGE plpgsql AS
$$
DECLARE
    a record;
    valid_reviews int;
    publisher_reviewed boolean;
    content_changed boolean;
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.state <> 'DRAFT' OR OLD.first_submitted_at IS NOT NULL OR EXISTS (SELECT 1 FROM assessment_version_reference r WHERE r.version_id = OLD.id) THEN
            RAISE EXCEPTION 'assessment version % cannot be deleted', OLD.id USING ERRCODE = '23514', CONSTRAINT = 'assessment_version_immutable';
        END IF;
        RETURN OLD;
    END IF;
    SELECT * INTO a FROM assessment WHERE id = NEW.assessment_id;
    IF NEW.governance = 'STANDARD' AND assessment_required_governance(a.purpose) = 'HIGH_STAKES' THEN
        RAISE EXCEPTION '% assessments need high-stakes governance', a.purpose USING ERRCODE = '23514', CONSTRAINT = 'assessment_governance';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.state <> 'DRAFT' THEN
            RAISE EXCEPTION 'new assessment versions start as drafts' USING ERRCODE = '23514', CONSTRAINT = 'assessment_version_immutable';
        END IF;
        RETURN NEW;
    END IF;

    -- published, superseded and withdrawn versions: every column except the forward state change and bookkeeping
    IF OLD.state IN ('PUBLISHED', 'SUPERSEDED', 'WITHDRAWN') THEN
        IF (to_jsonb(OLD) - ARRAY['state', 'superseded_at', 'version', 'updated_at']) IS DISTINCT FROM (to_jsonb(NEW) - ARRAY['state', 'superseded_at', 'version', 'updated_at'])
           OR NOT ((OLD.state = NEW.state) OR (OLD.state = 'PUBLISHED' AND NEW.state IN ('SUPERSEDED', 'WITHDRAWN')) OR (OLD.state = 'SUPERSEDED' AND NEW.state = 'WITHDRAWN')) THEN
            RAISE EXCEPTION 'assessment version % is % and immutable', OLD.id, OLD.state USING ERRCODE = '23514', CONSTRAINT = 'assessment_version_immutable';
        END IF;
        RETURN NEW;
    END IF;

    content_changed := (OLD.review_hash, OLD.governance, OLD.assessment_id, OLD.version_no, OLD.author_id)
                       IS DISTINCT FROM (NEW.review_hash, NEW.governance, NEW.assessment_id, NEW.version_no, NEW.author_id);
    IF OLD.state IN ('IN_REVIEW', 'APPROVED') AND content_changed AND NEW.state <> 'DRAFT' THEN
        RAISE EXCEPTION 'reviewed content of % can only change by returning it to draft', OLD.id USING ERRCODE = '23514', CONSTRAINT = 'assessment_version_review_lock';
    END IF;
    IF OLD.review_hash IS DISTINCT FROM NEW.review_hash OR (OLD.state IN ('IN_REVIEW', 'APPROVED') AND NEW.state = 'DRAFT') THEN
        UPDATE assessment_review SET invalidated_at = now(), invalidated_reason = 'content modified after review'
        WHERE version_id = OLD.id AND invalidated_at IS NULL;
    END IF;

    -- publication: an independent publisher holding assessment.publish at the institution; enough valid reviews;
    -- under high-stakes governance the publisher did not review (three different people)
    IF NEW.state = 'PUBLISHED' THEN
        IF OLD.state NOT IN ('IN_REVIEW', 'APPROVED') THEN
            RAISE EXCEPTION 'only a version in review can be published' USING ERRCODE = '23514', CONSTRAINT = 'assessment_publication_policy';
        END IF;
        IF NEW.published_by IS NULL OR NEW.published_by = NEW.author_id THEN
            RAISE EXCEPTION 'the author cannot publish' USING ERRCODE = '23514', CONSTRAINT = 'assessment_publication_policy';
        END IF;
        IF NOT holds_permission(NEW.tenant_id, NEW.published_by, 'assessment.publish', a.org_unit_id) THEN
            RAISE EXCEPTION 'the publisher does not hold assessment.publish at this institution' USING ERRCODE = '23514', CONSTRAINT = 'assessment_publication_policy';
        END IF;
        SELECT count(DISTINCT r.reviewer_id), coalesce(bool_or(r.reviewer_id = NEW.published_by), false)
          INTO valid_reviews, publisher_reviewed
          FROM assessment_review r
         WHERE r.version_id = NEW.id AND r.decision = 'REVIEWED' AND r.invalidated_at IS NULL
           AND r.reviewed_hash = NEW.review_hash AND r.reviewer_id <> NEW.author_id;
        IF valid_reviews < 1 THEN
            RAISE EXCEPTION 'publication needs a valid independent review' USING ERRCODE = '23514', CONSTRAINT = 'assessment_publication_policy';
        END IF;
        IF NEW.governance = 'HIGH_STAKES' AND publisher_reviewed THEN
            RAISE EXCEPTION 'high-stakes assessments need a publisher who did not review them' USING ERRCODE = '23514', CONSTRAINT = 'assessment_publication_policy';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER assessment_version_guard BEFORE INSERT OR UPDATE OR DELETE ON assessment_version FOR EACH ROW EXECUTE FUNCTION assessment_version_guard();

CREATE FUNCTION assessment_review_guard() RETURNS trigger LANGUAGE plpgsql AS
$$
DECLARE v record;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'assessment reviews are permanent' USING ERRCODE = '23514', CONSTRAINT = 'assessment_review_append_only';
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF (to_jsonb(OLD) - ARRAY['invalidated_at', 'invalidated_reason']) IS DISTINCT FROM (to_jsonb(NEW) - ARRAY['invalidated_at', 'invalidated_reason'])
           OR OLD.invalidated_at IS NOT NULL THEN
            RAISE EXCEPTION 'assessment reviews are append-only (only invalidation is recorded)' USING ERRCODE = '23514', CONSTRAINT = 'assessment_review_append_only';
        END IF;
        RETURN NEW;
    END IF;
    SELECT * INTO v FROM assessment_version WHERE id = NEW.version_id;
    IF NEW.reviewer_id = v.author_id THEN
        RAISE EXCEPTION 'the author cannot review own assessment' USING ERRCODE = '23514', CONSTRAINT = 'assessment_review_not_author';
    END IF;
    IF v.state <> 'IN_REVIEW' THEN
        RAISE EXCEPTION 'only versions in review can be reviewed' USING ERRCODE = '23514', CONSTRAINT = 'assessment_review_state';
    END IF;
    IF NEW.reviewed_hash <> v.review_hash THEN
        RAISE EXCEPTION 'review does not match the current content' USING ERRCODE = '23514', CONSTRAINT = 'assessment_review_hash';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER assessment_review_guard BEFORE INSERT OR UPDATE OR DELETE ON assessment_review FOR EACH ROW EXECUTE FUNCTION assessment_review_guard();

-- items and rules change only while their version is a draft; items must be approved question versions of the tenant
CREATE FUNCTION assessment_draft_only_guard() RETURNS trigger LANGUAGE plpgsql AS
$$
DECLARE s text;
BEGIN
    SELECT state INTO s FROM assessment_version WHERE id = CASE WHEN TG_OP = 'DELETE' THEN OLD.version_id ELSE NEW.version_id END;
    IF s IS DISTINCT FROM 'DRAFT' THEN
        RAISE EXCEPTION '% of a % assessment version cannot change', TG_TABLE_NAME, s USING ERRCODE = '23514', CONSTRAINT = 'assessment_version_immutable';
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END $$;
-- fixed questions are approved versions of the same organization (checked separately: assessment_rule has no such column)
CREATE FUNCTION assessment_item_approved_guard() RETURNS trigger LANGUAGE plpgsql AS
$$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM question_version qv WHERE qv.id = NEW.question_version_id AND qv.tenant_id = NEW.tenant_id AND qv.state = 'APPROVED') THEN
        RAISE EXCEPTION 'only approved question versions can be placed in an assessment' USING ERRCODE = '23514', CONSTRAINT = 'assessment_item_approved';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER assessment_item_guard BEFORE INSERT OR UPDATE OR DELETE ON assessment_item FOR EACH ROW EXECUTE FUNCTION assessment_draft_only_guard();
CREATE TRIGGER assessment_item_approved_guard BEFORE INSERT OR UPDATE ON assessment_item FOR EACH ROW EXECUTE FUNCTION assessment_item_approved_guard();
CREATE TRIGGER assessment_rule_guard BEFORE INSERT OR UPDATE OR DELETE ON assessment_rule FOR EACH ROW EXECUTE FUNCTION assessment_draft_only_guard();

-- pools are written once, during publication (version still in review), from approved versions; never changed afterwards
CREATE FUNCTION assessment_rule_pool_guard() RETURNS trigger LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'frozen pools never change' USING ERRCODE = '23514', CONSTRAINT = 'assessment_pool_frozen';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM assessment_rule r JOIN assessment_version v ON v.id = r.version_id WHERE r.id = NEW.rule_id AND v.state IN ('IN_REVIEW', 'APPROVED'))
       OR NOT EXISTS (SELECT 1 FROM question_version qv WHERE qv.id = NEW.question_version_id AND qv.tenant_id = NEW.tenant_id AND qv.state = 'APPROVED') THEN
        RAISE EXCEPTION 'pools are frozen from approved questions at publication only' USING ERRCODE = '23514', CONSTRAINT = 'assessment_pool_frozen';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER assessment_rule_pool_guard BEFORE INSERT OR UPDATE OR DELETE ON assessment_rule_pool FOR EACH ROW EXECUTE FUNCTION assessment_rule_pool_guard();

-- papers: attempt papers only from published versions; never changed or deleted
CREATE FUNCTION assessment_paper_guard() RETURNS trigger LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'generated papers never change' USING ERRCODE = '23514', CONSTRAINT = 'assessment_paper_immutable';
    END IF;
    IF NEW.purpose = 'ATTEMPT' AND NOT EXISTS (SELECT 1 FROM assessment_version v WHERE v.id = NEW.version_id AND v.state = 'PUBLISHED') THEN
        RAISE EXCEPTION 'attempt papers come only from published versions' USING ERRCODE = '23514', CONSTRAINT = 'assessment_paper_immutable';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER assessment_paper_guard BEFORE INSERT OR UPDATE OR DELETE ON assessment_paper FOR EACH ROW EXECUTE FUNCTION assessment_paper_guard();

CREATE FUNCTION assessment_reference_guard() RETURNS trigger LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NOT EXISTS (SELECT 1 FROM assessment_version WHERE id = NEW.version_id AND state IN ('PUBLISHED', 'SUPERSEDED', 'WITHDRAWN')) THEN
            RAISE EXCEPTION 'only published assessment versions can be referenced' USING ERRCODE = '23514', CONSTRAINT = 'assessment_reference_state';
        END IF;
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'assessment version references are permanent' USING ERRCODE = '23514', CONSTRAINT = 'assessment_reference_permanent';
END $$;
CREATE TRIGGER assessment_reference_guard BEFORE INSERT OR UPDATE OR DELETE ON assessment_version_reference FOR EACH ROW EXECUTE FUNCTION assessment_reference_guard();

CREATE FUNCTION assessment_transition_append_only() RETURNS trigger LANGUAGE plpgsql AS
$$ BEGIN RAISE EXCEPTION 'assessment transitions are append-only' USING ERRCODE = '23514', CONSTRAINT = 'assessment_transition_append_only'; END $$;
CREATE TRIGGER assessment_transition_append_only BEFORE UPDATE OR DELETE ON assessment_transition FOR EACH ROW EXECUTE FUNCTION assessment_transition_append_only();

-- ===================================================================================================== RLS
DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['assessment', 'assessment_version', 'assessment_item', 'assessment_rule', 'assessment_rule_pool', 'assessment_review',
                             'assessment_transition', 'assessment_paper', 'assessment_version_reference'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id())', t);
    END LOOP;
END $$;

-- ===================================================================================================== permissions (Q-2.2-1, Q-2.2-6)
-- administrators hold every non-privileged assessment permission (1.3 ceiling); assessment.publish is privileged (P2-08)
UPDATE role SET permissions = permissions || ARRAY['assessment.read', 'assessment.author', 'assessment.author.internal', 'assessment.review']
    WHERE system_role AND code IN ('ORG_ADMIN', 'INSTITUTION_ADMIN');
UPDATE role SET permissions = permissions || ARRAY['assessment.read', 'assessment.review', 'assessment.publish']
    WHERE system_role AND code = 'EXAM_CONTROLLER';
UPDATE role SET permissions = permissions || ARRAY['assessment.read', 'assessment.author', 'assessment.review']
    WHERE system_role AND code = 'ASSESSMENT_DESIGNER';
UPDATE role SET permissions = permissions || ARRAY['assessment.read', 'assessment.author.internal']
    WHERE system_role AND code = 'FACULTY';

CREATE OR REPLACE FUNCTION privileged_permissions() RETURNS text[] LANGUAGE sql IMMUTABLE AS
$$ SELECT ARRAY['question.approve', 'question.key.read', 'assessment.publish'] $$;
