-- Decision Q-01 (2026-11-04): a service identity's business and technical custodians must be two different people.
-- Existing identities that name one person twice are disabled first (they cannot be operated under the new rule and are
-- kept for audit), then the rule is enforced for every new or changed row.
UPDATE service_identity SET status = 'DISABLED', status_reason = 'custodian_conflict', updated_at = now(), version = version + 1
WHERE business_custodian_id = technical_custodian_id AND status <> 'DISABLED';
UPDATE api_client SET status = 'REVOKED', revoked_at = now(), updated_at = now(), version = version + 1
WHERE service_identity_id IN (SELECT id FROM service_identity WHERE status_reason = 'custodian_conflict') AND status <> 'REVOKED';
ALTER TABLE service_identity ADD CONSTRAINT service_identity_distinct_custodians
    CHECK (business_custodian_id <> technical_custodian_id OR status = 'DISABLED');
