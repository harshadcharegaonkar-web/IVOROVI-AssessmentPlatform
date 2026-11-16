-- LOCAL DEVELOPMENT AND CI FIXTURE (dev profile only; fictitious). Demo programme, mandatory module I: a second
-- organization administrator in Borealis, so the security-policy E2E test can show four-eyes approval of a weaker policy
-- (requester and approver must be different administrators) and return the policy to its baseline. Borealis is otherwise
-- only the "other organization" of the isolation tests, which this account does not affect. The ids match the user in
-- deploy/compose/keycloak/realm-ivorovi.json.
INSERT INTO user_account (id, tenant_id, external_subject, email, display_name) VALUES
 ('0192b000-0000-7000-8000-0000000b1002', '0192b000-0000-7000-8000-00000000000b', '0192b000-0000-7000-8000-0000000b0002', 'bruno.admin@borealis.test', 'Bruno Admin');
INSERT INTO role_assignment (id, tenant_id, user_account_id, role_id, scope_type, scope_id) VALUES
 ('0192b000-0000-7000-8000-0000000b2002', '0192b000-0000-7000-8000-00000000000b', '0192b000-0000-7000-8000-0000000b1002', '01920000-0000-7000-8000-000000000002', 'TENANT', '0192b000-0000-7000-8000-00000000000b');
INSERT INTO org_assignment (id, tenant_id, user_account_id, org_unit_id, is_primary) VALUES
 ('0192b000-0000-7000-8000-0000000b3002', '0192b000-0000-7000-8000-00000000000b', '0192b000-0000-7000-8000-0000000b1002', NULL, true);
