-- LOCAL DEVELOPMENT AND CI FIXTURE (dev profile only; fictitious). KI-88 (approved 2026-11-16): the staff E2E layout tests
-- for tablet and mobile sign in with their own accounts, one per viewport project, instead of the administrator every
-- other test uses, so the suite's traffic per account stays within what one person generates. The per-user rate limits
-- are unchanged. Minimum permissions: exactly what the screens those tests open require (the web app's route guards, plus
-- student.personal.read, which the import mapping screen needs for the personal e-mail column), in
-- a dedicated custom role (its name must not start with "E2E ": the access tests archive such roles); no privileged permission. The ids match the users in deploy/compose/keycloak/realm-ivorovi.json.
INSERT INTO role (id, tenant_id, code, name, description, permissions) VALUES
 ('0192a000-0000-7000-8000-0000000a40f0', '0192a000-0000-7000-8000-00000000000a', 'LAYOUT_REVIEWER_TEST', 'Layout reviewer (test accounts only)',
  'Development and CI only: the screens the tablet and mobile layout tests open, nothing else.',
  ARRAY['org_unit.read', 'tenant.settings.manage', 'user.read', 'staff.read', 'role.read', 'role.manage', 'academics.read', 'program.manage',
        'batch.manage', 'student.read', 'student.personal.read', 'student.manage', 'student.import', 'report.read', 'integration.read', 'audit.read', 'feature.read']);
INSERT INTO user_account (id, tenant_id, external_subject, email, display_name) VALUES
 ('0192a000-0000-7000-8000-0000000a10f1', '0192a000-0000-7000-8000-00000000000a', '0192a000-0000-7000-8000-0000000a00f1', 'layout.tablet@aurora.test', 'Layout Tablet (E2E)'),
 ('0192a000-0000-7000-8000-0000000a10f2', '0192a000-0000-7000-8000-00000000000a', '0192a000-0000-7000-8000-0000000a00f2', 'layout.mobile@aurora.test', 'Layout Mobile (E2E)');
INSERT INTO role_assignment (id, tenant_id, user_account_id, role_id, scope_type, scope_id) VALUES
 ('0192a000-0000-7000-8000-0000000a20f1', '0192a000-0000-7000-8000-00000000000a', '0192a000-0000-7000-8000-0000000a10f1', '0192a000-0000-7000-8000-0000000a40f0', 'TENANT', '0192a000-0000-7000-8000-00000000000a'),
 ('0192a000-0000-7000-8000-0000000a20f2', '0192a000-0000-7000-8000-00000000000a', '0192a000-0000-7000-8000-0000000a10f2', '0192a000-0000-7000-8000-0000000a40f0', 'TENANT', '0192a000-0000-7000-8000-00000000000a');
INSERT INTO org_assignment (id, tenant_id, user_account_id, org_unit_id, is_primary) VALUES
 ('0192a000-0000-7000-8000-0000000a30f1', '0192a000-0000-7000-8000-00000000000a', '0192a000-0000-7000-8000-0000000a10f1', NULL, true),
 ('0192a000-0000-7000-8000-0000000a30f2', '0192a000-0000-7000-8000-00000000000a', '0192a000-0000-7000-8000-0000000a10f2', NULL, true);
