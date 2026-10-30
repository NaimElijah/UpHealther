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

-- The strongest lock this migration needs, taken first. The foreign key below takes SHARE ROW EXCLUSIVE
-- on auth_sessions and the DROP COLUMNs at the end need ACCESS EXCLUSIVE, so taking them in that order
-- lets a refresh holding a row lock deadlock with the migration: its UPDATE waits for the foreign key's
-- lock, and the DROP waits for the refresh. Taken up front, a refresh either finishes first or waits for
-- the whole migration. The timeout bounds that wait if a long transaction holds the table, failing the
-- start instead of stalling every refresh behind the queued lock. SET LOCAL ends with Flyway's
-- transaction, which wraps each PostgreSQL migration.
SET LOCAL lock_timeout = '5s';
LOCK TABLE auth_sessions IN ACCESS EXCLUSIVE MODE;

CREATE TABLE auth_session_spent_tokens (
    session_id UUID NOT NULL REFERENCES auth_sessions(id) ON DELETE CASCADE,
    token_hash BYTEA NOT NULL,
    spent_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (session_id, token_hash)
);

-- Every session still live is revoked, and no V9 digest is carried over. This release renames the
-- refresh cookie to __Host-refresh_token, so after it no browser presents the credential it holds. A
-- session left live would be out of reach of reuse detection: a thief who had copied a credential could
-- present it under the new name, while its owner, signing in afresh, would never present the old one
-- that exposes the theft. The rename signs every browser out anyway, so revoking costs nothing more,
-- and a copied digest would then protect nothing. The old cookie lapses on its own Max-Age (ADR-065).
UPDATE auth_sessions SET revoked = TRUE WHERE revoked = FALSE;

-- Superseded by the table above: a second record of the same fact could only disagree with it.
ALTER TABLE auth_sessions DROP COLUMN previous_token_hash;
ALTER TABLE auth_sessions DROP COLUMN rotated_at;
