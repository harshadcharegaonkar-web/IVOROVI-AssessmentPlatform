-- KI-89 (decision D-15): durable, account-level protection against session-code guessing that works when Valkey (and with
-- it every in-memory rate-limit layer) is unavailable. One row per candidate account: failed check-in attempts in the
-- current window, the end of a cooldown, and how many cooldowns in a row (the cooldown doubles, up to a cap, then resets
-- after a quiet day). A successful check-in removes the row. Keyed by the signed-in account, so nobody can exhaust another
-- candidate's budget. No session code or code hash is stored.
CREATE TABLE check_in_guard (
    tenant_id          uuid         NOT NULL REFERENCES tenant (id),
    candidate_id       uuid         NOT NULL,
    failures           integer      NOT NULL DEFAULT 0 CHECK (failures >= 0),
    window_started_at  timestamptz  NOT NULL DEFAULT now(),
    cooldowns          integer      NOT NULL DEFAULT 0 CHECK (cooldowns >= 0),
    blocked_until      timestamptz,
    last_failure_at    timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, candidate_id),
    FOREIGN KEY (tenant_id, candidate_id) REFERENCES candidate_account (tenant_id, id)
);

ALTER TABLE check_in_guard ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON check_in_guard USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());
