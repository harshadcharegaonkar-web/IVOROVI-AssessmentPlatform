-- Phase 1, increment 1.2: user and staff management, invitations, organizational and role assignments.
-- Existing accounts (Phase 0 / 1.1) keep their identifiers, subjects, status and grants.

-- ---------------------------------------------------------------------------------------------------------
-- User accounts: profile fields, lifecycle timestamps; external subject is absent until the invitation is
-- accepted and the identity-provider account exists.
-- ---------------------------------------------------------------------------------------------------------
ALTER TABLE user_account
    ADD COLUMN given_name    varchar(100),
    ADD COLUMN family_name   varchar(100),
    ADD COLUMN phone         varchar(30),
    ADD COLUMN status_reason varchar(500),
    ADD COLUMN status_changed_at timestamptz,
    ADD COLUMN status_changed_by uuid;
ALTER TABLE user_account ALTER COLUMN external_subject DROP NOT NULL;
ALTER TABLE user_account DROP CONSTRAINT user_account_tenant_id_external_subject_key;
CREATE UNIQUE INDEX user_account_subject_uq ON user_account (tenant_id, external_subject) WHERE external_subject IS NOT NULL;
-- Email addresses are stored normalised (trimmed, lower case) and are unique within an organization.
ALTER TABLE user_account ADD CONSTRAINT user_account_email_normalised CHECK (email = lower(btrim(email)) AND email LIKE '%_@_%');
-- uniqueness per organization: existing index user_account_email_uq on (tenant_id, lower(email)) (Phase 0)
-- An active account always has an identity-provider subject; an invited one may not have it yet.
ALTER TABLE user_account ADD CONSTRAINT user_account_subject_when_active
    CHECK (status IN ('INVITED', 'DEACTIVATED') OR external_subject IS NOT NULL);
-- Split existing display names into given / family name where possible (display name is kept).
UPDATE user_account SET given_name = split_part(display_name, ' ', 1),
                        family_name = NULLIF(substr(display_name, length(split_part(display_name, ' ', 1)) + 2), '')
WHERE given_name IS NULL;

-- ---------------------------------------------------------------------------------------------------------
-- Invitations: single-use, expiring. Only a SHA-256 hash of the token is stored; the token itself exists only
-- in the email that is sent.
-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE user_invitation (
    id              uuid PRIMARY KEY,
    tenant_id       uuid         NOT NULL REFERENCES tenant (id),
    user_account_id uuid         NOT NULL,
    email           varchar(320) NOT NULL,
    token_hash      char(64)     NOT NULL UNIQUE,
    status          varchar(20)  NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'ACCEPTED', 'REVOKED', 'EXPIRED')),
    expires_at      timestamptz  NOT NULL,
    send_count      int          NOT NULL DEFAULT 1,
    last_sent_at    timestamptz  NOT NULL DEFAULT now(),
    delivery_status varchar(20)  NOT NULL DEFAULT 'QUEUED' CHECK (delivery_status IN ('QUEUED', 'SENT', 'FAILED')),
    created_at      timestamptz  NOT NULL DEFAULT now(),
    created_by      uuid,
    accepted_at     timestamptz,
    revoked_at      timestamptz,
    revoked_by      uuid,
    version         bigint       NOT NULL DEFAULT 0,
    FOREIGN KEY (tenant_id, user_account_id) REFERENCES user_account (tenant_id, id)
);
-- at most one open invitation per account
CREATE UNIQUE INDEX user_invitation_open_uq ON user_invitation (tenant_id, user_account_id) WHERE status = 'PENDING';
CREATE INDEX user_invitation_list_idx ON user_invitation (tenant_id, status, created_at DESC);
ALTER TABLE user_invitation ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON user_invitation
    USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());

-- The acceptance page is public: it knows only the token. This function maps a token hash to its tenant (and
-- nothing else) so the request can then run inside that tenant's RLS context.
CREATE FUNCTION invitation_tenant(p_token_hash char(64)) RETURNS uuid
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, ${flyway:defaultSchema} AS
$$ SELECT tenant_id FROM ${flyway:defaultSchema}.user_invitation WHERE token_hash = p_token_hash $$;
REVOKE ALL ON FUNCTION invitation_tenant(char) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION invitation_tenant(char) TO ${appRole};

-- ---------------------------------------------------------------------------------------------------------
-- Staff profiles: independent of login accounts; optionally linked to one.
-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE staff_profile (
    id                uuid PRIMARY KEY,
    tenant_id         uuid         NOT NULL REFERENCES tenant (id),
    user_account_id   uuid,
    staff_no          varchar(50)  NOT NULL CHECK (staff_no ~ '^[A-Za-z0-9][A-Za-z0-9/_.-]{0,49}$'),
    given_name        varchar(100) NOT NULL CHECK (length(btrim(given_name)) > 0),
    family_name       varchar(100),
    email             varchar(320) CHECK (email IS NULL OR (email = lower(btrim(email)) AND email LIKE '%_@_%')),
    phone             varchar(30),
    category          varchar(30)  NOT NULL CHECK (category IN ('FACULTY', 'DEPARTMENT_ADMIN', 'ACADEMIC_ADMIN', 'EVALUATOR',
                          'EXAM_CONTROLLER', 'PROCTOR', 'ADMISSIONS_OFFICER', 'PLACEMENT_OFFICER', 'ADMINISTRATIVE_STAFF')),
    designation       varchar(100),
    employment_status varchar(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (employment_status IN ('ACTIVE', 'ON_LEAVE', 'SEPARATED')),
    employment_type   varchar(20)  CHECK (employment_type IN ('PERMANENT', 'CONTRACT', 'VISITING', 'ADJUNCT')),
    joined_on         date,
    created_at        timestamptz  NOT NULL DEFAULT now(),
    created_by        uuid,
    updated_at        timestamptz  NOT NULL DEFAULT now(),
    updated_by        uuid,
    version           bigint       NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, user_account_id) REFERENCES user_account (tenant_id, id)
);
CREATE UNIQUE INDEX staff_profile_no_uq ON staff_profile (tenant_id, lower(staff_no));
CREATE UNIQUE INDEX staff_profile_user_uq ON staff_profile (tenant_id, user_account_id) WHERE user_account_id IS NOT NULL;
CREATE INDEX staff_profile_list_idx ON staff_profile (tenant_id, category, employment_status);
ALTER TABLE staff_profile ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON staff_profile
    USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());

