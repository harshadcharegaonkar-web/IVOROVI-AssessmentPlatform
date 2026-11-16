-- Session 5, S-4 (shared change in the scheduling module, reviewed by Session A).
-- An enrolment created by the admissions or placement module through scheduling.ExamScheduling records whom it is
-- for (an opaque id of a student, an applicant or a placement candidate). Never set from a CSV upload; once set it
-- never changes. Not personal data. Renumbered above the highest version on main before merge (demo-programme rule 5).
ALTER TABLE exam_enrolment
    ADD COLUMN subject_type varchar(30) CHECK (subject_type IN ('STUDENT', 'APPLICANT', 'PLACEMENT_CANDIDATE')),
    ADD COLUMN subject_ref  uuid,
    ADD CONSTRAINT exam_enrolment_subject_pair CHECK ((subject_type IS NULL) = (subject_ref IS NULL));
ALTER TABLE exam_enrolment DROP CONSTRAINT exam_enrolment_source_check;
ALTER TABLE exam_enrolment ADD CONSTRAINT exam_enrolment_source_check CHECK (source IN ('UPLOAD', 'MANUAL', 'MODULE'));
ALTER TABLE exam_enrolment ADD CONSTRAINT exam_enrolment_module_subject CHECK (source <> 'MODULE' OR subject_ref IS NOT NULL);
CREATE UNIQUE INDEX exam_enrolment_subject_uq ON exam_enrolment (tenant_id, schedule_id, subject_type, subject_ref) WHERE subject_ref IS NOT NULL;
CREATE INDEX exam_enrolment_subject_idx ON exam_enrolment (tenant_id, subject_type, subject_ref) WHERE subject_ref IS NOT NULL;

CREATE OR REPLACE FUNCTION exam_enrolment_guard() RETURNS trigger LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'enrolments are withdrawn, never deleted' USING ERRCODE = '23514', CONSTRAINT = 'exam_enrolment_permanent';
    END IF;
    IF NEW.schedule_id <> OLD.schedule_id OR NEW.email_index <> OLD.email_index
       OR (OLD.candidate_id IS NOT NULL AND NEW.candidate_id IS DISTINCT FROM OLD.candidate_id) THEN
        RAISE EXCEPTION 'an enrolment''s schedule, email and linked candidate never change' USING ERRCODE = '23514', CONSTRAINT = 'exam_enrolment_binding';
    END IF;
    IF OLD.subject_ref IS NOT NULL AND (NEW.subject_type, NEW.subject_ref) IS DISTINCT FROM (OLD.subject_type, OLD.subject_ref) THEN
        RAISE EXCEPTION 'an enrolment''s subject never changes once recorded' USING ERRCODE = '23514', CONSTRAINT = 'exam_enrolment_subject_binding';
    END IF;
    RETURN NEW;
END $$;
