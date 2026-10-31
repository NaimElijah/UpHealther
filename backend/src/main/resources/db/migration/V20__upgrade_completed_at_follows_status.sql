-- An upgrade has a completion time exactly when it is COMPLETED (FR-27, ADR-080, ADR-073).
--
-- V19 added completed_at, and the domain sets it at the one transition into COMPLETED, which is terminal.
-- Nothing in the schema tied the two: a stray UPDATE could mark an upgrade COMPLETED with no stamp, and it
-- would never appear among the dashboard's recent completions, or stamp an upgrade that never completed.
-- V19 backfilled every COMPLETED row, so no row already here is outside the rule.
--
-- Added NOT VALID and then validated, as V12's constraints are, so that once the table holds rows the
-- VALIDATE can move to a migration of its own without blocking writes (ADR-072).

-- Bounds the wait for the locks below (ADR-072).
SET LOCAL lock_timeout = '5s';

ALTER TABLE health_upgrades ADD CONSTRAINT health_upgrades_completed_at_check
    CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL)) NOT VALID;
ALTER TABLE health_upgrades VALIDATE CONSTRAINT health_upgrades_completed_at_check;
