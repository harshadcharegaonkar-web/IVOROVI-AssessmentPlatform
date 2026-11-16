-- Session 5, S-2 (shared change in the students module, reviewed by Session 2 and Session A).
-- An admission record created by converting an accepted admission application keeps the application id, so a
-- repeated conversion returns the same student instead of creating a second one. Not personal data (an opaque id).
-- Renumbered above the highest version on main before merge (demo-programme rule 5).
ALTER TABLE admission_record ADD COLUMN source_application_id uuid;
CREATE UNIQUE INDEX admission_source_application_uq ON admission_record (tenant_id, source_application_id) WHERE source_application_id IS NOT NULL;
