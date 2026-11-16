-- M-G manual evaluation (contract C-2). Evaluators mark anonymous scripts; double marking with moderation; final item
-- marks go to exams through ItemResults (C-1). Every submitted mark and moderation decision is permanent.

-- permissions become grantable: evaluators and faculty mark; exam controllers and administrators manage. Administrators
-- also hold evaluation.write (1.3 privilege ceiling: they hold every non-privileged permission of the roles they assign).
UPDATE role SET permissions = permissions || ARRAY['evaluation.write']
    WHERE system_role AND code IN ('EVALUATOR', 'FACULTY', 'EXAM_CONTROLLER', 'ORG_ADMIN', 'INSTITUTION_ADMIN') AND NOT ('evaluation.write' = ANY (permissions));
UPDATE role SET permissions = permissions || ARRAY['evaluation.manage']
    WHERE system_role AND code IN ('EXAM_CONTROLLER', 'ORG_ADMIN', 'INSTITUTION_ADMIN') AND NOT ('evaluation.manage' = ANY (permissions));

CREATE TABLE evaluation_setup (
    schedule_id      uuid          PRIMARY KEY,
    tenant_id        uuid          NOT NULL REFERENCES tenant (id),
    org_unit_id      uuid          NOT NULL,
    mode             varchar(10)   NOT NULL CHECK (mode IN ('SINGLE', 'DOUBLE')),
    tolerance_pct    numeric(5,2)  NOT NULL DEFAULT 10 CHECK (tolerance_pct BETWEEN 0 AND 100),
    evaluator_ids    uuid[]        NOT NULL DEFAULT '{}',
    updated_by       uuid          NOT NULL,
    updated_at       timestamptz   NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, schedule_id)
);

-- one anonymous script per attempt; the attempt id is never shown to evaluators
CREATE TABLE evaluation_script (
    id               uuid          PRIMARY KEY,
    tenant_id        uuid          NOT NULL REFERENCES tenant (id),
    schedule_id      uuid          NOT NULL,
    attempt_id       uuid          NOT NULL UNIQUE,
    code             varchar(20)   NOT NULL,
    created_at       timestamptz   NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, code),
    FOREIGN KEY (tenant_id, schedule_id) REFERENCES evaluation_setup (tenant_id, schedule_id)
);

CREATE TABLE evaluation_item (
    id                  uuid          PRIMARY KEY,
    tenant_id           uuid          NOT NULL REFERENCES tenant (id),
    script_id           uuid          NOT NULL,
    position            integer       NOT NULL CHECK (position > 0),
    question_version_id uuid          NOT NULL,
    marks               numeric(9,2)  NOT NULL CHECK (marks >= 0),
    status              varchar(12)   NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'MODERATION', 'FINAL')),
    final_awarded       numeric(9,2)  CHECK (final_awarded >= 0 AND final_awarded <= marks),
    final_source        varchar(10)   CHECK (final_source IN ('SINGLE', 'AGREED', 'MODERATED')),
    moderated_by        uuid,
    moderation_reason   varchar(2000),
    finalized_at        timestamptz,
    UNIQUE (tenant_id, id),
    UNIQUE (script_id, position),
    FOREIGN KEY (tenant_id, script_id) REFERENCES evaluation_script (tenant_id, id),
    CHECK ((status = 'FINAL') = (final_awarded IS NOT NULL AND final_source IS NOT NULL AND finalized_at IS NOT NULL)),
    CHECK ((final_source = 'MODERATED') = (moderated_by IS NOT NULL AND moderation_reason IS NOT NULL))
);

