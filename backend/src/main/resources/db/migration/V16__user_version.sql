-- An account carries an optimistic-lock version, as an upgrade does (#340, D6.5; BR-14's mechanism).
--
-- Every write to an account writes the whole row, so two that overlap undo each other: an administrator's
-- disable, overtaken by a role change loaded before it, put enabled = true back, and the audit trail still
-- said "disabled" (NFR-34). User's @Version refuses the overtaken write at its commit instead, answered 409.
--
-- NOT NULL with a default, so every existing row starts at version 0 without a backfill, and an INSERT that
-- names no version, as V2's seed does, still succeeds. The default stays: Hibernate always writes the
-- version itself, so it serves only such writes.

-- Bounds the wait for the ACCESS EXCLUSIVE lock ADD COLUMN takes on users (ADR-072).
SET LOCAL lock_timeout = '5s';

ALTER TABLE users ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
