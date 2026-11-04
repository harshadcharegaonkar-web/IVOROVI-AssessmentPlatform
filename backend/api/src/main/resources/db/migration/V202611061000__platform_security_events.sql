-- Increment 1.6 closure (KI-53): security events that cannot be attributed to an organization, such as uploads
-- refused before authentication (no or invalid token). Organization-attributable refusals go to the hash-chained
-- audit trail. Rows hold request metadata only (never file contents, names or personal values); written and read in
-- platform context; append-only for the application role.
CREATE TABLE security_event (
    id          uuid PRIMARY KEY,
    occurred_at timestamptz  NOT NULL DEFAULT now(),
    category    varchar(60)  NOT NULL,
    method      varchar(10),
    path        varchar(500),
    status      integer,
    reason      varchar(100),
    client_ip   varchar(64),
    user_agent  varchar(300),
    trace_id    varchar(64),
    details     jsonb        NOT NULL DEFAULT '{}'
);
CREATE INDEX security_event_time_idx ON security_event (occurred_at DESC);
ALTER TABLE security_event ENABLE ROW LEVEL SECURITY;
CREATE POLICY security_event_platform ON security_event USING (is_platform_context()) WITH CHECK (is_platform_context());
REVOKE UPDATE, TRUNCATE ON security_event FROM ${appRole};
