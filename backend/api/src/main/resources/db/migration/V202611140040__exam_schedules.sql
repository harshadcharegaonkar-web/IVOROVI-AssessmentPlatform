-- M2 scheduling and enrolment (Session A, programme Day 2; taken over from Session 2 after its usage limit).
-- A schedule binds one PUBLISHED assessment version (never a later one) to an exam portal, an institution, a window and
-- one exam session (exams module: codes, check-in). Enrolments hold the invited email encrypted plus a keyed index
-- (same index as candidate_account.email_index), so the candidate is linked without plaintext lookups.

CREATE TABLE exam_schedule (
    id                uuid PRIMARY KEY,
    tenant_id         uuid          NOT NULL REFERENCES tenant (id),
    org_unit_id       uuid,
    version_id        uuid          NOT NULL,
    portal_id         uuid          NOT NULL,
    session_id        uuid          NOT NULL,
    title             varchar(200)  NOT NULL,
    opens_at          timestamptz   NOT NULL,
    closes_at         timestamptz   NOT NULL,
    late_entry_until  timestamptz,
    duration_minutes  integer       NOT NULL CHECK (duration_minutes BETWEEN 1 AND 600),
    instructions      varchar(4000),
    calculator        boolean       NOT NULL DEFAULT false,
    status            varchar(20)   NOT NULL DEFAULT 'SCHEDULED' CHECK (status IN ('SCHEDULED', 'CANCELLED')),
    created_by        uuid          NOT NULL,
    created_at        timestamptz   NOT NULL DEFAULT now(),
    updated_at        timestamptz   NOT NULL DEFAULT now(),
    version           bigint        NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    CHECK (closes_at > opens_at),
    CHECK (late_entry_until IS NULL OR (late_entry_until >= opens_at AND late_entry_until <= closes_at)),
    FOREIGN KEY (tenant_id, org_unit_id) REFERENCES org_unit (tenant_id, id),
    FOREIGN KEY (tenant_id, version_id) REFERENCES assessment_version (tenant_id, id),
    FOREIGN KEY (tenant_id, portal_id) REFERENCES exam_portal (tenant_id, id),
    FOREIGN KEY (tenant_id, session_id) REFERENCES readiness_session (tenant_id, id)
);

CREATE TABLE exam_enrolment (
    id                uuid PRIMARY KEY,
    tenant_id         uuid          NOT NULL REFERENCES tenant (id),
    schedule_id       uuid          NOT NULL,
    email_enc         text          NOT NULL,
    email_index       char(64)      NOT NULL,
    display_name      varchar(200),
    candidate_id      uuid,
    extra_minutes     integer       NOT NULL DEFAULT 0 CHECK (extra_minutes BETWEEN 0 AND 600),
    extra_reason      varchar(500),
    source            varchar(20)   NOT NULL CHECK (source IN ('UPLOAD', 'MANUAL')),
    invitation_status varchar(20)   NOT NULL DEFAULT 'NOT_SENT' CHECK (invitation_status IN ('NOT_SENT', 'SENT', 'FAILED')),
    invited_at        timestamptz,
    status            varchar(20)   NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'WITHDRAWN')),
    created_by        uuid          NOT NULL,
    created_at        timestamptz   NOT NULL DEFAULT now(),
    version           bigint        NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    UNIQUE (schedule_id, email_index),                         -- re-uploading the same list never duplicates
    CHECK ((extra_minutes = 0) OR extra_reason IS NOT NULL),   -- extra time always has a recorded reason
    FOREIGN KEY (tenant_id, schedule_id) REFERENCES exam_schedule (tenant_id, id),
    FOREIGN KEY (tenant_id, candidate_id) REFERENCES candidate_account (tenant_id, id)
);
CREATE INDEX exam_enrolment_candidate_idx ON exam_enrolment (tenant_id, candidate_id) WHERE candidate_id IS NOT NULL;
CREATE INDEX exam_enrolment_index_idx ON exam_enrolment (tenant_id, email_index);

-- a schedule's assessment version, portal, session and institution never change; schedules are cancelled, never deleted
CREATE FUNCTION exam_schedule_guard() RETURNS trigger LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'schedules are cancelled, never deleted' USING ERRCODE = '23514', CONSTRAINT = 'exam_schedule_permanent';
    END IF;
    IF (NEW.version_id, NEW.portal_id, NEW.session_id, NEW.org_unit_id, NEW.tenant_id) IS DISTINCT FROM
       (OLD.version_id, OLD.portal_id, OLD.session_id, OLD.org_unit_id, OLD.tenant_id) THEN
        RAISE EXCEPTION 'a schedule''s assessment version, portal, session and institution never change' USING ERRCODE = '23514', CONSTRAINT = 'exam_schedule_binding';
    END IF;
    IF OLD.status = 'CANCELLED' THEN
        RAISE EXCEPTION 'a cancelled schedule never changes' USING ERRCODE = '23514', CONSTRAINT = 'exam_schedule_cancelled';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER exam_schedule_guard BEFORE UPDATE OR DELETE ON exam_schedule FOR EACH ROW EXECUTE FUNCTION exam_schedule_guard();

CREATE FUNCTION exam_enrolment_guard() RETURNS trigger LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'enrolments are withdrawn, never deleted' USING ERRCODE = '23514', CONSTRAINT = 'exam_enrolment_permanent';
    END IF;
    IF NEW.schedule_id <> OLD.schedule_id OR NEW.email_index <> OLD.email_index
       OR (OLD.candidate_id IS NOT NULL AND NEW.candidate_id IS DISTINCT FROM OLD.candidate_id) THEN
        RAISE EXCEPTION 'an enrolment''s schedule, email and linked candidate never change' USING ERRCODE = '23514', CONSTRAINT = 'exam_enrolment_binding';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER exam_enrolment_guard BEFORE UPDATE OR DELETE ON exam_enrolment FOR EACH ROW EXECUTE FUNCTION exam_enrolment_guard();

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['exam_schedule', 'exam_enrolment'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id())', t);
    END LOOP;
END $$;
REVOKE DELETE ON exam_schedule, exam_enrolment FROM ${appRole};

-- permissions: read for administrators; manage for exam controllers and administrators (they already hold exam.session.manage)
UPDATE role SET permissions = permissions || ARRAY['exam.schedule.read', 'exam.schedule.manage']
    WHERE system_role AND code IN ('ORG_ADMIN', 'INSTITUTION_ADMIN', 'EXAM_CONTROLLER');
