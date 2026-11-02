-- TEST-ONLY fixture additions on top of db/devdata (synthetic).
-- Suspended tenant with an otherwise valid admin.
INSERT INTO tenant (id, code, name, status) VALUES
 ('0192c000-0000-7000-8000-00000000000c', 'cirrus', 'Cirrus College (synthetic, suspended)', 'SUSPENDED');
INSERT INTO user_account (id, tenant_id, external_subject, email, display_name) VALUES
 ('0192c000-0000-7000-8000-0000000c1001', '0192c000-0000-7000-8000-00000000000c', 'sub-cirrus-admin', 'admin@cirrus.test', 'Cirrus Admin');
INSERT INTO role_assignment (id, tenant_id, user_account_id, role_id, scope_type, scope_id) VALUES
 ('0192c000-0000-7000-8000-0000000c2001', '0192c000-0000-7000-8000-00000000000c', '0192c000-0000-7000-8000-0000000c1001', '01920000-0000-7000-8000-000000000002', 'TENANT', '0192c000-0000-7000-8000-00000000000c');

-- Tenant A: a former admin whose ORG_ADMIN assignment has expired, and a suspended account.
INSERT INTO user_account (id, tenant_id, external_subject, email, display_name, status) VALUES
 ('0192a000-0000-7000-8000-0000000a1005', '0192a000-0000-7000-8000-00000000000a', 'sub-aurora-expired', 'expired.admin@aurora.test', 'Expired Admin', 'ACTIVE'),
 ('0192a000-0000-7000-8000-0000000a1006', '0192a000-0000-7000-8000-00000000000a', 'sub-aurora-suspended', 'suspended.user@aurora.test', 'Suspended User', 'SUSPENDED');
INSERT INTO role_assignment (id, tenant_id, user_account_id, role_id, scope_type, scope_id, valid_from, valid_to) VALUES
 ('0192a000-0000-7000-8000-0000000a2005', '0192a000-0000-7000-8000-00000000000a', '0192a000-0000-7000-8000-0000000a1005', '01920000-0000-7000-8000-000000000002', 'TENANT', '0192a000-0000-7000-8000-00000000000a', now() - interval '30 days', now() - interval '1 day'),
 ('0192a000-0000-7000-8000-0000000a2006', '0192a000-0000-7000-8000-00000000000a', '0192a000-0000-7000-8000-0000000a1006', '01920000-0000-7000-8000-000000000002', 'TENANT', '0192a000-0000-7000-8000-00000000000a', now() - interval '30 days', null);
