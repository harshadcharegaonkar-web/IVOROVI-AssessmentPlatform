-- Increment 2.0-A: four-eyes approval framework (condition C-2, decision Q-04).
-- One person can never perform both steps: enforced by the service, by the check constraint below, and by a live
-- permission check of the approver at decision time. No eligible second approver => the request stays PENDING
-- (or is escalated to organization-level holders); it is never auto-approved.
CREATE TABLE approval_request (
    id                   uuid PRIMARY KEY,
    tenant_id            uuid          NOT NULL REFERENCES tenant (id),
    type                 varchar(60)   NOT NULL,
    subject_type         varchar(60)   NOT NULL,
    subject_id           varchar(64)   NOT NULL,
    summary              varchar(300)  NOT NULL,
    payload              jsonb         NOT NULL DEFAULT '{}',
    payload_hash         char(64)      NOT NULL,
    required_permission  varchar(100)  NOT NULL,
    org_unit_id          uuid,
    requested_by         uuid          NOT NULL,
    requested_at         timestamptz   NOT NULL DEFAULT now(),
    status               varchar(20)   NOT NULL DEFAULT 'PENDING'
                         CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED', 'EXPIRED')),
    expires_at           timestamptz   NOT NULL,
    decided_by           uuid,
    decided_at           timestamptz,
    decision_reason      varchar(500),
    escalated_by         uuid,
    escalated_at         timestamptz,
    version              bigint        NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, requested_by) REFERENCES user_account (tenant_id, id),
    FOREIGN KEY (tenant_id, decided_by) REFERENCES user_account (tenant_id, id),
    FOREIGN KEY (tenant_id, org_unit_id) REFERENCES org_unit (tenant_id, id),
    -- separation of duties: the decider is never the requester
    CONSTRAINT approval_four_eyes CHECK (decided_by IS NULL OR decided_by <> requested_by),
    -- a decision has a decider, and only APPROVED/REJECTED carry one
    CONSTRAINT approval_decision_consistency CHECK ((status IN ('APPROVED', 'REJECTED')) = (decided_by IS NOT NULL))
);
-- one open request per subject and type
CREATE UNIQUE INDEX approval_open_uq ON approval_request (tenant_id, type, subject_type, subject_id) WHERE status = 'PENDING';
CREATE INDEX approval_pending_idx ON approval_request (tenant_id, status, expires_at);
ALTER TABLE approval_request ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON approval_request USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());
