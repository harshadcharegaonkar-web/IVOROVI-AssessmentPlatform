-- Phase 1, increment 1.0: idempotency records, in-app notifications, outbox retention permissions.

-- ---------------------------------------------------------------------------------------------------------
-- Idempotency: one record per (tenant, user, key). Responses of completed requests are replayed.
-- Retention (platform context) deletes expired rows.
-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE idempotency_record (
    tenant_id             uuid         NOT NULL REFERENCES tenant (id),
    user_account_id       uuid         NOT NULL,
    idem_key              varchar(100) NOT NULL,
    method                varchar(10)  NOT NULL,
    path                  varchar(300) NOT NULL,
    request_hash          char(64)     NOT NULL,
    status                varchar(15)  NOT NULL CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    response_status       integer,
    response_content_type varchar(100),
    response_body         bytea,
    created_at            timestamptz  NOT NULL DEFAULT now(),
    expires_at            timestamptz  NOT NULL,
    PRIMARY KEY (tenant_id, user_account_id, idem_key),
    FOREIGN KEY (tenant_id, user_account_id) REFERENCES user_account (tenant_id, id)
);
CREATE INDEX idempotency_record_expiry_idx ON idempotency_record (expires_at);
ALTER TABLE idempotency_record ENABLE ROW LEVEL SECURITY;
-- Platform context must also pass SELECT: PostgreSQL applies SELECT policies to DELETE ... WHERE (retention).
CREATE POLICY idem_select ON idempotency_record FOR SELECT
    USING (tenant_id = current_tenant_id() OR is_platform_context());
CREATE POLICY idem_insert ON idempotency_record FOR INSERT WITH CHECK (tenant_id = current_tenant_id());
CREATE POLICY idem_update ON idempotency_record FOR UPDATE
    USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());
CREATE POLICY idem_delete ON idempotency_record FOR DELETE
    USING (tenant_id = current_tenant_id() OR is_platform_context());

-- ---------------------------------------------------------------------------------------------------------
-- In-app notifications (recipient-scoped in the API; tenant-scoped by RLS).
-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE notification (
    id           uuid PRIMARY KEY,
    tenant_id    uuid          NOT NULL REFERENCES tenant (id),
    recipient_id uuid          NOT NULL,
    category     varchar(50)   NOT NULL,
    severity     varchar(10)   NOT NULL CHECK (severity IN ('INFO', 'SUCCESS', 'WARNING', 'ERROR')),
    title        varchar(200)  NOT NULL,
    body         varchar(1000),
    link         varchar(300)  CHECK (link IS NULL OR link ~ '^/[A-Za-z0-9/_?=&.-]*$'),
    created_at   timestamptz   NOT NULL DEFAULT now(),
    read_at      timestamptz,
    FOREIGN KEY (tenant_id, recipient_id) REFERENCES user_account (tenant_id, id)
);
CREATE INDEX notification_recipient_idx ON notification (tenant_id, recipient_id, created_at DESC);
ALTER TABLE notification ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON notification
    USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());

-- ---------------------------------------------------------------------------------------------------------
-- Outbox: per-command policies. Tenants may insert/read their own events; only platform jobs update, and
-- only platform jobs may delete, and then only rows that were already published (retention).
-- ---------------------------------------------------------------------------------------------------------
DROP POLICY outbox_access ON outbox_event;
CREATE POLICY outbox_select ON outbox_event FOR SELECT
    USING (is_platform_context() OR tenant_id = current_tenant_id());
CREATE POLICY outbox_insert ON outbox_event FOR INSERT
    WITH CHECK (is_platform_context() OR tenant_id = current_tenant_id());
CREATE POLICY outbox_update ON outbox_event FOR UPDATE
    USING (is_platform_context()) WITH CHECK (is_platform_context());
CREATE POLICY outbox_delete ON outbox_event FOR DELETE
    USING (is_platform_context() AND published_at IS NOT NULL);
GRANT DELETE ON outbox_event TO ${appRole};
