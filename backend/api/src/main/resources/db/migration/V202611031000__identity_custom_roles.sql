-- Phase 1, increment 1.3: custom roles and permission management.
-- System roles (tenant_id IS NULL) stay immutable and always active; custom roles belong to one organization.

ALTER TABLE role
    ADD COLUMN status            varchar(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    -- a custom role limited to one unit's subtree (created by a unit administrator); NULL = whole organization
    ADD COLUMN scope_org_unit_id uuid,
    ADD COLUMN cloned_from       uuid REFERENCES role (id),
    ADD COLUMN archived_at       timestamptz,
    ADD COLUMN created_by        uuid,
    ADD COLUMN updated_by        uuid,
    ADD COLUMN status_reason     varchar(500);
ALTER TABLE role ADD CONSTRAINT role_scope_unit_fk FOREIGN KEY (tenant_id, scope_org_unit_id) REFERENCES org_unit (tenant_id, id);
ALTER TABLE role ADD CONSTRAINT role_system_fixed
    CHECK (NOT system_role OR (scope_org_unit_id IS NULL AND status = 'ACTIVE' AND archived_at IS NULL));
-- custom roles never carry platform permissions (defence in depth for the service rule)
ALTER TABLE role ADD CONSTRAINT role_custom_not_platform CHECK (system_role OR NOT ('platform.tenant.manage' = ANY (permissions)));
CREATE INDEX role_tenant_list_idx ON role (tenant_id, status) WHERE archived_at IS NULL;

-- Unit administrators may define roles for their own subtree (the ceiling rule limits what they can put in them).
UPDATE role SET permissions = permissions || ARRAY['role.manage']
    WHERE system_role AND code = 'INSTITUTION_ADMIN' AND NOT ('role.manage' = ANY (permissions));
