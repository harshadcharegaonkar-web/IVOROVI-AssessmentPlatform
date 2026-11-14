-- M3c / follow-up B (KI-71, KI-72, KI-73): outage detection beyond total failure.
--  * per-instance health in the heartbeat (version, in-flight requests, 1-minute error rate and p95, dependency checks)
--    with a rolling sample history;
--  * correlated platform incidents with severity, acknowledgement, mitigation, recovery hold and a timeline;
--  * the serving instance and verified gaps of every candidate's presence (impact and verified lost time);
--  * impact rows carry why a candidate is affected and the verified lost time; compensation by verified lost time.

ALTER TABLE process_heartbeat
    ADD COLUMN version      varchar(60),
    ADD COLUMN in_flight    integer,
    ADD COLUMN requests_1m  integer,
    ADD COLUMN errors_1m    integer,
    ADD COLUMN p95_ms       integer,
    ADD COLUMN checks       jsonb NOT NULL DEFAULT '{}';

CREATE TABLE process_heartbeat_sample (
    instance_id   varchar(100) NOT NULL,
    role          varchar(20)  NOT NULL CHECK (role IN ('ADMIN', 'DELIVERY')),
    sampled_at    timestamptz  NOT NULL DEFAULT now(),
    version       varchar(60),
    in_flight     integer      NOT NULL DEFAULT 0,
    requests_1m   integer      NOT NULL DEFAULT 0,
    errors_1m     integer      NOT NULL DEFAULT 0,
    p95_ms        integer      NOT NULL DEFAULT 0,
    checks        jsonb        NOT NULL DEFAULT '{}',   -- {"database":"OK","valkey":"FAILED",...}
    PRIMARY KEY (instance_id, sampled_at)
);
CREATE INDEX process_heartbeat_sample_time_idx ON process_heartbeat_sample (role, sampled_at);

ALTER TABLE platform_incident DROP CONSTRAINT platform_incident_kind_check;
ALTER TABLE platform_incident ADD CONSTRAINT platform_incident_kind_check CHECK (kind IN ('DELIVERY_OUTAGE', 'CHECK_IN_ANOMALY'));
ALTER TABLE platform_incident
    ADD COLUMN severity         varchar(30)  NOT NULL DEFAULT 'ROLE_DOWN'
        CHECK (severity IN ('INSTANCE_DEGRADED', 'ROLE_DEGRADED', 'ROLE_DOWN', 'SECURITY_ALERT')),
    ADD COLUMN role             varchar(20)  NOT NULL DEFAULT 'DELIVERY',
    ADD COLUMN tenant_id        uuid         REFERENCES tenant (id),      -- organization concerned (check-in anomalies only)
    ADD COLUMN instances        text[]       NOT NULL DEFAULT '{}',       -- instances that failed at any time during the incident
    ADD COLUMN clear_since      timestamptz,                              -- every rule clear since (recovery hold running)
    ADD COLUMN acknowledged_at  timestamptz,
    ADD COLUMN acknowledged_by  uuid,
    ADD COLUMN acknowledged_by_name varchar(200),
    ADD COLUMN mitigated_at     timestamptz,
    ADD COLUMN updated_at       timestamptz  NOT NULL DEFAULT now();
-- at most one open incident per cause: per role for delivery health, per organization for check-in anomalies
CREATE UNIQUE INDEX platform_incident_one_open_delivery ON platform_incident (role) WHERE status = 'OPEN' AND kind = 'DELIVERY_OUTAGE';
CREATE UNIQUE INDEX platform_incident_one_open_anomaly ON platform_incident (tenant_id) WHERE status = 'OPEN' AND kind = 'CHECK_IN_ANOMALY';

