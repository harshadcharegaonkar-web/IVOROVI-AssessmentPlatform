-- Day 6, QA-D6-1 (found by Session 3's adversarial tests): the database did not tie a termination to its four-eyes
-- approval. As the application role a pending termination request could be set to APPROVED without any approval, and an
-- attempt could be set to TERMINATED with no approved request. The result path already had the equivalent guard.
-- Now, as for results:
--  * a termination request becomes APPROVED only with an APPROVED approval request of type exam.attempt.terminate for
--    this very request, decided by the recorded decider, who is not the requester; its approval link never changes;
--  * an attempt becomes TERMINATED only while an APPROVED termination request for it exists;
--  * the same binding for second-person extra time (R-2): a grant that names an approval must match an APPROVED
--    exam.attempt.extend request for this attempt, granted by its requester and approved by its decider.
-- The framework marks the approval request APPROVED before it calls the handler, in the same transaction.

CREATE OR REPLACE FUNCTION exam_attempt_termination_guard() RETURNS trigger LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'termination requests are never deleted' USING ERRCODE = '23514', CONSTRAINT = 'exam_attempt_termination_permanent';
    END IF;
    IF OLD.status <> 'PENDING' OR (NEW.attempt_id, NEW.reason, NEW.requested_by, NEW.tenant_id, NEW.created_at)
                                   IS DISTINCT FROM (OLD.attempt_id, OLD.reason, OLD.requested_by, OLD.tenant_id, OLD.created_at) THEN
        RAISE EXCEPTION 'a termination request changes only by its decision' USING ERRCODE = '23514', CONSTRAINT = 'exam_attempt_termination_immutable';
    END IF;
    IF OLD.approval_id IS NOT NULL AND NEW.approval_id IS DISTINCT FROM OLD.approval_id THEN
        RAISE EXCEPTION 'a termination request keeps its approval' USING ERRCODE = '23514', CONSTRAINT = 'exam_attempt_termination_immutable';
    END IF;
    IF NEW.status = 'APPROVED' AND NOT EXISTS (
            SELECT 1 FROM approval_request r
            WHERE r.id = NEW.approval_id AND r.tenant_id = NEW.tenant_id AND r.type = 'exam.attempt.terminate' AND r.status = 'APPROVED'
              AND r.subject_id = NEW.id::text AND r.requested_by = NEW.requested_by AND r.decided_by = NEW.decided_by
              AND r.decided_by <> r.requested_by) THEN
        RAISE EXCEPTION 'a termination is approved only through its four-eyes approval'
            USING ERRCODE = '23514', CONSTRAINT = 'exam_attempt_termination_approved';
    END IF;
    RETURN NEW;
END $$;

-- the attempt guard of V202611170020, plus: TERMINATED only with an approved termination request
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
    IF NEW.status = 'TERMINATED' AND OLD.status <> 'TERMINATED' AND NOT EXISTS (
            SELECT 1 FROM exam_attempt_termination t WHERE t.attempt_id = NEW.id AND t.status = 'APPROVED') THEN
        RAISE EXCEPTION 'an attempt is terminated only through an approved termination request'
            USING ERRCODE = '23514', CONSTRAINT = 'exam_attempt_terminated';
    END IF;
    RETURN NEW;
END $$;

-- second-person extra time: an approval named on a grant must be the approved request for this grant
CREATE OR REPLACE FUNCTION exam_attempt_extension_insert_guard() RETURNS trigger LANGUAGE plpgsql AS
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
    IF NEW.approval_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM approval_request r
            WHERE r.id = NEW.approval_id AND r.tenant_id = NEW.tenant_id AND r.type = 'exam.attempt.extend' AND r.status = 'APPROVED'
              AND r.subject_id = NEW.attempt_id::text AND r.requested_by = NEW.granted_by AND r.decided_by = NEW.approved_by
              AND r.decided_by <> r.requested_by
              AND (r.payload ->> 'minutes')::int = NEW.minutes) THEN
        RAISE EXCEPTION 'extra time approved by a second person needs that approved request'
            USING ERRCODE = '23514', CONSTRAINT = 'exam_attempt_extension_second_person';
    END IF;
    RETURN NEW;
END $$;
