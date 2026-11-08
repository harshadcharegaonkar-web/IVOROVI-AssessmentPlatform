-- LOCAL DEVELOPMENT FIXTURE (dev profile only; fictitious). A second, organization-wide examination controller for
-- Aurora, so four-eyes steps (e.g. candidate compensation) can be demonstrated by two different people.
-- The id matches the user in deploy/compose/keycloak/realm-ivorovi.json.
INSERT INTO user_account (id, tenant_id, external_subject, email, display_name) VALUES
 ('0192a000-0000-7000-8000-0000000a10e1', '0192a000-0000-7000-8000-00000000000a', '0192a000-0000-7000-8000-0000000a00e1', 'eshan.controller@aurora.test', 'Eshan Controller');
INSERT INTO role_assignment (id, tenant_id, user_account_id, role_id, scope_type, scope_id) VALUES
 ('0192a000-0000-7000-8000-0000000a20e1', '0192a000-0000-7000-8000-00000000000a', '0192a000-0000-7000-8000-0000000a10e1', '01920000-0000-7000-8000-000000000006', 'TENANT', '0192a000-0000-7000-8000-00000000000a');
