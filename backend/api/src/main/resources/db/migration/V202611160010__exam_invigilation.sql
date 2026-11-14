-- Day 4 P3: invigilation. Live monitoring of a scheduled examination and candidate incidents recorded by invigilators.
--  * exam.invigilate (unit scope): monitor candidates' check-in, connection and attempt progress (never answers or scores) and
--    record incidents. Held by the existing EXAM_PROCTOR system role (until now without permissions), exam controllers and
--    administrators (administrators must hold it to assign the proctor role: privilege ceiling 1.3).
--  * Incidents are permanent records (never changed or deleted); a correction is a new incident that refers to the first.

CREATE TABLE exam_candidate_incident (
    id            uuid PRIMARY KEY,
    tenant_id     uuid          NOT NULL REFERENCES tenant (id),
    schedule_id   uuid          NOT NULL,
    enrolment_id  uuid          NOT NULL,
    category      varchar(30)   NOT NULL CHECK (category IN ('IDENTITY', 'SUSPECTED_MALPRACTICE', 'TECHNICAL', 'DISRUPTION', 'LATE_ARRIVAL', 'OTHER')),
    note          varchar(1000) NOT NULL CHECK (length(btrim(note)) > 0),
    corrects_id   uuid          REFERENCES exam_candidate_incident (id),
    recorded_by   uuid          NOT NULL,
    recorded_at   timestamptz   NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, schedule_id) REFERENCES exam_schedule (tenant_id, id),
    FOREIGN KEY (tenant_id, enrolment_id) REFERENCES exam_enrolment (tenant_id, id)
);
CREATE INDEX exam_candidate_incident_schedule_idx ON exam_candidate_incident (tenant_id, schedule_id, recorded_at);

CREATE FUNCTION exam_candidate_incident_guard() RETURNS trigger LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'candidate incidents are permanent records; record a correcting incident instead' USING ERRCODE = '23514', CONSTRAINT = 'exam_candidate_incident_permanent';
END $$;
CREATE TRIGGER exam_candidate_incident_guard BEFORE UPDATE OR DELETE ON exam_candidate_incident FOR EACH ROW EXECUTE FUNCTION exam_candidate_incident_guard();

ALTER TABLE exam_candidate_incident ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON exam_candidate_incident USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());
REVOKE UPDATE, DELETE ON exam_candidate_incident FROM ${appRole};

UPDATE role SET permissions = permissions || ARRAY['exam.invigilate']
    WHERE system_role AND code IN ('ORG_ADMIN', 'INSTITUTION_ADMIN', 'EXAM_CONTROLLER', 'EXAM_PROCTOR') AND NOT ('exam.invigilate' = ANY (permissions));