CREATE TABLE platform_incident_event (
    id           uuid PRIMARY KEY,
    incident_id  uuid          NOT NULL REFERENCES platform_incident (id),
    at           timestamptz   NOT NULL DEFAULT now(),
    type         varchar(30)   NOT NULL CHECK (type IN ('OPENED', 'SIGNAL_RAISED', 'SIGNAL_CLEARED', 'SEVERITY_CHANGED', 'ALL_CLEAR', 'RELAPSED',
                                                       'RECOVERED', 'ACKNOWLEDGED', 'MITIGATED', 'NOTE', 'IMPACT_RECORDED')),
    rule         varchar(40),
    instance_id  varchar(100),
    detail       varchar(1000),
    actor_id     uuid,
    actor_name   varchar(200)
);
CREATE INDEX platform_incident_event_incident_idx ON platform_incident_event (incident_id, at);

ALTER TABLE delivery_presence
    ADD COLUMN instance_id          varchar(100),
    ADD COLUMN prev_instance_id     varchar(100),
    ADD COLUMN instance_changed_at  timestamptz,
    ADD COLUMN prev_seen_at         timestamptz;

-- a silence in a candidate's presence longer than the gap threshold, with the instance before and after (verified lost time)
CREATE TABLE delivery_presence_gap (
    tenant_id        uuid          NOT NULL REFERENCES tenant (id),
    candidate_id     uuid          NOT NULL,
    session_id       uuid          NOT NULL,
    gap_start        timestamptz   NOT NULL,
    gap_end          timestamptz   NOT NULL,
    instance_before  varchar(100),
    instance_after   varchar(100),
    CHECK (gap_end > gap_start),
    PRIMARY KEY (tenant_id, candidate_id, session_id, gap_start),
    FOREIGN KEY (tenant_id, candidate_id) REFERENCES candidate_account (tenant_id, id),
    FOREIGN KEY (tenant_id, session_id) REFERENCES readiness_session (tenant_id, id)
);
CREATE INDEX delivery_presence_gap_time_idx ON delivery_presence_gap (tenant_id, gap_start, gap_end);

ALTER TABLE check_in ADD COLUMN instance_id varchar(100);

ALTER TABLE exam_incident_impact
    ADD COLUMN scope              varchar(20)  NOT NULL DEFAULT 'ROLE' CHECK (scope IN ('ROLE', 'INSTANCE', 'REPORTED')),
    ADD COLUMN instances          text[]       NOT NULL DEFAULT '{}',
    ADD COLUMN missing_check_ins  integer      NOT NULL DEFAULT 0;      -- expected (codes issued) but not checked in, identities unknown

ALTER TABLE exam_incident_candidate
    ADD COLUMN reason        varchar(40)  NOT NULL DEFAULT 'ACTIVE'
        CHECK (reason IN ('ACTIVE', 'SERVED_BY_FAILED_INSTANCE', 'CHECK_IN_FAILED', 'EXPECTED_NOT_CHECKED_IN')),
    ADD COLUMN lost_seconds  integer      CHECK (lost_seconds IS NULL OR lost_seconds >= 0);   -- NULL: not verified

ALTER TABLE exam_compensation DROP CONSTRAINT exam_compensation_action_check;
ALTER TABLE exam_compensation ADD CONSTRAINT exam_compensation_action_check
    CHECK (action IN ('EXTEND_SESSION', 'EXTEND_BY_LOST_TIME', 'NEW_CHECK_IN', 'NO_ACTION'));
ALTER TABLE candidate_compensation ADD COLUMN lost_seconds integer;

ALTER TABLE process_heartbeat_sample ENABLE ROW LEVEL SECURITY;
CREATE POLICY platform_only ON process_heartbeat_sample USING (is_platform_context()) WITH CHECK (is_platform_context());
ALTER TABLE platform_incident_event ENABLE ROW LEVEL SECURITY;
CREATE POLICY platform_only ON platform_incident_event USING (is_platform_context()) WITH CHECK (is_platform_context());
ALTER TABLE delivery_presence_gap ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON delivery_presence_gap USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());
