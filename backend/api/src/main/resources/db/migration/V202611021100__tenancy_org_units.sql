-- Phase 1, increment 1.1: organization hierarchy (University → College → Campus → Department).
-- Generalises Phase 0 "institution" into "org_unit". Existing rows keep their UUIDs, so role assignments and
-- feature overrides continue to point at the same records.

CREATE EXTENSION IF NOT EXISTS ltree;

CREATE TABLE org_unit (
    id          uuid PRIMARY KEY,
    tenant_id   uuid         NOT NULL REFERENCES tenant (id),
    parent_id   uuid,
    unit_type   varchar(20)  NOT NULL CHECK (unit_type IN ('UNIVERSITY', 'COLLEGE', 'CAMPUS', 'DEPARTMENT')),
    code        varchar(63)  NOT NULL CHECK (code ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,62}$'),
    name        varchar(200) NOT NULL CHECK (length(btrim(name)) > 0),
    short_name  varchar(50),
    status      varchar(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    -- Materialised path of hex UUID labels (root.….self). Maintained by the service in the same transaction.
    path        ltree        NOT NULL,
    attributes  jsonb        NOT NULL DEFAULT '{}'::jsonb,
    branding    jsonb        NOT NULL DEFAULT '{}'::jsonb,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    created_by  uuid,
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    updated_by  uuid,
    version     bigint       NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    -- parent must belong to the same tenant
    FOREIGN KEY (tenant_id, parent_id) REFERENCES org_unit (tenant_id, id),
    CHECK (parent_id IS NULL OR parent_id <> id),
    -- only universities and colleges may be top-level
    CHECK (parent_id IS NOT NULL OR unit_type IN ('UNIVERSITY', 'COLLEGE'))
);
-- Codes are unique among siblings (top-level units: within the organization), case-insensitively.
CREATE UNIQUE INDEX org_unit_sibling_code_uq
    ON org_unit (tenant_id, coalesce(parent_id, '00000000-0000-0000-0000-000000000000'::uuid), lower(code));
CREATE INDEX org_unit_path_gist ON org_unit USING gist (path);
CREATE INDEX org_unit_parent_idx ON org_unit (tenant_id, parent_id);
CREATE INDEX org_unit_type_idx ON org_unit (tenant_id, unit_type, status);
ALTER TABLE org_unit ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON org_unit
    USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());

-- Phase 0 institutions become top-level colleges with unchanged identifiers.
INSERT INTO org_unit (id, tenant_id, parent_id, unit_type, code, name, status, path,
                      created_at, created_by, updated_at, updated_by, version)
SELECT id, tenant_id, NULL, 'COLLEGE', code, name, status, text2ltree(replace(id::text, '-', '')),
       created_at, created_by, updated_at, updated_by, version
FROM institution;

-- Feature overrides now reference org units (universities and colleges).
ALTER TABLE feature_flag_override DROP CONSTRAINT feature_flag_override_tenant_id_institution_id_fkey;
ALTER TABLE feature_flag_override RENAME COLUMN institution_id TO org_unit_id;
ALTER TABLE feature_flag_override
    ADD CONSTRAINT feature_flag_override_org_unit_fk FOREIGN KEY (tenant_id, org_unit_id) REFERENCES org_unit (tenant_id, id);

-- Role assignment scopes: INSTITUTION → ORG_UNIT (covers the unit and its subtree).
ALTER TABLE role_assignment DROP CONSTRAINT role_assignment_scope_type_check;
UPDATE role_assignment SET scope_type = 'ORG_UNIT' WHERE scope_type = 'INSTITUTION';
ALTER TABLE role_assignment ADD CONSTRAINT role_assignment_scope_type_check CHECK (scope_type IN ('TENANT', 'ORG_UNIT'));
CREATE INDEX role_assignment_scope_idx ON role_assignment (tenant_id, scope_type, scope_id);

-- Permission keys follow the rename; organisation settings become manageable by organization administrators.
UPDATE role SET permissions = array_replace(array_replace(permissions, 'institution.read', 'org_unit.read'),
                                            'institution.manage', 'org_unit.manage');
UPDATE role SET permissions = permissions || ARRAY['org_unit.manage']
    WHERE system_role AND code = 'INSTITUTION_ADMIN' AND NOT ('org_unit.manage' = ANY (permissions));
UPDATE role SET permissions = permissions || ARRAY['tenant.settings.manage']
    WHERE system_role AND code = 'ORG_ADMIN' AND NOT ('tenant.settings.manage' = ANY (permissions));
UPDATE role SET permissions = permissions || ARRAY['org_unit.read']
    WHERE system_role AND code IN ('EVALUATOR', 'EXAM_PROCTOR') AND NOT ('org_unit.read' = ANY (permissions));

DROP TABLE institution;

-- ---------------------------------------------------------------------------------------------------------
-- Organizational history: append-only snapshots of every change (kept after a unit is deleted).
-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE org_unit_history (
    id          uuid PRIMARY KEY,
    tenant_id   uuid         NOT NULL REFERENCES tenant (id),
    org_unit_id uuid         NOT NULL,
    change_type varchar(20)  NOT NULL CHECK (change_type IN ('CREATED', 'UPDATED', 'MOVED', 'DEACTIVATED', 'ACTIVATED', 'DELETED')),
    changed_at  timestamptz  NOT NULL DEFAULT now(),
    changed_by  uuid,
    reason      varchar(500),
    before      jsonb,
    after       jsonb
);
CREATE INDEX org_unit_history_unit_idx ON org_unit_history (tenant_id, org_unit_id, changed_at DESC);
ALTER TABLE org_unit_history ENABLE ROW LEVEL SECURITY;
CREATE POLICY history_read ON org_unit_history FOR SELECT USING (tenant_id = current_tenant_id());
CREATE POLICY history_insert ON org_unit_history FOR INSERT WITH CHECK (tenant_id = current_tenant_id());
REVOKE UPDATE, DELETE, TRUNCATE ON org_unit_history FROM ${appRole};

-- Initial history entry for migrated units.
INSERT INTO org_unit_history (id, tenant_id, org_unit_id, change_type, changed_at, reason, after)
SELECT gen_random_uuid(), tenant_id, id, 'CREATED', created_at, 'Migrated from Phase 0 institution',
       jsonb_build_object('type', unit_type, 'code', code, 'name', name, 'status', status)
FROM org_unit;

-- ---------------------------------------------------------------------------------------------------------
-- Tenant settings (terminology labels, time zone). Separate from `tenant`, which tenants may not modify.
-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE tenant_settings (
    tenant_id  uuid PRIMARY KEY REFERENCES tenant (id),
    settings   jsonb       NOT NULL DEFAULT '{}'::jsonb,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid,
    version    bigint      NOT NULL DEFAULT 0
);
ALTER TABLE tenant_settings ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant_settings
    USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());
