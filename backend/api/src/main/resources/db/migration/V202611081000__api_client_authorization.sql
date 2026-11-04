-- KI-59 (increment 1.7 closure): API clients never keep a permission their authority has lost.
-- Delegated clients act for an owner whose CURRENT permissions are re-checked on every request; service identities are
-- independently governed non-human principals (read-only, approved scope envelope, business + technical custodians).
-- Additive only: no Phase 1 table changes beyond new columns on the 1.7 api_client table.

-- ---------------------------------------------------------------------------------------------------------
-- Service identities
-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE service_identity (
    id                       uuid PRIMARY KEY,
    tenant_id                uuid          NOT NULL REFERENCES tenant (id),
    name                     varchar(100)  NOT NULL CHECK (length(btrim(name)) > 0),
    purpose                  varchar(500)  NOT NULL CHECK (length(btrim(purpose)) > 0),
    org_unit_id              uuid,
    business_custodian_id    uuid          NOT NULL,
    technical_custodian_id   uuid          NOT NULL,
    -- approved ceiling of everything the identity may ever exercise (empty until independently approved)
    scope_envelope           text[]        NOT NULL DEFAULT '{}',
    proposed_envelope        text[],
    proposed_by              uuid,
    proposed_at              timestamptz,
    approved_by              uuid,
    approved_at              timestamptz,
    status                   varchar(20)   NOT NULL DEFAULT 'PENDING_APPROVAL'
                             CHECK (status IN ('PENDING_APPROVAL', 'ACTIVE', 'SUSPENDED', 'DISABLED')),
    status_reason            varchar(40),
    custodian_invalid_since  timestamptz,
    created_by               uuid          NOT NULL,
    created_at               timestamptz   NOT NULL DEFAULT now(),
    updated_at               timestamptz   NOT NULL DEFAULT now(),
    version                  bigint        NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, org_unit_id) REFERENCES org_unit (tenant_id, id),
    FOREIGN KEY (tenant_id, business_custodian_id) REFERENCES user_account (tenant_id, id),
    FOREIGN KEY (tenant_id, technical_custodian_id) REFERENCES user_account (tenant_id, id)
);
-- Independent approval (approver ≠ proposer) is enforced by the service and recorded in the audit trail.
CREATE UNIQUE INDEX service_identity_name_uq ON service_identity (tenant_id, lower(name));
CREATE INDEX service_identity_custodian_idx ON service_identity (tenant_id, business_custodian_id, technical_custodian_id);

-- Role assignments of service identities: the same roles and ceiling rule as for people, kept apart from people's
-- assignments so Phase 1 user screens, counts and sign-in are untouched.
CREATE TABLE service_role_assignment (
    id                   uuid PRIMARY KEY,
    tenant_id            uuid          NOT NULL REFERENCES tenant (id),
    service_identity_id  uuid          NOT NULL,
    role_id              uuid          NOT NULL REFERENCES role (id),
    scope_type           varchar(20)   NOT NULL CHECK (scope_type IN ('TENANT', 'ORG_UNIT')),
    scope_id             uuid          NOT NULL,
    created_by           uuid          NOT NULL,
    created_at           timestamptz   NOT NULL DEFAULT now(),
    revoked_by           uuid,
    revoked_at           timestamptz,
    FOREIGN KEY (tenant_id, service_identity_id) REFERENCES service_identity (tenant_id, id)
);
CREATE INDEX service_role_assignment_idx ON service_role_assignment (tenant_id, service_identity_id) WHERE revoked_at IS NULL;

-- ---------------------------------------------------------------------------------------------------------
-- API clients: owner (delegated) or service identity; suspension
-- ---------------------------------------------------------------------------------------------------------
ALTER TABLE api_client ADD COLUMN kind varchar(20) NOT NULL DEFAULT 'DELEGATED' CHECK (kind IN ('DELEGATED', 'SERVICE'));
ALTER TABLE api_client ADD COLUMN owner_user_id uuid;
ALTER TABLE api_client ADD COLUMN service_identity_id uuid;
ALTER TABLE api_client ADD COLUMN suspended_reason varchar(40);
ALTER TABLE api_client ADD COLUMN suspended_at timestamptz;
UPDATE api_client SET owner_user_id = created_by WHERE owner_user_id IS NULL;
ALTER TABLE api_client DROP CONSTRAINT api_client_status_check;
ALTER TABLE api_client ADD CONSTRAINT api_client_status_check CHECK (status IN ('ACTIVE', 'SUSPENDED', 'REVOKED'));
ALTER TABLE api_client ADD CONSTRAINT api_client_owner_fk FOREIGN KEY (tenant_id, owner_user_id) REFERENCES user_account (tenant_id, id);
ALTER TABLE api_client ADD CONSTRAINT api_client_service_fk FOREIGN KEY (tenant_id, service_identity_id) REFERENCES service_identity (tenant_id, id);
ALTER TABLE api_client ADD CONSTRAINT api_client_principal_check CHECK (
    (kind = 'DELEGATED' AND owner_user_id IS NOT NULL AND service_identity_id IS NULL)
    OR (kind = 'SERVICE' AND service_identity_id IS NOT NULL AND owner_user_id IS NULL));
CREATE INDEX api_client_owner_idx ON api_client (tenant_id, owner_user_id) WHERE kind = 'DELEGATED';

-- One audit record per client, scope and day when a scope stops working (keeps retries from flooding the trail).
CREATE TABLE api_client_scope_loss (
    tenant_id  uuid        NOT NULL REFERENCES tenant (id),
    client_id  uuid        NOT NULL,
    scope      varchar(60) NOT NULL,
    day        date        NOT NULL,
    reason     varchar(40) NOT NULL,
    PRIMARY KEY (tenant_id, client_id, scope, day),
    FOREIGN KEY (tenant_id, client_id) REFERENCES api_client (tenant_id, id)
);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['service_identity', 'service_role_assignment', 'api_client_scope_loss'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id())', t);
    END LOOP;
END $$;
