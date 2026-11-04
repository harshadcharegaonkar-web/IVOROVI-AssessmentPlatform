-- Phase 1, increment 1.7: integration management (provider-independent adapters), API clients with scoped
-- credentials, signed webhooks, and richer in-app notifications. Every table is tenant-owned with RLS. Secrets are
-- never stored in plain text: API key secrets as SHA-256 hashes (shown once), adapter and webhook signing secrets
-- encrypted by the application (AES-GCM, IVOROVI_DATA_KEY).

-- ---------------------------------------------------------------------------------------------------------
-- Integrations (SIS/ERP, LMS, email provider, institutional applications) through built-in, vendor-neutral adapters.
-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE integration (
    id               uuid PRIMARY KEY,
    tenant_id        uuid         NOT NULL REFERENCES tenant (id),
    kind             varchar(20)  NOT NULL CHECK (kind IN ('SIS_ERP', 'LMS', 'EMAIL', 'APPLICATION')),
    adapter          varchar(40)  NOT NULL,
    name             varchar(100) NOT NULL CHECK (length(btrim(name)) > 0),
    description      varchar(300),
    config           jsonb        NOT NULL DEFAULT '{}',
    secret_enc       text,
    enabled          boolean      NOT NULL DEFAULT false,
    connection_status varchar(20) NOT NULL DEFAULT 'UNKNOWN' CHECK (connection_status IN ('UNKNOWN', 'HEALTHY', 'FAILING')),
    last_checked_at  timestamptz,
    last_success_at  timestamptz,
    last_sync_at     timestamptz,
    last_error       varchar(500),
    consecutive_failures integer  NOT NULL DEFAULT 0,
    created_by       uuid,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    updated_by       uuid,
    updated_at       timestamptz  NOT NULL DEFAULT now(),
    version          bigint       NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id)
);
CREATE UNIQUE INDEX integration_name_uq ON integration (tenant_id, lower(name));

CREATE TABLE integration_run (
    id             uuid PRIMARY KEY,
    tenant_id      uuid         NOT NULL REFERENCES tenant (id),
    integration_id uuid         NOT NULL,
    operation      varchar(30)  NOT NULL CHECK (operation IN ('CONNECTION_TEST', 'SYNC')),
    trigger        varchar(20)  NOT NULL CHECK (trigger IN ('MANUAL', 'SCHEDULED', 'RETRY')),
    status         varchar(20)  NOT NULL CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    attempts       integer      NOT NULL DEFAULT 1,
    message        varchar(500),
    duration_ms    integer,
    retry_of       uuid,
    started_by     uuid,
    started_at     timestamptz  NOT NULL DEFAULT now(),
    finished_at    timestamptz,
    FOREIGN KEY (tenant_id, integration_id) REFERENCES integration (tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX integration_run_idx ON integration_run (tenant_id, integration_id, started_at DESC);

-- ---------------------------------------------------------------------------------------------------------
-- API clients: read-only, scoped credentials (key id + secret; secret hashed, shown once; rotation with grace period).
-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE api_client (
    id            uuid PRIMARY KEY,
    tenant_id     uuid          NOT NULL REFERENCES tenant (id),
    name          varchar(100)  NOT NULL CHECK (length(btrim(name)) > 0),
    description   varchar(300),
    scopes        text[]        NOT NULL,
    org_unit_id   uuid,
    allowed_ips   text[]        NOT NULL DEFAULT '{}',
    status        varchar(20)   NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'REVOKED')),
    expires_at    timestamptz,
    last_used_at  timestamptz,
    created_by    uuid,
    created_at    timestamptz   NOT NULL DEFAULT now(),
    revoked_by    uuid,
    revoked_at    timestamptz,
    updated_at    timestamptz   NOT NULL DEFAULT now(),
    version       bigint        NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, org_unit_id) REFERENCES org_unit (tenant_id, id)
);
CREATE UNIQUE INDEX api_client_name_uq ON api_client (tenant_id, lower(name));

CREATE TABLE api_client_secret (
    id           uuid PRIMARY KEY,
    tenant_id    uuid         NOT NULL REFERENCES tenant (id),
    client_id    uuid         NOT NULL,
    key_id       varchar(32)  NOT NULL UNIQUE,
    secret_hash  char(64)     NOT NULL,
    hint         varchar(8)   NOT NULL,
    created_by   uuid,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    expires_at   timestamptz,
    revoked_at   timestamptz,
    last_used_at timestamptz,
    FOREIGN KEY (tenant_id, client_id) REFERENCES api_client (tenant_id, id)
);
CREATE INDEX api_client_secret_client_idx ON api_client_secret (tenant_id, client_id);

CREATE TABLE api_client_usage (
    tenant_id  uuid    NOT NULL REFERENCES tenant (id),
    client_id  uuid    NOT NULL,
    day        date    NOT NULL,
    requests   bigint  NOT NULL DEFAULT 0,
    denied     bigint  NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, client_id, day),
    FOREIGN KEY (tenant_id, client_id) REFERENCES api_client (tenant_id, id)
);

