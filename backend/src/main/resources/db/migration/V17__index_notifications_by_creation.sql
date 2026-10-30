-- The index the nightly retention job reads through (#340, D6.8; NFR-60, ADR-075).
--
-- Each night the job deletes, a batch at a time, the notifications created more than ninety days ago,
-- oldest first. Without an index on created_at every batch would scan the whole table for its rows.
-- idx_notifications_user_created leads with user_id, so it cannot serve a read across every user.
--
-- The check-in sweep's dedup read, findUserIdsNotifiedAfter, filters on type and on created_at after the
-- start of today, and no index led with either column before, so it can read one day's rows through
-- this one too.
--
-- Plain DDL inside Flyway's transaction rather than CONCURRENTLY: no production database holds rows yet
-- (ADR-072).

-- Bounds the wait for the lock below (ADR-072).
SET LOCAL lock_timeout = '5s';

CREATE INDEX idx_notifications_created_at ON notifications (created_at);
