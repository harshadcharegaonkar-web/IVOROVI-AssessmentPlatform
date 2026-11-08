-- Increment 2.0-B/C/D. Candidate identities (separate realm, linked per organization), exam portals on the shared exam
-- domain (tenant resolved server-side from the portal key), readiness check-in sessions with expiring single-use
-- session codes, presence, process heartbeats, platform incidents and the candidate-compensation workflow.

-- ---------------------------------------------------------------------------------------------------------
-- Exam portals (Q-07): one shared exam domain; the portal key in the URL is resolved to an organization by the server.
-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE exam_portal (
    id                 uuid PRIMARY KEY,
    tenant_id          uuid          NOT NULL REFERENCES tenant (id),
    portal_key         varchar(40)   NOT NULL UNIQUE CHECK (portal_key ~ '^[a-z0-9][a-z0-9-]{2,39}$'),
    name               varchar(150)  NOT NULL,
    status             varchar(20)   NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    self_registration  boolean       NOT NULL DEFAULT true,
    mfa_policy         varchar(20)   NOT NULL DEFAULT 'OPTIONAL' CHECK (mfa_policy IN ('OFF', 'OPTIONAL', 'REQUIRED')),
    created_by         uuid          NOT NULL,
    created_at         timestamptz   NOT NULL DEFAULT now(),
    updated_at         timestamptz   NOT NULL DEFAULT now(),
    version            bigint        NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id)
);
-- Before any tenant context exists: portal key -> organization (active portals of active organizations only).
CREATE FUNCTION exam_portal_tenant(p_key varchar) RETURNS uuid
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, ${flyway:defaultSchema} AS
$$ SELECT p.tenant_id FROM ${flyway:defaultSchema}.exam_portal p JOIN ${flyway:defaultSchema}.tenant t ON t.id = p.tenant_id
   WHERE p.portal_key = lower(p_key) AND p.status = 'ACTIVE' AND t.status IN ('ACTIVE', 'TRIAL') $$;
REVOKE ALL ON FUNCTION exam_portal_tenant(varchar) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION exam_portal_tenant(varchar) TO ${appRole};

-- ---------------------------------------------------------------------------------------------------------
-- Candidate accounts: an identity of the candidate realm linked to one organization (never a staff account).
-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE candidate_account (
    id             uuid PRIMARY KEY,
    tenant_id      uuid          NOT NULL REFERENCES tenant (id),
    subject        varchar(255)  NOT NULL,
    email_enc      text          NOT NULL,
    display_name   varchar(200),
    status         varchar(20)   NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    portal_id      uuid          NOT NULL,
    created_at     timestamptz   NOT NULL DEFAULT now(),
    last_seen_at   timestamptz,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, subject),
    FOREIGN KEY (tenant_id, portal_id) REFERENCES exam_portal (tenant_id, id)
);

-- ---------------------------------------------------------------------------------------------------------
-- Readiness check-in sessions and expiring single-use session codes (C-1).
-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE readiness_session (
    id           uuid PRIMARY KEY,
    tenant_id    uuid          NOT NULL REFERENCES tenant (id),
    portal_id    uuid          NOT NULL,
    org_unit_id  uuid,
    name         varchar(150)  NOT NULL,
    starts_at    timestamptz   NOT NULL,
    ends_at      timestamptz   NOT NULL,
    status       varchar(20)   NOT NULL DEFAULT 'SCHEDULED' CHECK (status IN ('SCHEDULED', 'CANCELLED')),
    created_by   uuid          NOT NULL,
    created_at   timestamptz   NOT NULL DEFAULT now(),
    version      bigint        NOT NULL DEFAULT 0,
    CHECK (ends_at > starts_at),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, portal_id) REFERENCES exam_portal (tenant_id, id),
    FOREIGN KEY (tenant_id, org_unit_id) REFERENCES org_unit (tenant_id, id)
);

