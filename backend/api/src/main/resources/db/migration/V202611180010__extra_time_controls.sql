-- Day 6: extra-time controls of decision R-2 (approved 2026-11-15).
--  * Extra time is granted by designated holders of the new privileged permission exam.attempt.extend (exam controllers
--    and invigilators). Administrators do not hold it automatically; appointing a holder needs a second administrator's
--    approval like every privileged role (P2-08).
--  * Every grant is a permanent row recording the candidate, the examination, the actor, the time, the previous and the
--    new expiry; the row must match the attempt at the moment of the grant (database guard).
--  * Per examination, an institution can require a second person (a different holder of exam.attempt.extend) to
--    approve each grant (high-stakes examinations). The database refuses an unapproved grant on such an examination.
--  Termination and result publication keep their own independent approvals; nothing here changes them.

ALTER TABLE exam_schedule ADD COLUMN extra_time_second_person boolean NOT NULL DEFAULT false;

ALTER TABLE exam_attempt_extension
    ADD COLUMN schedule_id        uuid,
    ADD COLUMN candidate_id       uuid,
    ADD COLUMN previous_deadline  timestamptz,
    ADD COLUMN new_deadline       timestamptz,
    ADD COLUMN approved_by        uuid,
    ADD COLUMN approval_id        uuid;

-- backfill the grants recorded before this migration (synthetic data only so far): the expiry after each grant is the
-- attempt's deadline minus the minutes of the grants that followed it
ALTER TABLE exam_attempt_extension DISABLE TRIGGER exam_attempt_extension_guard;
UPDATE exam_attempt_extension x SET schedule_id = a.schedule_id, candidate_id = a.candidate_id,
       new_deadline = a.deadline_at - make_interval(mins => (SELECT coalesce(sum(l.minutes), 0)::int FROM exam_attempt_extension l
                                                             WHERE l.attempt_id = x.attempt_id AND (l.granted_at, l.id) > (x.granted_at, x.id))),
       previous_deadline = a.deadline_at - make_interval(mins => (SELECT coalesce(sum(l.minutes), 0)::int FROM exam_attempt_extension l
                                                                  WHERE l.attempt_id = x.attempt_id AND (l.granted_at, l.id) >= (x.granted_at, x.id)))
FROM exam_attempt a WHERE a.id = x.attempt_id;
ALTER TABLE exam_attempt_extension ENABLE TRIGGER exam_attempt_extension_guard;

ALTER TABLE exam_attempt_extension
    ALTER COLUMN schedule_id SET NOT NULL,
    ALTER COLUMN candidate_id SET NOT NULL,
    ALTER COLUMN previous_deadline SET NOT NULL,
    ALTER COLUMN new_deadline SET NOT NULL,
    ADD CONSTRAINT exam_attempt_extension_expiry CHECK (new_deadline = previous_deadline + make_interval(mins => minutes)),
    ADD CONSTRAINT exam_attempt_extension_second_person CHECK (approved_by IS NULL OR approved_by <> granted_by),
    ADD CONSTRAINT exam_attempt_extension_approval CHECK ((approved_by IS NULL) = (approval_id IS NULL));
CREATE INDEX exam_attempt_extension_schedule_idx ON exam_attempt_extension (tenant_id, schedule_id, candidate_id);

-- a grant must describe the attempt as it is: same examination and candidate, the current expiry as "previous", the
-- attempt still running; and on an examination that requires a second person, an approval by somebody else
CREATE FUNCTION exam_attempt_extension_insert_guard() RETURNS trigger LANGUAGE plpgsql AS
$$
DECLARE
    a record;
    needs_second boolean;
BEGIN
    SELECT t.schedule_id, t.candidate_id, t.deadline_at, t.status INTO a FROM exam_attempt t WHERE t.id = NEW.attempt_id;
    IF a.schedule_id IS DISTINCT FROM NEW.schedule_id OR a.candidate_id IS DISTINCT FROM NEW.candidate_id
       OR a.deadline_at IS DISTINCT FROM NEW.previous_deadline OR a.status IS DISTINCT FROM 'IN_PROGRESS' THEN
        RAISE EXCEPTION 'an extension must record the running attempt and its current expiry'
            USING ERRCODE = '23514', CONSTRAINT = 'exam_attempt_extension_consistent';
    END IF;
    SELECT s.extra_time_second_person INTO needs_second FROM exam_schedule s WHERE s.id = NEW.schedule_id;
    IF coalesce(needs_second, true) AND NEW.approved_by IS NULL THEN
        RAISE EXCEPTION 'extra time on this examination needs the approval of a second person'
            USING ERRCODE = '23514', CONSTRAINT = 'exam_attempt_extension_second_person';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER exam_attempt_extension_insert_guard BEFORE INSERT ON exam_attempt_extension
    FOR EACH ROW EXECUTE FUNCTION exam_attempt_extension_insert_guard();
REVOKE TRUNCATE ON exam_attempt_extension FROM ${appRole};

-- the policy may be tightened at any time and relaxed only before the examination window opens
CREATE FUNCTION exam_schedule_extra_time_policy_guard() RETURNS trigger LANGUAGE plpgsql AS
$$
BEGIN
    IF OLD.extra_time_second_person AND NOT NEW.extra_time_second_person AND OLD.opens_at <= now() THEN
        RAISE EXCEPTION 'the second-person rule for extra time cannot be relaxed once the examination has opened'
            USING ERRCODE = '23514', CONSTRAINT = 'exam_schedule_extra_time_policy';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER exam_schedule_extra_time_policy_guard BEFORE UPDATE OF extra_time_second_person ON exam_schedule
    FOR EACH ROW EXECUTE FUNCTION exam_schedule_extra_time_policy_guard();

-- the designated permission: exam controllers and invigilators; privileged (no automatic holding by administrators)
UPDATE role SET permissions = permissions || ARRAY['exam.attempt.extend']
    WHERE system_role AND code IN ('EXAM_CONTROLLER', 'EXAM_PROCTOR') AND NOT ('exam.attempt.extend' = ANY (permissions));
CREATE OR REPLACE FUNCTION privileged_permissions() RETURNS text[] LANGUAGE sql IMMUTABLE AS
$$ SELECT ARRAY['question.approve', 'question.key.read', 'assessment.publish', 'exam.results.approve', 'exam.attempt.terminate', 'exam.attempt.extend'] $$;
