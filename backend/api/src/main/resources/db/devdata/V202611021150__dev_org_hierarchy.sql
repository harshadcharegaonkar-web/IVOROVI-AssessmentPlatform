-- LOCAL DEVELOPMENT FIXTURE (dev profile only; entirely fictitious).
-- Aurora: University → 2 colleges (moved from top level); AIT has a campus with departments; ACC has a department
-- directly under the college (no campus level), demonstrating optional levels. Borealis: a department.
CREATE FUNCTION pg_temp.lbl(u uuid) RETURNS ltree LANGUAGE sql AS $$ SELECT text2ltree(replace(u::text, '-', '')) $$;

INSERT INTO org_unit (id, tenant_id, parent_id, unit_type, code, name, short_name, path, attributes) VALUES
 ('0192a000-0000-7000-8000-00000000a100', '0192a000-0000-7000-8000-00000000000a', NULL, 'UNIVERSITY', 'AU',
  'Aurora University', 'AU', pg_temp.lbl('0192a000-0000-7000-8000-00000000a100'), '{"city":"Pune","state":"Maharashtra"}');

UPDATE org_unit SET parent_id = '0192a000-0000-7000-8000-00000000a100',
       path = pg_temp.lbl('0192a000-0000-7000-8000-00000000a100') || pg_temp.lbl(id)
WHERE id IN ('0192a000-0000-7000-8000-00000000a101', '0192a000-0000-7000-8000-00000000a102');

INSERT INTO org_unit (id, tenant_id, parent_id, unit_type, code, name, path) VALUES
 ('0192a000-0000-7000-8000-00000000a111', '0192a000-0000-7000-8000-00000000000a', '0192a000-0000-7000-8000-00000000a101', 'CAMPUS', 'NORTH',
  'AIT North Campus', pg_temp.lbl('0192a000-0000-7000-8000-00000000a100') || pg_temp.lbl('0192a000-0000-7000-8000-00000000a101') || pg_temp.lbl('0192a000-0000-7000-8000-00000000a111'));
INSERT INTO org_unit (id, tenant_id, parent_id, unit_type, code, name, path) VALUES
 ('0192a000-0000-7000-8000-00000000a121', '0192a000-0000-7000-8000-00000000000a', '0192a000-0000-7000-8000-00000000a111', 'DEPARTMENT', 'CSE',
  'Computer Science & Engineering', (SELECT path FROM org_unit WHERE id = '0192a000-0000-7000-8000-00000000a111') || pg_temp.lbl('0192a000-0000-7000-8000-00000000a121')),
 ('0192a000-0000-7000-8000-00000000a122', '0192a000-0000-7000-8000-00000000000a', '0192a000-0000-7000-8000-00000000a111', 'DEPARTMENT', 'MECH',
  'Mechanical Engineering', (SELECT path FROM org_unit WHERE id = '0192a000-0000-7000-8000-00000000a111') || pg_temp.lbl('0192a000-0000-7000-8000-00000000a122')),
 ('0192a000-0000-7000-8000-00000000a131', '0192a000-0000-7000-8000-00000000000a', '0192a000-0000-7000-8000-00000000a102', 'DEPARTMENT', 'COM',
  'Department of Commerce', (SELECT path FROM org_unit WHERE id = '0192a000-0000-7000-8000-00000000a102') || pg_temp.lbl('0192a000-0000-7000-8000-00000000a131')),
 ('0192b000-0000-7000-8000-00000000b121', '0192b000-0000-7000-8000-00000000000b', '0192b000-0000-7000-8000-00000000b101', 'DEPARTMENT', 'PHY',
  'Department of Physics', (SELECT path FROM org_unit WHERE id = '0192b000-0000-7000-8000-00000000b101') || pg_temp.lbl('0192b000-0000-7000-8000-00000000b121'));

INSERT INTO org_unit_history (id, tenant_id, org_unit_id, change_type, reason, after)
SELECT gen_random_uuid(), tenant_id, id, 'CREATED', 'Development fixture', jsonb_build_object('type', unit_type, 'code', code, 'name', name)
FROM org_unit WHERE id IN ('0192a000-0000-7000-8000-00000000a100', '0192a000-0000-7000-8000-00000000a111',
  '0192a000-0000-7000-8000-00000000a121', '0192a000-0000-7000-8000-00000000a122', '0192a000-0000-7000-8000-00000000a131',
  '0192b000-0000-7000-8000-00000000b121');
