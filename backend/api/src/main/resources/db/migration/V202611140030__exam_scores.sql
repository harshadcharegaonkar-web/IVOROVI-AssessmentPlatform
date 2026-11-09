-- M4 automatic scoring (primary stream, programme Day 2): provisional scores of submitted attempts. Marks come from the
-- candidate's paper (what the published assessment assigned); grading runs inside the question module, so no answer key
-- is stored here. Scores are written once per attempt and never changed; releasing them to candidates (with independent
-- approval) is a separate step.

CREATE TABLE exam_score (
    attempt_id        uuid          PRIMARY KEY,
    tenant_id         uuid          NOT NULL REFERENCES tenant (id),
    status            varchar(20)   NOT NULL DEFAULT 'PROVISIONAL' CHECK (status IN ('PROVISIONAL', 'RELEASED')),
    awarded           numeric(9,2)  NOT NULL,
    max_marks         numeric(9,2)  NOT NULL CHECK (max_marks >= 0),
    correct           integer       NOT NULL,
    partial           integer       NOT NULL,
    incorrect         integer       NOT NULL,
    unanswered        integer       NOT NULL,
    not_markable      integer       NOT NULL,
    scored_at         timestamptz   NOT NULL DEFAULT now(),
    score_hash        char(64)      NOT NULL,                  -- SHA-256 over the per-answer grades (evidence)
    UNIQUE (tenant_id, attempt_id),
    FOREIGN KEY (tenant_id, attempt_id) REFERENCES exam_attempt (tenant_id, id)
);

CREATE TABLE exam_answer_score (
    tenant_id           uuid          NOT NULL REFERENCES tenant (id),
    attempt_id          uuid          NOT NULL,
    position            integer       NOT NULL,
    question_version_id uuid          NOT NULL,
    outcome             varchar(20)   NOT NULL CHECK (outcome IN ('CORRECT', 'PARTIAL', 'INCORRECT', 'UNANSWERED', 'INVALID', 'NOT_MARKABLE')),
    awarded             numeric(9,2)  NOT NULL,
    marks               numeric(9,2)  NOT NULL,
    PRIMARY KEY (attempt_id, position),
    FOREIGN KEY (tenant_id, attempt_id) REFERENCES exam_score (tenant_id, attempt_id)
);

-- scores only for submitted attempts; never changed or deleted (release is the one allowed change)
CREATE FUNCTION exam_score_guard() RETURNS trigger LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NOT EXISTS (SELECT 1 FROM exam_attempt a WHERE a.id = NEW.attempt_id AND a.status IN ('SUBMITTED', 'AUTO_SUBMITTED')) THEN
            RAISE EXCEPTION 'only submitted attempts are scored' USING ERRCODE = '23514', CONSTRAINT = 'exam_score_submitted';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.status = 'PROVISIONAL' AND NEW.status = 'RELEASED'
       AND (to_jsonb(NEW) - 'status') = (to_jsonb(OLD) - 'status') THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'scores never change' USING ERRCODE = '23514', CONSTRAINT = 'exam_score_immutable';
END $$;
CREATE TRIGGER exam_score_guard BEFORE INSERT OR UPDATE OR DELETE ON exam_score FOR EACH ROW EXECUTE FUNCTION exam_score_guard();

CREATE FUNCTION exam_answer_score_guard() RETURNS trigger LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'answer scores never change' USING ERRCODE = '23514', CONSTRAINT = 'exam_answer_score_immutable';
END $$;
CREATE TRIGGER exam_answer_score_guard BEFORE UPDATE OR DELETE ON exam_answer_score FOR EACH ROW EXECUTE FUNCTION exam_answer_score_guard();

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['exam_score', 'exam_answer_score'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id())', t);
    END LOOP;
END $$;
REVOKE DELETE ON exam_score, exam_answer_score FROM ${appRole};
REVOKE UPDATE ON exam_answer_score FROM ${appRole};