CREATE TABLE session_code (
    id                  uuid PRIMARY KEY,
    tenant_id           uuid          NOT NULL REFERENCES tenant (id),
    session_id          uuid          NOT NULL,
    code_hash           char(64)      NOT NULL UNIQUE,      -- HMAC of the code; the code itself is shown once
    hint                varchar(4)    NOT NULL,
    valid_from          timestamptz   NOT NULL,
    valid_until         timestamptz   NOT NULL,
    consumed_at         timestamptz,
    consumed_by         uuid,
    revoked_at          timestamptz,
    issued_for          uuid,                                -- candidate the code was issued to (compensation re-check), if any
    created_by          uuid,
    created_at          timestamptz   NOT NULL DEFAULT now(),
    CHECK (valid_until > valid_from),
    -- single use: consumed exactly once, by exactly one candidate
    CHECK ((consumed_at IS NULL) = (consumed_by IS NULL)),
    FOREIGN KEY (tenant_id, session_id) REFERENCES readiness_session (tenant_id, id),
    FOREIGN KEY (tenant_id, consumed_by) REFERENCES candidate_account (tenant_id, id)
);
CREATE INDEX session_code_session_idx ON session_code (tenant_id, session_id);

CREATE TABLE check_in (
    id              uuid PRIMARY KEY,
    tenant_id       uuid          NOT NULL REFERENCES tenant (id),
    session_id      uuid          NOT NULL,
    candidate_id    uuid          NOT NULL,
    code_id         uuid          NOT NULL,
    checked_in_at   timestamptz   NOT NULL DEFAULT now(),
    clock_offset_ms integer,
    system_check    jsonb         NOT NULL DEFAULT '{}',
    UNIQUE (tenant_id, session_id, candidate_id),
    FOREIGN KEY (tenant_id, session_id) REFERENCES readiness_session (tenant_id, id),
    FOREIGN KEY (tenant_id, candidate_id) REFERENCES candidate_account (tenant_id, id)
);

CREATE TABLE delivery_presence (
    tenant_id      uuid         NOT NULL REFERENCES tenant (id),
    candidate_id   uuid         NOT NULL,
    session_id     uuid         NOT NULL,
    first_seen_at  timestamptz  NOT NULL DEFAULT now(),
    last_seen_at   timestamptz  NOT NULL DEFAULT now(),
    beats          bigint       NOT NULL DEFAULT 1,
    saves          bigint       NOT NULL DEFAULT 0,       -- acknowledged practice saves (harness/echo; answer saves from 2.5)
    last_save_seq  bigint       NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, candidate_id, session_id),
    FOREIGN KEY (tenant_id, candidate_id) REFERENCES candidate_account (tenant_id, id),
    FOREIGN KEY (tenant_id, session_id) REFERENCES readiness_session (tenant_id, id)
);

-- ---------------------------------------------------------------------------------------------------------
-- Platform outage detection and candidate compensation (C-3).
-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE process_heartbeat (
    instance_id   varchar(100) PRIMARY KEY,
    role          varchar(20)  NOT NULL CHECK (role IN ('ADMIN', 'DELIVERY')),
    started_at    timestamptz  NOT NULL DEFAULT now(),
    last_beat_at  timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE platform_incident (
    id           uuid PRIMARY KEY,
    kind         varchar(30)   NOT NULL CHECK (kind IN ('DELIVERY_OUTAGE')),
    started_at   timestamptz   NOT NULL,
    ended_at     timestamptz,
    detected_at  timestamptz   NOT NULL DEFAULT now(),
    status       varchar(20)   NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'RESOLVED')),
    summary      varchar(300)  NOT NULL
);

-- What an incident meant for one organization (platform-detected or reported by the institution).
CREATE TABLE exam_incident_impact (
    id                  uuid PRIMARY KEY,
    tenant_id           uuid          NOT NULL REFERENCES tenant (id),
    platform_incident_id uuid         REFERENCES platform_incident (id),
    source              varchar(20)   NOT NULL CHECK (source IN ('PLATFORM', 'REPORTED')),
    started_at          timestamptz   NOT NULL,
    ended_at            timestamptz,                         -- NULL while a platform outage is still ongoing
    description         varchar(500)  NOT NULL,
    status              varchar(30)   NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'COMPENSATION_PENDING', 'COMPENSATED', 'CLOSED_NO_ACTION')),
    reported_by         uuid,
    created_at          timestamptz   NOT NULL DEFAULT now(),
    version             bigint        NOT NULL DEFAULT 0,
    CHECK (ended_at >= started_at),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, platform_incident_id)
);

