-- Day 5: examination operations.
--  * Extension of a running attempt by an authorized controller (exam.schedule.manage): append-only record with a reason;
--    the attempt's deadline may move forward only by exactly the recorded extensions (database guard), never back.
--  * Termination of a running attempt: requested by an invigilator (exam.invigilate), decided by a different person holding
--    exam.attempt.terminate (privileged, four-eyes); the attempt ends with status TERMINATED; its answers are kept.

ALTER TABLE exam_attempt DROP CONSTRAINT exam_attempt_status_check;
ALTER TABLE exam_attempt ADD CONSTRAINT exam_attempt_status_check CHECK (status IN ('IN_PROGRESS', 'SUBMITTED', 'AUTO_SUBMITTED', 'TERMINATED'));
ALTER TABLE exam_attempt ADD COLUMN extended_minutes integer NOT NULL DEFAULT 0 CHECK (extended_minutes BETWEEN 0 AND 600);

CREATE TABLE exam_attempt_extension (
    id          uuid PRIMARY KEY,
    tenant_id   uuid          NOT NULL REFERENCES tenant (id),
    attempt_id  uuid          NOT NULL,
    minutes     integer       NOT NULL CHECK (minutes BETWEEN 1 AND 60),
    reason      varchar(500)  NOT NULL CHECK (length(btrim(reason)) > 0),
    granted_by  uuid          NOT NULL,
    granted_at  timestamptz   NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, attempt_id) REFERENCES exam_attempt (tenant_id, id)
);
CREATE INDEX exam_attempt_extension_attempt_idx ON exam_attempt_extension (tenant_id, attempt_id);

CREATE TABLE exam_attempt_termination (
    id            uuid PRIMARY KEY,
    tenant_id     uuid          NOT NULL REFERENCES tenant (id),
    attempt_id    uuid          NOT NULL,
    reason        varchar(1000) NOT NULL CHECK (length(btrim(reason)) > 0),
    requested_by  uuid          NOT NULL,
    approval_id   uuid,
    status        varchar(20)   NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CLOSED')),
    decided_by    uuid,
    decided_at    timestamptz,
    created_at    timestamptz   NOT NULL DEFAULT now(),
    CHECK (decided_by IS NULL OR decided_by <> requested_by),          -- four-eyes, also against a direct write
    CHECK (status <> 'APPROVED' OR decided_by IS NOT NULL),
    FOREIGN KEY (tenant_id, attempt_id) REFERENCES exam_attempt (tenant_id, id)
);
CREATE UNIQUE INDEX exam_attempt_termination_pending_uq ON exam_attempt_termination (tenant_id, attempt_id) WHERE status = 'PENDING';

-- extensions are append-only; termination requests change only by their decision
CREATE FUNCTION exam_operations_append_only() RETURNS trigger LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'extensions are permanent records' USING ERRCODE = '23514', CONSTRAINT = 'exam_attempt_extension_permanent';
END $$;
CREATE TRIGGER exam_attempt_extension_guard BEFORE UPDATE OR DELETE ON exam_attempt_extension FOR EACH ROW EXECUTE FUNCTION exam_operations_append_only();

CREATE FUNCTION exam_attempt_termination_guard() RETURNS trigger LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'termination requests are never deleted' USING ERRCODE = '23514', CONSTRAINT = 'exam_attempt_termination_permanent';
    END IF;
    IF OLD.status <> 'PENDING' OR (NEW.attempt_id, NEW.reason, NEW.requested_by, NEW.tenant_id, NEW.created_at)
                                   IS DISTINCT FROM (OLD.attempt_id, OLD.reason, OLD.requested_by, OLD.tenant_id, OLD.created_at) THEN
        RAISE EXCEPTION 'a termination request changes only by its decision' USING ERRCODE = '23514', CONSTRAINT = 'exam_attempt_termination_immutable';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER exam_attempt_termination_guard BEFORE UPDATE OR DELETE ON exam_attempt_termination FOR EACH ROW EXECUTE FUNCTION exam_attempt_termination_guard();

-- the attempt guard: as before, plus the one permitted deadline change (forward by the newly recorded extension minutes,
-- only while the attempt is open)
CREATE OR REPLACE FUNCTION exam_attempt_guard() RETURNS trigger LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'attempts are never deleted' USING ERRCODE = '23514', CONSTRAINT = 'exam_attempt_permanent';
    END IF;
    IF (to_jsonb(NEW) - ARRAY['status', 'submitted_at', 'submit_key', 'answers_saved', 'version', 'deadline_at', 'extended_minutes'])
       IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['status', 'submitted_at', 'submit_key', 'answers_saved', 'version', 'deadline_at', 'extended_minutes']) THEN
        RAISE EXCEPTION 'an attempt''s binding and deadline never change' USING ERRCODE = '23514', CONSTRAINT = 'exam_attempt_immutable';
    END IF;
    IF (NEW.deadline_at, NEW.extended_minutes) IS DISTINCT FROM (OLD.deadline_at, OLD.extended_minutes) THEN
        IF OLD.status <> 'IN_PROGRESS' OR NEW.extended_minutes <= OLD.extended_minutes
           OR NEW.deadline_at <> OLD.deadline_at + make_interval(mins => NEW.extended_minutes - OLD.extended_minutes)
           OR NEW.extended_minutes <> (SELECT coalesce(sum(minutes), 0) FROM exam_attempt_extension x WHERE x.attempt_id = NEW.id) THEN
            RAISE EXCEPTION 'an attempt''s deadline moves only forward, by recorded extensions' USING ERRCODE = '23514', CONSTRAINT = 'exam_attempt_immutable';
        END IF;
    END IF;
    IF OLD.status <> 'IN_PROGRESS' AND (NEW.status, NEW.submitted_at, NEW.submit_key, NEW.answers_saved)
                                      IS DISTINCT FROM (OLD.status, OLD.submitted_at, OLD.submit_key, OLD.answers_saved) THEN
        RAISE EXCEPTION 'a submitted attempt never changes' USING ERRCODE = '23514', CONSTRAINT = 'exam_attempt_submitted';
    END IF;
    RETURN NEW;
END $$;

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['exam_attempt_extension', 'exam_attempt_termination'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id())', t);
    END LOOP;
END $$;
REVOKE UPDATE, DELETE ON exam_attempt_extension FROM ${appRole};
REVOKE DELETE ON exam_attempt_termination FROM ${appRole};

UPDATE role SET permissions = permissions || ARRAY['exam.attempt.terminate']
    WHERE system_role AND code = 'EXAM_CONTROLLER' AND NOT ('exam.attempt.terminate' = ANY (permissions));
CREATE OR REPLACE FUNCTION privileged_permissions() RETURNS text[] LANGUAGE sql IMMUTABLE AS
$$ SELECT ARRAY['question.approve', 'question.key.read', 'assessment.publish', 'exam.results.approve', 'exam.attempt.terminate'] $$;

-- a terminated attempt is scored like a submitted one (its result still needs review and independent approval, R-1)
CREATE OR REPLACE FUNCTION exam_score_guard() RETURNS trigger LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NOT EXISTS (SELECT 1 FROM exam_attempt a WHERE a.id = NEW.attempt_id AND a.status IN ('SUBMITTED', 'AUTO_SUBMITTED', 'TERMINATED')) THEN
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
