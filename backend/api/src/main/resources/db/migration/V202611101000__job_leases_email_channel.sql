-- Increment 2.0-E. (1) Scheduled-job leases (KI-61): a named job runs on exactly one application instance per interval.
-- (2) Email notification channel (P2-09 / KI-63): per-user category preferences and an email delivery queue with retries.

CREATE TABLE job_lease (
    name         varchar(100) PRIMARY KEY,
    owner        varchar(100) NOT NULL,
    lease_until  timestamptz  NOT NULL,
    acquired_at  timestamptz  NOT NULL DEFAULT now(),
    runs         bigint       NOT NULL DEFAULT 0
);
ALTER TABLE job_lease ENABLE ROW LEVEL SECURITY;
CREATE POLICY job_lease_platform ON job_lease USING (is_platform_context()) WITH CHECK (is_platform_context());

CREATE TABLE notification_preference (
    tenant_id      uuid        NOT NULL REFERENCES tenant (id),
    user_id        uuid        NOT NULL,
    category       varchar(50) NOT NULL,
    email_enabled  boolean     NOT NULL,
    updated_at     timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, user_id, category),
    FOREIGN KEY (tenant_id, user_id) REFERENCES user_account (tenant_id, id)
);

CREATE TABLE email_delivery (
    id                uuid PRIMARY KEY,
    tenant_id         uuid          NOT NULL REFERENCES tenant (id),
    notification_id   uuid,
    recipient_user_id uuid,
    recipient_enc     text          NOT NULL,        -- encrypted address (personal data)
    category          varchar(50)   NOT NULL,
    subject           varchar(300)  NOT NULL,
    body              text          NOT NULL,
    status            varchar(20)   NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    attempts          integer       NOT NULL DEFAULT 0,
    next_attempt_at   timestamptz   NOT NULL DEFAULT now(),
    last_error        varchar(200),
    sent_at           timestamptz,
    created_at        timestamptz   NOT NULL DEFAULT now()
);
CREATE INDEX email_delivery_due_idx ON email_delivery (tenant_id, next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX email_delivery_notification_idx ON email_delivery (tenant_id, notification_id);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['notification_preference', 'email_delivery'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id())', t);
    END LOOP;
END $$;
