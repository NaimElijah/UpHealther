-- Server-side sessions behind a rotating refresh credential (NFR-1, NFR-35, NFR-36). See ADR-015.
--
-- The point of the table is that signing out can mean something. A bearer token is valid until it
-- expires and nothing the server does can take it back; a session is the row that makes revocation,
-- reuse detection and a per-device sign-out possible.
--
-- TIMESTAMPTZ throughout, not TIMESTAMP. These are instants, compared against now() on whatever host
-- happens to serve the request, and a naive column would make the comparison depend on the server's
-- timezone. The user-facing tables predate this and stay as they are.
--
-- BYTEA, never the credential itself. Only the SHA-256 of a refresh token is stored, so a copy of this
-- table yields nothing that can be presented.
CREATE TABLE auth_sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    refresh_token_hash BYTEA NOT NULL,
    previous_token_hash BYTEA,
    created_at TIMESTAMPTZ NOT NULL,
    last_used_at TIMESTAMPTZ NOT NULL,
    rotated_at TIMESTAMPTZ,
    idle_expires_at TIMESTAMPTZ NOT NULL,
    absolute_expires_at TIMESTAMPTZ NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE
);

-- Deleting an account takes its sessions with it: ON DELETE CASCADE above, and this index is what
-- keeps that delete, and "end every session this account holds", from scanning the table.
CREATE INDEX idx_auth_sessions_user_id ON auth_sessions(user_id);

-- No index for the nightly cleanup sweep, deliberately. It deletes where the session is revoked OR
-- idle OR past its cap, and Postgres can only turn an OR into a bitmap scan when every branch is
-- indexed - so an index on one of the three would be paid for on every insert and used by nothing.
-- The sweep scans instead, which is cheap here: the table holds roughly one row per live session and
-- is emptied of dead ones every night. Revisit if that stops being true.
