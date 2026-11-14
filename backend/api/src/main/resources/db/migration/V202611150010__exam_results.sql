-- M4 result workflow (primary stream, programme Day 3, decision D-10): an authorized person reviews the provisional
-- scores of a schedule; an independent second person approves through the four-eyes framework; approval publishes
-- (releases) exactly the reviewed scores; candidates see only released results. Nobody can approve their own review.

CREATE TABLE exam_result_review (
    id              uuid PRIMARY KEY,
    tenant_id       uuid          NOT NULL REFERENCES tenant (id),
    schedule_id     uuid          NOT NULL,
    session_id      uuid          NOT NULL,                       -- the exam session (institution scope for authorization)
    org_unit_id     uuid,
    reviewer_id     uuid          NOT NULL,
    comment         varchar(2000),
    attempt_ids     uuid[]        NOT NULL,                       -- exactly the attempts whose scores this review covers
    scores_hash     char(64)      NOT NULL,                       -- SHA-256 over (attempt id, score hash), in attempt order
    approval_id     uuid,
    status          varchar(20)   NOT NULL DEFAULT 'PENDING_APPROVAL'
                                  CHECK (status IN ('PENDING_APPROVAL', 'PUBLISHED', 'REJECTED', 'CLOSED')),
    approved_by     uuid,
    decided_at      timestamptz,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    CHECK (cardinality(attempt_ids) > 0),
    CHECK (approved_by IS NULL OR approved_by <> reviewer_id),     -- never approved by its reviewer
    CHECK ((status = 'PUBLISHED') = (approved_by IS NOT NULL)),
    FOREIGN KEY (tenant_id, session_id) REFERENCES readiness_session (tenant_id, id)
);
CREATE INDEX exam_result_review_schedule_idx ON exam_result_review (tenant_id, schedule_id);
-- at most one review awaiting approval per schedule
CREATE UNIQUE INDEX exam_result_review_open_uq ON exam_result_review (tenant_id, schedule_id) WHERE status = 'PENDING_APPROVAL';

-- a review's content never changes; only its decision is recorded once; never deleted
CREATE FUNCTION exam_result_review_guard() RETURNS trigger LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'result reviews are never deleted' USING ERRCODE = '23514', CONSTRAINT = 'exam_result_review_permanent';
    END IF;
    IF (to_jsonb(NEW) - ARRAY['status', 'approved_by', 'decided_at', 'approval_id']) IS DISTINCT FROM
       (to_jsonb(OLD) - ARRAY['status', 'approved_by', 'decided_at', 'approval_id']) THEN
        RAISE EXCEPTION 'a result review never changes' USING ERRCODE = '23514', CONSTRAINT = 'exam_result_review_immutable';
    END IF;
    IF OLD.status <> 'PENDING_APPROVAL' THEN
        RAISE EXCEPTION 'a decided result review never changes' USING ERRCODE = '23514', CONSTRAINT = 'exam_result_review_decided';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER exam_result_review_guard BEFORE UPDATE OR DELETE ON exam_result_review FOR EACH ROW EXECUTE FUNCTION exam_result_review_guard();

-- a score is released only by a published review that covers it
CREATE OR REPLACE FUNCTION exam_score_guard() RETURNS trigger LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NOT EXISTS (SELECT 1 FROM exam_attempt a WHERE a.id = NEW.attempt_id AND a.status IN ('SUBMITTED', 'AUTO_SUBMITTED')) THEN
            RAISE EXCEPTION 'only submitted attempts are scored' USING ERRCODE = '23514', CONSTRAINT = 'exam_score_submitted';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.status = 'PROVISIONAL' AND NEW.status = 'RELEASED'
       AND (to_jsonb(NEW) - 'status') = (to_jsonb(OLD) - 'status')
       AND EXISTS (SELECT 1 FROM exam_result_review r WHERE r.status = 'PUBLISHED' AND NEW.attempt_id = ANY (r.attempt_ids)) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'scores never change (release needs a published, independently approved review)' USING ERRCODE = '23514', CONSTRAINT = 'exam_score_immutable';
END $$;

ALTER TABLE exam_result_review ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON exam_result_review USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());
REVOKE DELETE ON exam_result_review FROM ${appRole};

UPDATE role SET permissions = permissions || ARRAY['exam.results.read']
    WHERE system_role AND code IN ('ORG_ADMIN', 'INSTITUTION_ADMIN');
UPDATE role SET permissions = permissions || ARRAY['exam.results.read', 'exam.results.review', 'exam.results.approve']
    WHERE system_role AND code = 'EXAM_CONTROLLER';
