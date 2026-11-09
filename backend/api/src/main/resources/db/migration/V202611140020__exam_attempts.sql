-- M3 delivery (primary stream, programme Day 2): candidate attempts, durable answers, idempotent submission.
-- An attempt is bound to the exact published assessment version and to the candidate's generated paper; answers are
-- saved per paper position with a client sequence number (only newer saves win) and journalled append-only. After
-- submission (or the deadline plus a short grace for in-flight saves) nothing changes, enforced here, not only in code.

CREATE TABLE exam_attempt (
    id                uuid PRIMARY KEY,
    tenant_id         uuid          NOT NULL REFERENCES tenant (id),
    schedule_id       uuid          NOT NULL,                 -- scheduling module (verified through ExamEligibility at start)
    candidate_id      uuid          NOT NULL,
    session_id        uuid          NOT NULL,
    version_id        uuid          NOT NULL,
    paper_id          uuid          NOT NULL,
    status            varchar(20)   NOT NULL DEFAULT 'IN_PROGRESS' CHECK (status IN ('IN_PROGRESS', 'SUBMITTED', 'AUTO_SUBMITTED')),
    started_at        timestamptz   NOT NULL DEFAULT now(),
    deadline_at       timestamptz   NOT NULL,
    extra_minutes     integer       NOT NULL DEFAULT 0 CHECK (extra_minutes BETWEEN 0 AND 600),
    submitted_at      timestamptz,
    submit_key        varchar(100),
    answers_saved     integer       NOT NULL DEFAULT 0,
    version           bigint        NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, schedule_id, candidate_id),             -- one attempt per candidate and schedule
    CHECK (deadline_at > started_at),
    CHECK ((status = 'IN_PROGRESS') = (submitted_at IS NULL)),
    FOREIGN KEY (tenant_id, candidate_id) REFERENCES candidate_account (tenant_id, id),
    FOREIGN KEY (tenant_id, session_id) REFERENCES readiness_session (tenant_id, id),
    FOREIGN KEY (tenant_id, version_id) REFERENCES assessment_version (tenant_id, id),
    FOREIGN KEY (tenant_id, paper_id) REFERENCES assessment_paper (tenant_id, id)
);
CREATE INDEX exam_attempt_deadline_idx ON exam_attempt (deadline_at) WHERE status = 'IN_PROGRESS';

CREATE TABLE exam_answer (
    tenant_id           uuid          NOT NULL REFERENCES tenant (id),
    attempt_id          uuid          NOT NULL,
    position            integer       NOT NULL CHECK (position >= 1),
    question_version_id uuid          NOT NULL,
    response            jsonb         NOT NULL,
    seq                 bigint        NOT NULL CHECK (seq >= 0),
    saved_at            timestamptz   NOT NULL DEFAULT now(),
    PRIMARY KEY (attempt_id, position),
    FOREIGN KEY (tenant_id, attempt_id) REFERENCES exam_attempt (tenant_id, id)
);

-- every accepted save, in order (recovery and dispute evidence); never changed
CREATE TABLE exam_answer_journal (
    id                  bigserial     PRIMARY KEY,
    tenant_id           uuid          NOT NULL REFERENCES tenant (id),
    attempt_id          uuid          NOT NULL,
    position            integer       NOT NULL,
    seq                 bigint        NOT NULL,
    response            jsonb         NOT NULL,
    saved_at            timestamptz   NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, attempt_id) REFERENCES exam_attempt (tenant_id, id)
);
CREATE INDEX exam_answer_journal_attempt_idx ON exam_answer_journal (attempt_id, position, seq);

-- attempts: identity, binding and deadline never change; status only moves forward; never deleted
CREATE FUNCTION exam_attempt_guard() RETURNS trigger LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'attempts are never deleted' USING ERRCODE = '23514', CONSTRAINT = 'exam_attempt_permanent';
    END IF;
    IF (to_jsonb(NEW) - ARRAY['status', 'submitted_at', 'submit_key', 'answers_saved', 'version'])
       IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['status', 'submitted_at', 'submit_key', 'answers_saved', 'version']) THEN
        RAISE EXCEPTION 'an attempt''s binding and deadline never change' USING ERRCODE = '23514', CONSTRAINT = 'exam_attempt_immutable';
    END IF;
    IF OLD.status <> 'IN_PROGRESS' AND (NEW.status, NEW.submitted_at, NEW.submit_key, NEW.answers_saved)
                                      IS DISTINCT FROM (OLD.status, OLD.submitted_at, OLD.submit_key, OLD.answers_saved) THEN
        RAISE EXCEPTION 'a submitted attempt never changes' USING ERRCODE = '23514', CONSTRAINT = 'exam_attempt_submitted';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER exam_attempt_guard BEFORE UPDATE OR DELETE ON exam_attempt FOR EACH ROW EXECUTE FUNCTION exam_attempt_guard();

-- answers: only while the attempt is open and before its deadline plus 30 s of grace (in-flight saves); never deleted;
-- a save never replaces a newer one
CREATE FUNCTION exam_answer_guard() RETURNS trigger LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'answers are never deleted' USING ERRCODE = '23514', CONSTRAINT = 'exam_answer_permanent';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM exam_attempt a WHERE a.id = NEW.attempt_id AND a.status = 'IN_PROGRESS'
                   AND now() <= a.deadline_at + interval '30 seconds') THEN
        RAISE EXCEPTION 'the attempt is closed' USING ERRCODE = '23514', CONSTRAINT = 'exam_answer_closed';
    END IF;
    IF TG_OP = 'UPDATE' AND (NEW.seq <= OLD.seq OR NEW.attempt_id <> OLD.attempt_id OR NEW.position <> OLD.position
                             OR NEW.question_version_id <> OLD.question_version_id) THEN
        RAISE EXCEPTION 'stale or invalid answer update' USING ERRCODE = '23514', CONSTRAINT = 'exam_answer_order';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER exam_answer_guard BEFORE INSERT OR UPDATE OR DELETE ON exam_answer FOR EACH ROW EXECUTE FUNCTION exam_answer_guard();

CREATE FUNCTION exam_answer_journal_append_only() RETURNS trigger LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'the answer journal is append-only' USING ERRCODE = '23514', CONSTRAINT = 'exam_answer_journal_append_only';
END $$;
CREATE TRIGGER exam_answer_journal_append_only BEFORE UPDATE OR DELETE ON exam_answer_journal FOR EACH ROW EXECUTE FUNCTION exam_answer_journal_append_only();

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['exam_attempt', 'exam_answer', 'exam_answer_journal'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id())', t);
    END LOOP;
END $$;
-- defence in depth behind the triggers: the application role cannot delete attempts or answers, nor rewrite the journal
REVOKE DELETE ON exam_attempt, exam_answer FROM ${appRole};
REVOKE UPDATE, DELETE ON exam_answer_journal FROM ${appRole};
