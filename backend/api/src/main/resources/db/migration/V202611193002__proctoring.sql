-- Demo programme, add-on B (Session 4): proctoring and examination security on top of the existing examination engine.
-- Principles (docs/phase-2/09-proctoring.md, approved by Session A on 2026-11-16):
--  * flags are signals, never findings: only a person decides (dismiss, warn, incident, termination REQUEST through the
--    existing four-eyes termination); nothing here changes an attempt, a score or a result;
--  * consent before any capture: the server refuses signals and snapshots of an attempt without a GIVEN consent record;
--  * evidence is limited: no audio, no video stream, no screen capture, no biometrics; snapshots are small JPEGs kept for
--    a retention period and then deleted (deletion recorded), never exported, never sent through webhooks or API clients;
--  * every table is tenant-scoped with RLS; consent, signals, flags and decisions are permanent (append-only) records.

-- the proctoring level of one scheduled examination (absent row = NONE); frozen once the window has opened
CREATE TABLE proctoring_setting (
    tenant_id             uuid          NOT NULL REFERENCES tenant (id),
    schedule_id           uuid          NOT NULL,
    level                 varchar(20)   NOT NULL CHECK (level IN ('NONE', 'BASIC', 'SNAPSHOTS')),
    snapshot_interval_s   integer       NOT NULL DEFAULT 120 CHECK (snapshot_interval_s BETWEEN 60 AND 600),
    retention_days        integer       NOT NULL DEFAULT 30 CHECK (retention_days BETWEEN 7 AND 90),
    consent_version       varchar(40)   NOT NULL,
    updated_by            uuid          NOT NULL,
    updated_at            timestamptz   NOT NULL DEFAULT now(),
    version               bigint        NOT NULL DEFAULT 0,
    PRIMARY KEY (schedule_id),
    UNIQUE (tenant_id, schedule_id),
    FOREIGN KEY (tenant_id, schedule_id) REFERENCES exam_schedule (tenant_id, id)
);

-- the candidate's decision on the notice shown before the attempt starts (text version and hash of exactly what was shown)
CREATE TABLE proctoring_consent (
    id                    uuid PRIMARY KEY,
    tenant_id             uuid          NOT NULL REFERENCES tenant (id),
    attempt_id            uuid          NOT NULL,
    schedule_id           uuid          NOT NULL,
    enrolment_id          uuid          NOT NULL,
    level                 varchar(20)   NOT NULL CHECK (level IN ('BASIC', 'SNAPSHOTS')),
    consent_version       varchar(40)   NOT NULL,
    text_hash             char(64)      NOT NULL,
    decision              varchar(10)   NOT NULL CHECK (decision IN ('GIVEN', 'DECLINED')),
    decided_at            timestamptz   NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (attempt_id),                                                  -- one decision per attempt
    FOREIGN KEY (tenant_id, attempt_id) REFERENCES exam_attempt (tenant_id, id),
    FOREIGN KEY (tenant_id, schedule_id) REFERENCES exam_schedule (tenant_id, id),
    FOREIGN KEY (tenant_id, enrolment_id) REFERENCES exam_enrolment (tenant_id, id)
);

-- browser signals (append-only); small detail only (never content, keystrokes or images)
CREATE TABLE proctoring_signal (
    id                    uuid PRIMARY KEY,
    tenant_id             uuid          NOT NULL REFERENCES tenant (id),
    attempt_id            uuid          NOT NULL,
    kind                  varchar(30)   NOT NULL CHECK (kind IN ('FOCUS_LOST', 'TAB_HIDDEN', 'FULLSCREEN_EXIT', 'COPY', 'PASTE', 'MULTI_DISPLAY',
                                                               'CAMERA_UNAVAILABLE')),
    client_at             timestamptz   NOT NULL,
    received_at           timestamptz   NOT NULL DEFAULT now(),
    duration_ms           integer       CHECK (duration_ms BETWEEN 0 AND 86400000),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, attempt_id) REFERENCES exam_attempt (tenant_id, id)
);
CREATE INDEX proctoring_signal_attempt_idx ON proctoring_signal (tenant_id, attempt_id, client_at);

