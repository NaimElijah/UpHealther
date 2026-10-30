-- The per-minute reminder dispatch reads only the enabled reminders set within the current minute
-- (#340, D6.12), and this is the index it reads through.
--
-- Until now the sweep loaded every enabled reminder of every user once a minute and filtered on the hour
-- and minute in Java, so each run cost a scan of the whole table, however few reminders were due.
-- Partial on enabled, which is the query's other condition: a switched-off reminder is never dispatched,
-- so it need not be in the index at all.
--
-- Plain DDL inside Flyway's transaction rather than CONCURRENTLY: no production database holds rows yet,
-- so the build is instant (ADR-072).

-- Bounds the wait for the lock below (ADR-072).
SET LOCAL lock_timeout = '5s';

CREATE INDEX idx_reminders_enabled_time ON reminders (reminder_time) WHERE enabled;
