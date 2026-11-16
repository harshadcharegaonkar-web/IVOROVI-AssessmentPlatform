-- Day 7, KI-79: immediate revocation of candidate sessions. The candidate realm notifies the delivery API through OIDC
-- back-channel logout whenever it ends a session (password reset, administrator, sign-out); the session id is recorded
-- here and every delivery request with a token of that session is refused at once. Not per organization: a candidate
-- identity's session spans organizations (this also closes QA-D6-4 for ended sessions). No personal data beyond the
-- identity provider's subject and session ids. Rows are append-only for the application; they are purged two days after
-- the session ended (access tokens live 5 minutes; an ended session's refresh token is refused by the identity provider).
CREATE TABLE candidate_ended_session (
    sid       varchar(100) PRIMARY KEY,
    subject   varchar(100) NOT NULL,
    ended_at  timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX candidate_ended_session_ended_idx ON candidate_ended_session (ended_at);

CREATE FUNCTION candidate_ended_session_guard() RETURNS trigger LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'an ended session never changes' USING ERRCODE = '23514', CONSTRAINT = 'candidate_ended_session_permanent';
    END IF;
    IF OLD.ended_at > now() - interval '2 days' THEN
        RAISE EXCEPTION 'an ended session is kept for two days' USING ERRCODE = '23514', CONSTRAINT = 'candidate_ended_session_permanent';
    END IF;
    RETURN OLD;
END $$;
CREATE TRIGGER candidate_ended_session_guard BEFORE UPDATE OR DELETE ON candidate_ended_session
    FOR EACH ROW EXECUTE FUNCTION candidate_ended_session_guard();
REVOKE UPDATE, TRUNCATE ON candidate_ended_session FROM ${appRole};

-- QA-D6-4 (Day 7): the password-reset revocation per candidate identity, not per organization, so it holds in an
-- organization the candidate joins after the reset and in one that was suspended at the time. Written by RecoveryGuard
-- (the backstop when a back-channel notification is lost); the time only moves forward and the kept session follows the
-- newest reset (QA-D6-3).
CREATE TABLE candidate_identity_revocation (
    subject           varchar(100) PRIMARY KEY,
    revoked_before    timestamptz  NOT NULL,
    kept_session_id   varchar(100),
    updated_at        timestamptz  NOT NULL DEFAULT now()
);
CREATE FUNCTION candidate_identity_revocation_guard() RETURNS trigger LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a revocation is never removed' USING ERRCODE = '23514', CONSTRAINT = 'candidate_identity_revocation_permanent';
    END IF;
    IF NEW.subject <> OLD.subject OR NEW.revoked_before < OLD.revoked_before THEN
        RAISE EXCEPTION 'a revocation only moves forward' USING ERRCODE = '23514', CONSTRAINT = 'candidate_identity_revocation_forward';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER candidate_identity_revocation_guard BEFORE UPDATE OR DELETE ON candidate_identity_revocation
    FOR EACH ROW EXECUTE FUNCTION candidate_identity_revocation_guard();
REVOKE TRUNCATE ON candidate_identity_revocation FROM ${appRole};
-- carry over the per-organization revocations recorded so far (latest per identity)
INSERT INTO candidate_identity_revocation (subject, revoked_before, kept_session_id)
SELECT DISTINCT ON (subject) subject, sessions_revoked_before, kept_session_id FROM candidate_account
WHERE sessions_revoked_before IS NOT NULL ORDER BY subject, sessions_revoked_before DESC;
