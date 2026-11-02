-- Ivorovi platform core schema (Phase 0).
-- Executed by Flyway as ivorovi_owner. The application connects as ivorovi_app, which is subject to RLS.
-- Every tenant-owned table: tenant_id NOT NULL + RLS policy bound to the per-connection setting app.tenant_id.

-- ---------------------------------------------------------------------------------------------------------
-- RLS helpers. NULLIF makes a missing/empty setting resolve to NULL, so "no tenant context" matches no rows.
-- ---------------------------------------------------------------------------------------------------------
CREATE FUNCTION current_tenant_id() RETURNS uuid
    LANGUAGE sql STABLE AS $$ SELECT NULLIF(current_setting('app.tenant_id', true), '')::uuid $$;

CREATE FUNCTION is_platform_context() RETURNS boolean
    LANGUAGE sql STABLE AS $$ SELECT coalesce(current_setting('app.platform_context', true), '') = 'on' $$;

-- ---------------------------------------------------------------------------------------------------------
-- Tenancy
-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE tenant (
    id          uuid PRIMARY KEY,
    code        varchar(63)  NOT NULL UNIQUE CHECK (code ~ '^[a-z0-9][a-z0-9-]{1,62}$'),
    name        varchar(200) NOT NULL,
    status      varchar(20)  NOT NULL CHECK (status IN ('TRIAL', 'ACTIVE', 'SUSPENDED', 'TERMINATED')),
    data_region varchar(32)  NOT NULL DEFAULT 'IN',
    branding    jsonb        NOT NULL DEFAULT '{}'::jsonb,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    version     bigint       NOT NULL DEFAULT 0
);
ALTER TABLE tenant ENABLE ROW LEVEL SECURITY;
-- A tenant sees only itself. Platform jobs (tenant resolution, provisioning) run in platform context.
CREATE POLICY tenant_isolation ON tenant
    USING (id = current_tenant_id() OR is_platform_context())
    WITH CHECK (is_platform_context());

CREATE TABLE institution (
    id          uuid PRIMARY KEY,
    tenant_id   uuid         NOT NULL REFERENCES tenant (id),
    code        varchar(63)  NOT NULL CHECK (code ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,62}$'),
    name        varchar(200) NOT NULL,
    status      varchar(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at  timestamptz  NOT NULL DEFAULT now(),
    created_by  uuid,
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    updated_by  uuid,
    version     bigint       NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, code),
    UNIQUE (tenant_id, id)
);
ALTER TABLE institution ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON institution
    USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());

-- ---------------------------------------------------------------------------------------------------------
-- Identity & access
-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE user_account (
    id               uuid PRIMARY KEY,
    tenant_id        uuid         NOT NULL REFERENCES tenant (id),
    external_subject varchar(255) NOT NULL,
    email            varchar(320) NOT NULL,
    display_name     varchar(200) NOT NULL,
    status           varchar(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('INVITED', 'ACTIVE', 'SUSPENDED', 'DEACTIVATED')),
    last_login_at    timestamptz,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    created_by       uuid,
    updated_at       timestamptz  NOT NULL DEFAULT now(),
    updated_by       uuid,
    version          bigint       NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, external_subject),
    UNIQUE (tenant_id, id)
);
CREATE UNIQUE INDEX user_account_email_uq ON user_account (tenant_id, lower(email));
ALTER TABLE user_account ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON user_account
    USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());

-- System roles have tenant_id NULL (shared, read-only for tenants). Custom roles belong to one tenant.
CREATE TABLE role (
    id          uuid PRIMARY KEY,
    tenant_id   uuid REFERENCES tenant (id),
    code        varchar(63)  NOT NULL CHECK (code ~ '^[A-Z][A-Z0-9_]{1,62}$'),
    name        varchar(200) NOT NULL,
    description varchar(1000),
    system_role boolean      NOT NULL DEFAULT false,
    permissions text[]       NOT NULL DEFAULT '{}',
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    version     bigint       NOT NULL DEFAULT 0,
    CHECK (system_role = (tenant_id IS NULL))
);
CREATE UNIQUE INDEX role_code_uq ON role (coalesce(tenant_id, '00000000-0000-0000-0000-000000000000'::uuid), code);
ALTER TABLE role ENABLE ROW LEVEL SECURITY;
CREATE POLICY role_read ON role FOR SELECT USING (tenant_id IS NULL OR tenant_id = current_tenant_id());
CREATE POLICY role_write ON role FOR INSERT WITH CHECK (tenant_id = current_tenant_id());
CREATE POLICY role_update ON role FOR UPDATE USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());
CREATE POLICY role_delete ON role FOR DELETE USING (tenant_id = current_tenant_id());

