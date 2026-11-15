-- KI-79 (Day 5): after a password reset, sessions that started before it are ended in the identity provider, but access
-- tokens those sessions already hold are self-contained and stayed usable until they expired (up to 5 minutes). The guard
-- now also records the reset here; the delivery API refuses any token issued before it unless it belongs to the session the
-- reset itself signed in. No secret is stored: a time and the kept sign-in session id.
ALTER TABLE candidate_account ADD COLUMN sessions_revoked_before timestamptz;
ALTER TABLE candidate_account ADD COLUMN kept_session_id varchar(100);