-- ---------------------------------------------------------------------------------------------------------
-- Organizational assignments (membership, not permissions): a user or a staff profile belongs to the
-- organization as a whole (org_unit_id NULL) or to units. Permissions come only from role assignments.
-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE org_assignment (
    id               uuid PRIMARY KEY,
    tenant_id        uuid         NOT NULL REFERENCES tenant (id),
    user_account_id  uuid,
    staff_profile_id uuid,
    org_unit_id      uuid,
    is_primary       boolean      NOT NULL DEFAULT false,
    title            varchar(100),
    starts_on        date,
    ends_on          date,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    created_by       uuid,
    CHECK (num_nonnulls(user_account_id, staff_profile_id) = 1),
    CHECK (ends_on IS NULL OR starts_on IS NULL OR ends_on >= starts_on),
    FOREIGN KEY (tenant_id, user_account_id) REFERENCES user_account (tenant_id, id),
    FOREIGN KEY (tenant_id, staff_profile_id) REFERENCES staff_profile (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, org_unit_id) REFERENCES org_unit (tenant_id, id)
);
CREATE UNIQUE INDEX org_assignment_user_unit_uq ON org_assignment
    (tenant_id, user_account_id, coalesce(org_unit_id, '00000000-0000-0000-0000-000000000000'::uuid)) WHERE user_account_id IS NOT NULL;
CREATE UNIQUE INDEX org_assignment_staff_unit_uq ON org_assignment
    (tenant_id, staff_profile_id, coalesce(org_unit_id, '00000000-0000-0000-0000-000000000000'::uuid)) WHERE staff_profile_id IS NOT NULL;
CREATE UNIQUE INDEX org_assignment_user_primary_uq ON org_assignment (tenant_id, user_account_id) WHERE is_primary AND user_account_id IS NOT NULL;
CREATE UNIQUE INDEX org_assignment_staff_primary_uq ON org_assignment (tenant_id, staff_profile_id) WHERE is_primary AND staff_profile_id IS NOT NULL;
CREATE INDEX org_assignment_unit_idx ON org_assignment (tenant_id, org_unit_id);
ALTER TABLE org_assignment ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON org_assignment
    USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());

-- Existing users get an organizational assignment matching their scoped role assignments (or the organization).
INSERT INTO org_assignment (id, tenant_id, user_account_id, org_unit_id, is_primary)
SELECT gen_random_uuid(), x.tenant_id, x.user_account_id, x.org_unit_id, x.rn = 1
FROM (SELECT DISTINCT ON (ra.tenant_id, ra.user_account_id, ra.scope_id)
             ra.tenant_id, ra.user_account_id,
             CASE WHEN ra.scope_type = 'ORG_UNIT' THEN ra.scope_id END AS org_unit_id,
             dense_rank() OVER (PARTITION BY ra.tenant_id, ra.user_account_id ORDER BY ra.scope_type DESC, ra.scope_id) AS rn
      FROM role_assignment ra
      ORDER BY ra.tenant_id, ra.user_account_id, ra.scope_id) x;
INSERT INTO org_assignment (id, tenant_id, user_account_id, org_unit_id, is_primary)
SELECT gen_random_uuid(), u.tenant_id, u.id, NULL, true FROM user_account u
WHERE NOT EXISTS (SELECT 1 FROM org_assignment a WHERE a.user_account_id = u.id);

-- ---------------------------------------------------------------------------------------------------------
-- Role assignments: soft revocation keeps the history; who granted it is recorded.
-- ---------------------------------------------------------------------------------------------------------
ALTER TABLE role_assignment
    ADD COLUMN revoked_at        timestamptz,
    ADD COLUMN revoked_by        uuid,
    ADD COLUMN revocation_reason varchar(500);
CREATE INDEX role_assignment_active_idx ON role_assignment (tenant_id, user_account_id) WHERE revoked_at IS NULL;

-- ---------------------------------------------------------------------------------------------------------
-- Permissions: role.read / role.assign (assignment of system roles; custom roles stay with role.manage, 1.3),
-- staff.read / staff.manage.
-- ---------------------------------------------------------------------------------------------------------
UPDATE role SET permissions = permissions || ARRAY['role.read', 'role.assign', 'staff.read', 'staff.manage']
    WHERE system_role AND code IN ('ORG_ADMIN', 'INSTITUTION_ADMIN');
UPDATE role SET permissions = permissions || ARRAY['staff.read']
    WHERE system_role AND code IN ('CAMPUS_ADMIN', 'DEPARTMENT_HEAD', 'MANAGEMENT');
UPDATE role SET permissions = permissions || ARRAY['user.read']
    WHERE system_role AND code IN ('DEPARTMENT_HEAD') AND NOT ('user.read' = ANY (permissions));