CREATE TABLE exam_incident_candidate (
    tenant_id     uuid  NOT NULL REFERENCES tenant (id),
    impact_id     uuid  NOT NULL,
    candidate_id  uuid  NOT NULL,
    session_id    uuid  NOT NULL,
    PRIMARY KEY (tenant_id, impact_id, candidate_id, session_id),
    FOREIGN KEY (tenant_id, impact_id) REFERENCES exam_incident_impact (tenant_id, id),
    FOREIGN KEY (tenant_id, candidate_id) REFERENCES candidate_account (tenant_id, id),
    FOREIGN KEY (tenant_id, session_id) REFERENCES readiness_session (tenant_id, id)
);

CREATE TABLE exam_compensation (
    id                 uuid PRIMARY KEY,
    tenant_id          uuid          NOT NULL REFERENCES tenant (id),
    impact_id          uuid          NOT NULL,
    action             varchar(30)   NOT NULL CHECK (action IN ('EXTEND_SESSION', 'NEW_CHECK_IN', 'NO_ACTION')),
    extension_minutes  integer       CHECK (extension_minutes IS NULL OR extension_minutes BETWEEN 1 AND 240),
    rationale          varchar(500)  NOT NULL,
    status             varchar(20)   NOT NULL DEFAULT 'PROPOSED' CHECK (status IN ('PROPOSED', 'APPROVED', 'REJECTED', 'CANCELLED')),
    approval_id        uuid,
    proposed_by        uuid          NOT NULL,
    proposed_at        timestamptz   NOT NULL DEFAULT now(),
    decided_at         timestamptz,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, impact_id) REFERENCES exam_incident_impact (tenant_id, id)
);

-- Per-candidate result of an approved compensation (consumed by delivery from 2.5).
CREATE TABLE candidate_compensation (
    id                 uuid PRIMARY KEY,
    tenant_id          uuid          NOT NULL REFERENCES tenant (id),
    compensation_id    uuid          NOT NULL,
    candidate_id       uuid          NOT NULL,
    session_id         uuid          NOT NULL,
    extension_minutes  integer,
    new_code_id        uuid,
    notified_at        timestamptz,
    created_at         timestamptz   NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, compensation_id, candidate_id, session_id),
    FOREIGN KEY (tenant_id, compensation_id) REFERENCES exam_compensation (tenant_id, id),
    FOREIGN KEY (tenant_id, candidate_id) REFERENCES candidate_account (tenant_id, id)
);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['exam_portal', 'candidate_account', 'readiness_session', 'session_code', 'check_in', 'delivery_presence',
                             'exam_incident_impact', 'exam_incident_candidate', 'exam_compensation', 'candidate_compensation'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id())', t);
    END LOOP;
    FOREACH t IN ARRAY ARRAY['process_heartbeat', 'platform_incident'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY platform_only ON %I USING (is_platform_context()) WITH CHECK (is_platform_context())', t);
    END LOOP;
END $$;

-- Organizations see the platform incidents that affected them (read-only, through their impact rows).
CREATE POLICY platform_incident_affected ON platform_incident FOR SELECT
    USING (EXISTS (SELECT 1 FROM exam_incident_impact i WHERE i.platform_incident_id = platform_incident.id AND i.tenant_id = current_tenant_id()));

-- Permissions (catalogue domain EXAMINATIONS, refined for 2.0 foundations; see decision register).
UPDATE role SET permissions = permissions || ARRAY['exam.session.manage', 'exam.incident.manage', 'exam.incident.decide']
    WHERE system_role AND code IN ('ORG_ADMIN', 'INSTITUTION_ADMIN', 'EXAM_CONTROLLER');