CREATE TABLE evaluation_task (
    id               uuid          PRIMARY KEY,
    tenant_id        uuid          NOT NULL REFERENCES tenant (id),
    item_id          uuid          NOT NULL,
    round            smallint      NOT NULL CHECK (round IN (1, 2)),
    evaluator_id     uuid          NOT NULL,
    status           varchar(10)   NOT NULL DEFAULT 'ASSIGNED' CHECK (status IN ('ASSIGNED', 'SUBMITTED')),
    assigned_at      timestamptz   NOT NULL DEFAULT now(),
    submitted_at     timestamptz,
    criterion_marks  jsonb,
    total            numeric(9,2)  CHECK (total >= 0),
    comment          varchar(2000),
    UNIQUE (item_id, round),
    UNIQUE (item_id, evaluator_id),
    FOREIGN KEY (tenant_id, item_id) REFERENCES evaluation_item (tenant_id, id),
    CHECK ((status = 'SUBMITTED') = (submitted_at IS NOT NULL AND total IS NOT NULL))
);
CREATE INDEX evaluation_task_evaluator ON evaluation_task (tenant_id, evaluator_id, status);

-- a submitted task never changes and is never deleted; a final item never changes
CREATE FUNCTION evaluation_task_guard() RETURNS trigger LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'DELETE' OR OLD.status = 'SUBMITTED' THEN
        RAISE EXCEPTION 'submitted evaluations never change' USING ERRCODE = '23514', CONSTRAINT = 'evaluation_task_immutable';
    END IF;
    IF NEW.item_id <> OLD.item_id OR NEW.round <> OLD.round OR NEW.evaluator_id <> OLD.evaluator_id THEN
        RAISE EXCEPTION 'an allocation never moves' USING ERRCODE = '23514', CONSTRAINT = 'evaluation_task_immutable';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER evaluation_task_guard BEFORE UPDATE OR DELETE ON evaluation_task FOR EACH ROW EXECUTE FUNCTION evaluation_task_guard();

CREATE FUNCTION evaluation_item_guard() RETURNS trigger LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'DELETE' OR OLD.status = 'FINAL' OR NEW.position <> OLD.position OR NEW.question_version_id <> OLD.question_version_id
       OR NEW.marks <> OLD.marks OR NEW.script_id <> OLD.script_id THEN
        RAISE EXCEPTION 'evaluation items never change once final' USING ERRCODE = '23514', CONSTRAINT = 'evaluation_item_immutable';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER evaluation_item_guard BEFORE UPDATE OR DELETE ON evaluation_item FOR EACH ROW EXECUTE FUNCTION evaluation_item_guard();

CREATE FUNCTION evaluation_script_guard() RETURNS trigger LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'scripts never change' USING ERRCODE = '23514', CONSTRAINT = 'evaluation_script_immutable';
END $$;
CREATE TRIGGER evaluation_script_guard BEFORE UPDATE OR DELETE ON evaluation_script FOR EACH ROW EXECUTE FUNCTION evaluation_script_guard();

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['evaluation_setup', 'evaluation_script', 'evaluation_item', 'evaluation_task'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id())', t);
    END LOOP;
END $$;
REVOKE DELETE, TRUNCATE ON evaluation_setup, evaluation_script, evaluation_item, evaluation_task FROM ${appRole};
REVOKE UPDATE ON evaluation_script FROM ${appRole};

-- how an evaluator used an AI suggestion (add-on A, contract C-2 §4); recorded once per task
CREATE TABLE evaluation_suggestion_use (
    task_id          uuid          PRIMARY KEY,
    tenant_id        uuid          NOT NULL REFERENCES tenant (id),
    suggestion_id    uuid          NOT NULL,
    use              varchar(10)   NOT NULL CHECK (use IN ('ACCEPTED', 'CHANGED', 'IGNORED')),
    recorded_at      timestamptz   NOT NULL DEFAULT now(),
    FOREIGN KEY (task_id) REFERENCES evaluation_task (id)
);
ALTER TABLE evaluation_suggestion_use ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON evaluation_suggestion_use USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());
REVOKE UPDATE, DELETE, TRUNCATE ON evaluation_suggestion_use FROM ${appRole};
