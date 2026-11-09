-- Day 2 (primary stream): keyed blind index of a candidate's sign-in email, so scheduling can link an invited email to
-- the candidate account without decrypting every row. HMAC under a key derived from the data key (FieldCipher.blindIndex);
-- filled at every portal visit (CandidateService.me). Not unique: one person may sign in with the same email on two portals.
ALTER TABLE candidate_account ADD COLUMN email_index char(64);
CREATE INDEX candidate_account_email_index_idx ON candidate_account (tenant_id, email_index) WHERE email_index IS NOT NULL;
