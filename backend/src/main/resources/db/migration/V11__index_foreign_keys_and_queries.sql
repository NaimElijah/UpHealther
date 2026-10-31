-- The indexes the queries and the foreign keys use, and none they do not (#340, D6.3 and D6.14).
--
-- Plain DDL inside Flyway's transaction rather than CONCURRENTLY: no production database holds rows yet,
-- so every statement here is instant, and a failure rolls the whole migration back (ADR-072).

-- Bounds the wait for every lock below, so a long transaction on one of these tables fails the start
-- instead of queueing the table's traffic behind this migration (ADR-072).
SET LOCAL lock_timeout = '5s';

-- notifications.related_upgrade_id references health_upgrades ON DELETE SET NULL, and had no index, so
-- every upgrade delete scanned the whole of notifications for the rows to detach (ADR-063, Decision 3).
-- Partial, because a notification that names no upgrade, such as the daily check-in or one already
-- detached, has nothing for the trigger to find. The trigger's related_upgrade_id = $1 implies IS NOT NULL,
-- so the planner can use the partial index for it.
CREATE INDEX idx_notifications_related_upgrade_id ON notifications (related_upgrade_id)
    WHERE related_upgrade_id IS NOT NULL;

-- Serves findByUserIdAndDate and findByUserIdAndDateBetween, the dashboard's reads of one user's day and
-- week, which idx_progress_entries_user_id answered only by user before filtering the dates. It also
-- leads with user_id, so the foreign key to users is served by it.
CREATE INDEX idx_progress_entries_user_date ON progress_entries (user_id, date);

-- Serves the administrator's account list, UserRepositoryAdapter.findAll, which pages ORDER BY
-- created_at, id. Without it each page sorts the whole table to find its rows.
CREATE INDEX idx_users_created_at_id ON users (created_at, id);

-- Each index below repeats the leading column of another, which answers every query it did, so it only
-- cost each write its upkeep.

-- users_email_key, the index behind email's UNIQUE constraint, serves findByEmail and existsByEmail.
DROP INDEX idx_users_email;

-- tracking_configs_upgrade_id_key, behind upgrade_id's UNIQUE constraint, serves findByUpgradeId,
-- findByUpgradeIdIn and the cascade from health_upgrades.
DROP INDEX idx_tracking_configs_upgrade_id;

-- uq_progress_upgrade_date (upgrade_id, date) serves findByUpgradeId, findByUpgradeIdOrderByDateDesc,
-- findByUserIdAndUpgradeIdIn and the cascade from health_upgrades.
DROP INDEX idx_progress_entries_upgrade_id;

-- idx_progress_entries_user_date, created above, serves every read by user_id and the cascade from users.
DROP INDEX idx_progress_entries_user_id;

-- idx_notifications_user_unread (user_id, is_read) and idx_notifications_user_created
-- (user_id, created_at) both lead with user_id, and serve every read by it and the cascade from users.
DROP INDEX idx_notifications_user_id;

-- Reflections are read only by upgrade_id (findByUpgradeIdOrderByDateDescCreatedAtDesc) and by user_id;
-- nothing filters or sorts them by date alone, so this index served no query.
DROP INDEX idx_reflections_date;

-- idx_progress_entries_date stays: findUserIdsWithEntriesOn, the check-in sweep's read of who logged
-- anything on a day across every user, filters by date alone.