-- Authentication happens before any tenant context exists: this maps a key id to its organization (and nothing else),
-- like invitation_tenant(). The secret is then verified inside that organization's RLS context.
CREATE FUNCTION api_key_tenant(p_key_id varchar) RETURNS uuid
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, ${flyway:defaultSchema} AS
$$ SELECT tenant_id FROM ${flyway:defaultSchema}.api_client_secret WHERE key_id = p_key_id $$;
REVOKE ALL ON FUNCTION api_key_tenant(varchar) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION api_key_tenant(varchar) TO ${appRole};

-- ---------------------------------------------------------------------------------------------------------
-- Webhooks: tenant-scoped endpoints, signed deliveries (HMAC-SHA256), retries, failure tracking, replay.
-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE webhook_endpoint (
    id                   uuid PRIMARY KEY,
    tenant_id            uuid          NOT NULL REFERENCES tenant (id),
    name                 varchar(100)  NOT NULL CHECK (length(btrim(name)) > 0),
    url                  varchar(500)  NOT NULL,
    event_types          text[]        NOT NULL,
    include_identifiers  boolean       NOT NULL DEFAULT false,
    secret_enc           text          NOT NULL,
    status               varchar(20)   NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'PAUSED', 'AUTO_DISABLED')),
    consecutive_failures integer       NOT NULL DEFAULT 0,
    last_delivery_at     timestamptz,
    last_success_at      timestamptz,
    created_by           uuid,
    created_at           timestamptz   NOT NULL DEFAULT now(),
    updated_at           timestamptz   NOT NULL DEFAULT now(),
    version              bigint        NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id)
);

CREATE TABLE webhook_delivery (
    id               uuid PRIMARY KEY,
    tenant_id        uuid         NOT NULL REFERENCES tenant (id),
    endpoint_id      uuid         NOT NULL,
    event_id         uuid         NOT NULL,
    event_type       varchar(80)  NOT NULL,
    payload          jsonb        NOT NULL,
    status           varchar(20)  NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'DELIVERED', 'FAILED')),
    attempts         integer      NOT NULL DEFAULT 0,
    next_attempt_at  timestamptz  NOT NULL DEFAULT now(),
    last_status_code integer,
    last_error       varchar(300),
    last_duration_ms integer,
    delivered_at     timestamptz,
    replay_of        uuid,
    created_by       uuid,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, endpoint_id) REFERENCES webhook_endpoint (tenant_id, id) ON DELETE CASCADE
);
-- one original delivery per endpoint and event (fan-out is idempotent); replays are separate rows
CREATE UNIQUE INDEX webhook_delivery_event_uq ON webhook_delivery (tenant_id, endpoint_id, event_id) WHERE replay_of IS NULL;
CREATE INDEX webhook_delivery_due_idx ON webhook_delivery (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX webhook_delivery_list_idx ON webhook_delivery (tenant_id, endpoint_id, created_at DESC);

-- ---------------------------------------------------------------------------------------------------------
-- Notifications: event, resource and delivery tracking.
-- ---------------------------------------------------------------------------------------------------------
-- The outbox records who caused an event, so consumers (notifications) do not inform people of their own actions.
ALTER TABLE outbox_event ADD COLUMN actor_id uuid;

-- source_event_id: the outbox event that produced the notification (consumers are at-least-once; one per recipient).
ALTER TABLE notification ADD COLUMN source_event_id uuid;
ALTER TABLE notification ADD COLUMN event_type varchar(80);
ALTER TABLE notification ADD COLUMN resource_type varchar(40);
ALTER TABLE notification ADD COLUMN resource_id varchar(64);
ALTER TABLE notification ADD COLUMN channel varchar(20) NOT NULL DEFAULT 'IN_APP' CHECK (channel IN ('IN_APP'));
ALTER TABLE notification ADD COLUMN delivery_status varchar(20) NOT NULL DEFAULT 'DELIVERED' CHECK (delivery_status IN ('DELIVERED'));
CREATE UNIQUE INDEX notification_event_uq ON notification (tenant_id, recipient_id, source_event_id) WHERE source_event_id IS NOT NULL;
CREATE INDEX notification_category_idx ON notification (tenant_id, recipient_id, category, created_at DESC);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['integration', 'integration_run', 'api_client', 'api_client_secret', 'api_client_usage', 'webhook_endpoint',
                             'webhook_delivery'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id())', t);
    END LOOP;
END $$;

-- Permissions (catalogue domain INTEGRATIONS) and the reporting permission for system roles.
UPDATE role SET permissions = permissions || ARRAY['integration.read', 'integration.manage', 'report.read']
    WHERE system_role AND code = 'ORG_ADMIN';
UPDATE role SET permissions = permissions || ARRAY['report.read']
    WHERE system_role AND code IN ('INSTITUTION_ADMIN', 'MANAGEMENT', 'DEPARTMENT_HEAD', 'ADMISSION_OFFICER');
