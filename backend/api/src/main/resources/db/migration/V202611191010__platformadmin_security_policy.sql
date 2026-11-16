-- Demo programme, mandatory module I (Session 2): organization security policy for staff sessions.
-- All organizations share one staff identity realm, so a second-factor requirement and session limits per organization
-- are enforced by the API from verified token claims (StaffSessionGuard). The password policy stays realm-wide.

-- One row per organization; no row = no policy (no second factor required, no session limits beyond the realm's).
CREATE TABLE tenant_security_policy (
    tenant_id       uuid         PRIMARY KEY REFERENCES tenant (id),
    mfa_required    boolean      NOT NULL DEFAULT false,
    idle_minutes    integer      CHECK (idle_minutes BETWEEN 5 AND 1440),          -- null = no idle limit
    absolute_hours  integer      CHECK (absolute_hours BETWEEN 1 AND 720),         -- null = no absolute limit
    updated_by      uuid,
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    version         bigint       NOT NULL DEFAULT 0
);

-- Staff sessions seen by the API (identity-provider session id), for idle and absolute limits. No token, no address.
CREATE TABLE staff_session (
    tenant_id     uuid          NOT NULL REFERENCES tenant (id),
    session_id    varchar(100)  NOT NULL,
    user_id       uuid          NOT NULL,
    first_seen    timestamptz   NOT NULL DEFAULT now(),
    last_seen     timestamptz   NOT NULL DEFAULT now(),
    ended_at      timestamptz,
    ended_reason  varchar(20)   CHECK (ended_reason IN ('IDLE', 'EXPIRED')),
    PRIMARY KEY (tenant_id, session_id)
);
CREATE INDEX staff_session_user_idx ON staff_session (tenant_id, user_id);

-- Users asked to enrol an authenticator under the organization's policy (requested once at the identity provider).
CREATE TABLE staff_mfa_enrolment_request (
    tenant_id     uuid         NOT NULL REFERENCES tenant (id),
    user_id       uuid         NOT NULL,
    requested_at  timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, user_id)
);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['tenant_security_policy', 'staff_session', 'staff_mfa_enrolment_request'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id())', t);
    END LOOP;
END $$;
REVOKE DELETE ON tenant_security_policy FROM ${appRole};

-- Permission: organization administrators (not privileged; weakening needs a second holder's approval, ADR-0007)
UPDATE role SET permissions = permissions || ARRAY['tenant.security.manage']
    WHERE system_role AND code = 'ORG_ADMIN' AND NOT ('tenant.security.manage' = ANY (permissions));
