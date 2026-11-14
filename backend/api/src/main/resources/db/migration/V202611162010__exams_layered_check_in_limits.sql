-- M3c / follow-up A (KI-66): layered check-in limits.
--  * session codes can be locked after too many failed attempts on them in a day (staff re-issue a new code);
--  * organizations register the network addresses of their exam venues per session (address layer becomes reporting-only);
--  * failed check-in attempts are recorded per candidate, portal and minute (who could not check in: KI-73 impact);
--  * the delivery process reports security and health signals to the platform (operator alerts, follow-up B).

ALTER TABLE session_code ADD COLUMN locked_at timestamptz;

CREATE TABLE exam_venue_network (
    id           uuid PRIMARY KEY,
    tenant_id    uuid          NOT NULL REFERENCES tenant (id),
    session_id   uuid          NOT NULL,
    network      cidr          NOT NULL,
    label        varchar(100),
    created_by   uuid          NOT NULL,
    created_at   timestamptz   NOT NULL DEFAULT now(),
    -- an allow-list switches the address layer off for the venue: never for more than a campus-sized network
    CHECK ((family(network) = 4 AND masklen(network) >= 16) OR (family(network) = 6 AND masklen(network) >= 48)),
    UNIQUE (tenant_id, session_id, network),
    FOREIGN KEY (tenant_id, session_id) REFERENCES readiness_session (tenant_id, id)
);

-- one row per candidate, portal and minute with failed attempts (bounded by the per-candidate limits)
CREATE TABLE check_in_failure (
    tenant_id     uuid          NOT NULL REFERENCES tenant (id),
    portal_id     uuid          NOT NULL,
    candidate_id  uuid          NOT NULL,
    minute        timestamptz   NOT NULL,
    failures      integer       NOT NULL DEFAULT 1,
    last_reason   varchar(40)   NOT NULL,
    instance_id   varchar(100),
    PRIMARY KEY (tenant_id, candidate_id, portal_id, minute),
    FOREIGN KEY (tenant_id, portal_id) REFERENCES exam_portal (tenant_id, id),
    FOREIGN KEY (tenant_id, candidate_id) REFERENCES candidate_account (tenant_id, id)
);
CREATE INDEX check_in_failure_minute_idx ON check_in_failure (tenant_id, portal_id, minute);

-- signals raised by delivery instances, correlated into platform incidents by the detector (administrative process)
CREATE TABLE platform_signal (
    id            uuid PRIMARY KEY,
    kind          varchar(30)   NOT NULL CHECK (kind IN ('CHECK_IN_ANOMALY', 'ADDRESS_THROTTLED')),
    tenant_id     uuid          REFERENCES tenant (id),
    portal_id     uuid,
    instance_id   varchar(100),
    observed_at   timestamptz   NOT NULL DEFAULT now(),
    detail        jsonb         NOT NULL DEFAULT '{}',
    incident_id   uuid          REFERENCES platform_incident (id),
    processed_at  timestamptz
);
CREATE INDEX platform_signal_pending_idx ON platform_signal (observed_at) WHERE processed_at IS NULL;

ALTER TABLE exam_venue_network ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON exam_venue_network USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());
ALTER TABLE check_in_failure ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON check_in_failure USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());
ALTER TABLE platform_signal ENABLE ROW LEVEL SECURITY;
CREATE POLICY platform_only ON platform_signal USING (is_platform_context()) WITH CHECK (is_platform_context());