-- webcam snapshots kept as evidence: the image is in object storage (quarantined until scanned clean); expires_at is set
-- when the row is written (taken_at + retention); a hold keeps it while a flag is open or an incident refers to it
CREATE TABLE proctoring_snapshot (
    id                    uuid PRIMARY KEY,
    tenant_id             uuid          NOT NULL REFERENCES tenant (id),
    attempt_id            uuid          NOT NULL,
    object_key            varchar(512)  NOT NULL,
    sha256                char(64)      NOT NULL,
    bytes                 integer       NOT NULL CHECK (bytes BETWEEN 1 AND 204800),
    scan_status           varchar(20)   NOT NULL CHECK (scan_status IN ('CLEAN', 'INFECTED', 'ERROR', 'NOT_SCANNED_DEV')),
    taken_at              timestamptz   NOT NULL,
    received_at           timestamptz   NOT NULL DEFAULT now(),
    expires_at            timestamptz   NOT NULL,
    hold                  boolean       NOT NULL DEFAULT false,
    deleted_at            timestamptz,
    UNIQUE (tenant_id, id),
    CHECK (expires_at > taken_at),
    FOREIGN KEY (tenant_id, attempt_id) REFERENCES exam_attempt (tenant_id, id)
);
CREATE INDEX proctoring_snapshot_attempt_idx ON proctoring_snapshot (tenant_id, attempt_id, taken_at);
CREATE INDEX proctoring_snapshot_expiry_idx ON proctoring_snapshot (expires_at) WHERE deleted_at IS NULL;

-- flags raised by deterministic, versioned rules over signals; counters grow while the same rule keeps firing
CREATE TABLE proctoring_flag (
    id                    uuid PRIMARY KEY,
    tenant_id             uuid          NOT NULL REFERENCES tenant (id),
    attempt_id            uuid          NOT NULL,
    schedule_id           uuid          NOT NULL,
    enrolment_id          uuid          NOT NULL,
    rule_code             varchar(40)   NOT NULL,
    rule_version          smallint      NOT NULL,
    severity              varchar(10)   NOT NULL CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH')),
    first_at              timestamptz   NOT NULL,
    last_at               timestamptz   NOT NULL,
    signal_count          integer       NOT NULL CHECK (signal_count >= 1),
    status                varchar(20)   NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'DECIDED')),
    UNIQUE (tenant_id, id),
    UNIQUE (attempt_id, rule_code),                                        -- one flag per rule and attempt
    CHECK (last_at >= first_at),
    FOREIGN KEY (tenant_id, attempt_id) REFERENCES exam_attempt (tenant_id, id),
    FOREIGN KEY (tenant_id, schedule_id) REFERENCES exam_schedule (tenant_id, id),
    FOREIGN KEY (tenant_id, enrolment_id) REFERENCES exam_enrolment (tenant_id, id)
);
CREATE INDEX proctoring_flag_schedule_idx ON proctoring_flag (tenant_id, schedule_id, status);

-- a person's decision on a flag (one per flag, permanent): the reason is required; warnings carry the text the candidate sees
CREATE TABLE proctoring_flag_decision (
    id                    uuid PRIMARY KEY,
    tenant_id             uuid          NOT NULL REFERENCES tenant (id),
    flag_id               uuid          NOT NULL,
    decision              varchar(30)   NOT NULL CHECK (decision IN ('DISMISS', 'WARN', 'INCIDENT', 'TERMINATION_REQUESTED')),
    reason                varchar(1000) NOT NULL CHECK (length(btrim(reason)) > 0),
    warning_text          varchar(300),
    incident_id           uuid,
    approval_id           uuid,
    decided_by            uuid          NOT NULL,
    decided_at            timestamptz   NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (flag_id),
    FOREIGN KEY (tenant_id, flag_id) REFERENCES proctoring_flag (tenant_id, id),
    CONSTRAINT proctoring_decision_warning CHECK ((decision = 'WARN') = (warning_text IS NOT NULL AND length(btrim(warning_text)) > 0)),
    CONSTRAINT proctoring_decision_incident CHECK ((decision = 'INCIDENT') = (incident_id IS NOT NULL)),
    CONSTRAINT proctoring_decision_termination CHECK ((decision = 'TERMINATION_REQUESTED') = (approval_id IS NOT NULL))
);

-- ===================================================================================================== guards
CREATE FUNCTION proctoring_append_only() RETURNS trigger LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION '% records are permanent', TG_TABLE_NAME USING ERRCODE = '23514', CONSTRAINT = 'proctoring_permanent';
END $$;
CREATE TRIGGER proctoring_consent_guard BEFORE UPDATE OR DELETE ON proctoring_consent FOR EACH ROW EXECUTE FUNCTION proctoring_append_only();
CREATE TRIGGER proctoring_signal_guard BEFORE UPDATE OR DELETE ON proctoring_signal FOR EACH ROW EXECUTE FUNCTION proctoring_append_only();
CREATE TRIGGER proctoring_decision_guard BEFORE UPDATE OR DELETE ON proctoring_flag_decision FOR EACH ROW EXECUTE FUNCTION proctoring_append_only();

-- capture needs consent: no signal or snapshot for an attempt without a GIVEN consent
CREATE FUNCTION proctoring_consent_required() RETURNS trigger LANGUAGE plpgsql AS
$$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM proctoring_consent c WHERE c.attempt_id = NEW.attempt_id AND c.decision = 'GIVEN') THEN
        RAISE EXCEPTION 'no proctoring data without the candidate''s consent' USING ERRCODE = '23514', CONSTRAINT = 'proctoring_consent_required';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER proctoring_signal_consent BEFORE INSERT ON proctoring_signal FOR EACH ROW EXECUTE FUNCTION proctoring_consent_required();
