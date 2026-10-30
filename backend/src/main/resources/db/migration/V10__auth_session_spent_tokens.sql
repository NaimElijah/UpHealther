-- Every refresh credential a session has spent, not only the last one (NFR-36, ADR-015, ADR-065).
--
-- V9 kept a single previous digest, so a credential two or more rotations old matched nothing and was
-- refused as a guess: a thief who had refreshed twice was never detected, and their session lived on to
-- its absolute cap. Each row here is one digest a rotation retired, and when, which is what the grace
-- window is measured from.
--
-- The digest is SHA-256 of the whole credential, sessionId included, so it is unique by construction
-- and the primary key is also the lookup a refresh makes. BYTEA, never the credential itself, as in V9.
--
-- ON DELETE CASCADE: the rows mean nothing without their session, and the nightly sweep and an
-- account's deletion both delete sessions in bulk, where nothing in Java visits the children.
CREATE TABLE auth_session_spent_tokens (
    session_id UUID NOT NULL REFERENCES auth_sessions(id) ON DELETE CASCADE,
    token_hash BYTEA NOT NULL,
    spent_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (session_id, token_hash)
);

-- The one digest V9 remembered is carried over, so a live session keeps the history it already had.
INSERT INTO auth_session_spent_tokens (session_id, token_hash, spent_at)
SELECT id, previous_token_hash, rotated_at
FROM auth_sessions
WHERE previous_token_hash IS NOT NULL AND rotated_at IS NOT NULL;

-- Superseded by the table above: a second record of the same fact could only disagree with it.
ALTER TABLE auth_sessions DROP COLUMN previous_token_hash;
ALTER TABLE auth_sessions DROP COLUMN rotated_at;
