-- Session 5, S-7 (approved by Session A, 2026-11-16). Renumbered above the highest version on main before merge.
-- admission.* and placement.* become available; analytics.* and placement.employer.read are new and not privileged;
-- admission.decide and placement.decide are privileged (P2-08). No existing system role gains a privileged permission:
-- they are held only by two new system roles, which are assigned through the existing four-eyes request (or the operator
-- bootstrap), exactly like EXAM_CONTROLLER today.

CREATE OR REPLACE FUNCTION privileged_permissions() RETURNS text[] LANGUAGE sql IMMUTABLE AS
$$ SELECT ARRAY['question.approve', 'question.key.read', 'assessment.publish', 'exam.results.approve', 'exam.attempt.terminate', 'exam.attempt.extend',
                'admission.decide', 'placement.decide'] $$;

INSERT INTO role (id, tenant_id, code, name, description, system_role, permissions) VALUES
 ('01920000-0000-7000-8000-000000000010', NULL, 'ADMISSION_APPROVER', 'Admission Approver',
   'Independently approves merit-list publication and admission offers (privileged; assigned through approval).', true,
   ARRAY['org_unit.read', 'admission.read', 'admission.decide']),
 ('01920000-0000-7000-8000-000000000011', NULL, 'PLACEMENT_APPROVER', 'Placement Approver',
   'Independently approves the release of drive shortlists to employers (privileged; assigned through approval).', true,
   ARRAY['org_unit.read', 'placement.read', 'placement.decide']);

-- non-privileged grants to existing system roles
UPDATE role SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['admission.read', 'admission.manage', 'placement.read', 'placement.manage',
                                                                               'analytics.read', 'analytics.manage']) ORDER BY 1)
    WHERE system_role AND tenant_id IS NULL AND code IN ('ORG_ADMIN', 'INSTITUTION_ADMIN');
UPDATE role SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['admission.read', 'admission.manage']) ORDER BY 1)
    WHERE system_role AND tenant_id IS NULL AND code = 'ADMISSION_OFFICER';
UPDATE role SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['placement.read', 'placement.manage']) ORDER BY 1)
    WHERE system_role AND tenant_id IS NULL AND code = 'PLACEMENT_OFFICER';
UPDATE role SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['analytics.read']) ORDER BY 1)
    WHERE system_role AND tenant_id IS NULL AND code IN ('DEPARTMENT_HEAD', 'FACULTY', 'EXAM_CONTROLLER', 'MANAGEMENT');
-- the employer role (Q2): reads only through placement's employer_access rows, checked on every read
UPDATE role SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['placement.employer.read']) ORDER BY 1)
    WHERE system_role AND tenant_id IS NULL AND code = 'EXTERNAL_RECRUITER';
