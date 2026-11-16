-- Contract C-1: items marked by evaluators (descriptive) or by the isolated executor (coding).
-- Scoring defers an attempt that has such answers: it records which positions await marks, and writes the attempt's
-- (immutable) score only once every one of them has its marks. Scores therefore stay complete and never change.

CREATE TABLE exam_attempt_deferred (
    attempt_id           uuid         PRIMARY KEY,
    tenant_id            uuid         NOT NULL REFERENCES tenant (id),
    evaluation_positions integer[]    NOT NULL DEFAULT '{}',
    execution_positions  integer[]    NOT NULL DEFAULT '{}',
    created_at           timestamptz  NOT NULL DEFAULT now(),
    CHECK (cardinality(evaluation_positions) + cardinality(execution_positions) > 0),
    FOREIGN KEY (tenant_id, attempt_id) REFERENCES exam_attempt (tenant_id, id)
);

-- The final marks of one deferred item. Written once; a later change goes through re-evaluation, never an update.
CREATE TABLE exam_item_mark (
    tenant_id    uuid          NOT NULL REFERENCES tenant (id),
    attempt_id   uuid          NOT NULL REFERENCES exam_attempt_deferred (attempt_id),
    position     integer       NOT NULL CHECK (position > 0),
    source       varchar(12)   NOT NULL CHECK (source IN ('EVALUATION', 'EXECUTION')),
    awarded      numeric(9,2)  NOT NULL CHECK (awarded >= 0),
    marks        numeric(9,2)  NOT NULL CHECK (marks >= 0 AND awarded <= marks),
    detail       varchar(200),
    recorded_by  uuid,
    recorded_at  timestamptz   NOT NULL DEFAULT now(),
    PRIMARY KEY (attempt_id, position)
);
CREATE INDEX exam_item_mark_tenant ON exam_item_mark (tenant_id);

CREATE FUNCTION exam_deferred_guard() RETURNS trigger LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'deferred marking records never change' USING ERRCODE = '23514', CONSTRAINT = 'exam_deferred_immutable';
END $$;
CREATE TRIGGER exam_attempt_deferred_guard BEFORE UPDATE OR DELETE ON exam_attempt_deferred FOR EACH ROW EXECUTE FUNCTION exam_deferred_guard();
CREATE TRIGGER exam_item_mark_guard BEFORE UPDATE OR DELETE ON exam_item_mark FOR EACH ROW EXECUTE FUNCTION exam_deferred_guard();

-- where an answer's marks came from (existing rows: automatic scoring)
ALTER TABLE exam_answer_score ADD COLUMN source varchar(12) NOT NULL DEFAULT 'AUTOMATIC' CHECK (source IN ('AUTOMATIC', 'EVALUATION', 'EXECUTION'));

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['exam_attempt_deferred', 'exam_item_mark'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id())', t);
    END LOOP;
END $$;
REVOKE UPDATE, DELETE, TRUNCATE ON exam_attempt_deferred, exam_item_mark FROM ${appRole};
