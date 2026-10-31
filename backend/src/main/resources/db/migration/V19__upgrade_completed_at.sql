-- An upgrade records when it was completed (FR-27, ADR-080).
--
-- The dashboard's "recently completed" list was ordered by updated_at, which every edit moves, and FR-13
-- lets a completed upgrade be edited: renaming one finished a year ago put it back at the top. The domain
-- now stamps completed_at, from the injected clock, at the one transition that reaches COMPLETED, and
-- COMPLETED is terminal, so the stamp never moves afterwards.
--
-- Nullable: only a completed upgrade has one, and nothing else ever will.

-- Bounds the wait for the ACCESS EXCLUSIVE lock ADD COLUMN takes on health_upgrades (ADR-072).
SET LOCAL lock_timeout = '5s';

ALTER TABLE health_upgrades ADD COLUMN completed_at TIMESTAMP;

-- Approximate, and knowingly so. When a row already completed was completed is recorded nowhere; its
-- updated_at is the closest thing there is, and it is exact for an upgrade nobody edited after completing
-- it. One edited since reads as completed at that edit, which is the order the dashboard showed before.
UPDATE health_upgrades SET completed_at = updated_at WHERE status = 'COMPLETED';