CREATE TRIGGER proctoring_snapshot_consent BEFORE INSERT ON proctoring_snapshot FOR EACH ROW EXECUTE FUNCTION proctoring_consent_required();

-- snapshots: only the hold flag and the deletion (retention) may change, and a deleted snapshot never comes back
CREATE FUNCTION proctoring_snapshot_guard() RETURNS trigger LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'snapshot records are kept; the image is deleted and deleted_at recorded' USING ERRCODE = '23514', CONSTRAINT = 'proctoring_permanent';
    END IF;
    IF (to_jsonb(OLD) - ARRAY['hold', 'deleted_at']) IS DISTINCT FROM (to_jsonb(NEW) - ARRAY['hold', 'deleted_at'])
       OR (OLD.deleted_at IS NOT NULL AND NEW.deleted_at IS DISTINCT FROM OLD.deleted_at) THEN
        RAISE EXCEPTION 'snapshot records are immutable' USING ERRCODE = '23514', CONSTRAINT = 'proctoring_permanent';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER proctoring_snapshot_guard BEFORE UPDATE OR DELETE ON proctoring_snapshot FOR EACH ROW EXECUTE FUNCTION proctoring_snapshot_guard();

-- flags: counters only grow, OPEN → DECIDED once (with a decision), never deleted
CREATE FUNCTION proctoring_flag_guard() RETURNS trigger LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'flags are permanent' USING ERRCODE = '23514', CONSTRAINT = 'proctoring_permanent';
    END IF;
    IF (OLD.id, OLD.tenant_id, OLD.attempt_id, OLD.schedule_id, OLD.enrolment_id, OLD.rule_code, OLD.rule_version, OLD.severity, OLD.first_at)
           IS DISTINCT FROM (NEW.id, NEW.tenant_id, NEW.attempt_id, NEW.schedule_id, NEW.enrolment_id, NEW.rule_code, NEW.rule_version, NEW.severity, NEW.first_at)
       OR NEW.signal_count < OLD.signal_count OR NEW.last_at < OLD.last_at
       OR (OLD.status = 'DECIDED' AND NEW.status <> 'DECIDED')
       OR (NEW.status = 'DECIDED' AND OLD.status = 'OPEN' AND NOT EXISTS (SELECT 1 FROM proctoring_flag_decision d WHERE d.flag_id = NEW.id)) THEN
        RAISE EXCEPTION 'flag % cannot change this way', OLD.id USING ERRCODE = '23514', CONSTRAINT = 'proctoring_permanent';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER proctoring_flag_guard BEFORE UPDATE OR DELETE ON proctoring_flag FOR EACH ROW EXECUTE FUNCTION proctoring_flag_guard();

-- ===================================================================================================== RLS
DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['proctoring_setting', 'proctoring_consent', 'proctoring_signal', 'proctoring_snapshot', 'proctoring_flag',
                             'proctoring_flag_decision'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id())', t);
    END LOOP;
END $$;
REVOKE UPDATE, DELETE ON proctoring_consent, proctoring_signal, proctoring_flag_decision FROM ${appRole};
REVOKE DELETE ON proctoring_snapshot, proctoring_flag FROM ${appRole};

-- ===================================================================================================== permissions
-- configure: exam controllers and administrators; review: proctors and controllers (and administrators, who must hold it to
-- assign the proctor role: privilege ceiling 1.3); evidence: exam controllers only (every view audited). proctoring.evidence.view
-- is privileged (P2-08): administrators never hold it, and a role containing it is assigned only through the four-eyes
-- request approved by a current holder, exactly like question.key.read.
UPDATE role SET permissions = permissions || ARRAY['proctoring.configure']
    WHERE system_role AND code IN ('ORG_ADMIN', 'INSTITUTION_ADMIN', 'EXAM_CONTROLLER') AND NOT ('proctoring.configure' = ANY (permissions));
UPDATE role SET permissions = permissions || ARRAY['proctoring.review']
    WHERE system_role AND code IN ('ORG_ADMIN', 'INSTITUTION_ADMIN', 'EXAM_CONTROLLER', 'EXAM_PROCTOR') AND NOT ('proctoring.review' = ANY (permissions));
UPDATE role SET permissions = permissions || ARRAY['proctoring.evidence.view']
    WHERE system_role AND code = 'EXAM_CONTROLLER' AND NOT ('proctoring.evidence.view' = ANY (permissions));

CREATE OR REPLACE FUNCTION privileged_permissions() RETURNS text[] LANGUAGE sql IMMUTABLE AS
$$ SELECT ARRAY['question.approve', 'question.key.read', 'assessment.publish', 'exam.results.approve', 'exam.attempt.terminate', 'exam.attempt.extend',
                'proctoring.evidence.view'] $$;