CREATE TABLE role_assignment (
    id              uuid PRIMARY KEY,
    tenant_id       uuid        NOT NULL REFERENCES tenant (id),
    user_account_id uuid        NOT NULL,
    role_id         uuid        NOT NULL REFERENCES role (id),
    scope_type      varchar(20) NOT NULL CHECK (scope_type IN ('TENANT', 'INSTITUTION')),
    scope_id        uuid        NOT NULL,
    valid_from      timestamptz NOT NULL DEFAULT now(),
    valid_to        timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    created_by      uuid,
    FOREIGN KEY (tenant_id, user_account_id) REFERENCES user_account (tenant_id, id),
    CHECK (valid_to IS NULL OR valid_to > valid_from),
    CHECK (scope_type <> 'TENANT' OR scope_id = tenant_id)
);
CREATE INDEX role_assignment_user_idx ON role_assignment (tenant_id, user_account_id);
ALTER TABLE role_assignment ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON role_assignment
    USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());

-- ---------------------------------------------------------------------------------------------------------
-- Feature flags: catalog + defaults live in code (FeatureFlag enum); only overrides are stored.
-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE feature_flag_override (
    id             uuid PRIMARY KEY,
    tenant_id      uuid         NOT NULL REFERENCES tenant (id),
    institution_id uuid,
    flag_key       varchar(100) NOT NULL,
    enabled        boolean      NOT NULL,
    updated_at     timestamptz  NOT NULL DEFAULT now(),
    updated_by     uuid,
    version        bigint       NOT NULL DEFAULT 0,
    FOREIGN KEY (tenant_id, institution_id) REFERENCES institution (tenant_id, id),
    UNIQUE NULLS NOT DISTINCT (tenant_id, institution_id, flag_key)
);
ALTER TABLE feature_flag_override ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON feature_flag_override
    USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());

-- ---------------------------------------------------------------------------------------------------------
-- Audit: append-only, hash-chained per tenant. The app role may INSERT and SELECT only.
-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE audit_event (
    id            uuid PRIMARY KEY,
    tenant_id     uuid         NOT NULL REFERENCES tenant (id),
    seq           bigint       NOT NULL,
    occurred_at   timestamptz  NOT NULL,
    actor_id      uuid,
    actor_subject varchar(255),
    action        varchar(100) NOT NULL,
    resource_type varchar(100),
    resource_id   varchar(100),
    outcome       varchar(20)  NOT NULL CHECK (outcome IN ('SUCCESS', 'DENIED', 'FAILURE')),
    details       jsonb        NOT NULL DEFAULT '{}'::jsonb,
    client_ip     varchar(64),
    user_agent    varchar(512),
    trace_id      varchar(64),
    prev_hash     char(64)     NOT NULL,
    hash          char(64)     NOT NULL,
    UNIQUE (tenant_id, seq)
);
CREATE INDEX audit_event_query_idx ON audit_event (tenant_id, occurred_at DESC);
CREATE INDEX audit_event_action_idx ON audit_event (tenant_id, action, occurred_at DESC);
ALTER TABLE audit_event ENABLE ROW LEVEL SECURITY;
CREATE POLICY audit_read ON audit_event FOR SELECT USING (tenant_id = current_tenant_id());
CREATE POLICY audit_insert ON audit_event FOR INSERT WITH CHECK (tenant_id = current_tenant_id());
REVOKE UPDATE, DELETE, TRUNCATE ON audit_event FROM ${appRole};

-- ---------------------------------------------------------------------------------------------------------
-- Transactional outbox. Rows are written in the business transaction (tenant context) and published by a
-- platform job (platform context).
-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE outbox_event (
    id             uuid PRIMARY KEY,
    tenant_id      uuid REFERENCES tenant (id),
    aggregate_type varchar(100) NOT NULL,
    aggregate_id   varchar(100) NOT NULL,
    event_type     varchar(150) NOT NULL,
    payload        jsonb        NOT NULL,
    created_at     timestamptz  NOT NULL DEFAULT now(),
    available_at   timestamptz  NOT NULL DEFAULT now(),
    attempts       integer      NOT NULL DEFAULT 0,
    last_error     varchar(2000),
    published_at   timestamptz
);
CREATE INDEX outbox_pending_idx ON outbox_event (available_at) WHERE published_at IS NULL;
ALTER TABLE outbox_event ENABLE ROW LEVEL SECURITY;
CREATE POLICY outbox_access ON outbox_event
    USING (is_platform_context() OR tenant_id = current_tenant_id())
    WITH CHECK (is_platform_context() OR tenant_id = current_tenant_id());
REVOKE DELETE, TRUNCATE ON outbox_event FROM ${appRole};
