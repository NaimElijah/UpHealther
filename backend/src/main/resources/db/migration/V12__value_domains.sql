-- The schema refuses a value the domain cannot hold (#340, D6.10; ADR-073).
--
-- Each set of enum values below is written twice, once as a Java enum and once here, as V8 did for
-- users.role: Hibernate's ddl-auto: validate checks a column's type and never its contents. Without the
-- constraint a stray write stores a status the application cannot read back, and every list that meets
-- the row fails as a 500. EnumColumnCheckContractTest fails the build when a list here and its enum's
-- constants differ. A nullable column stays nullable: a CHECK passes NULL.
--
-- Each constraint is added NOT VALID and then validated, so that once a table holds rows the VALIDATE can
-- move to a migration of its own without blocking writes (ADR-072). A row already outside a domain fails
-- the VALIDATE, and so the start, rather than being changed silently: what it should become is a decision
-- about someone's data.

-- Bounds the wait for every lock below (ADR-072).
SET LOCAL lock_timeout = '5s';

-- UpgradeStatus.
ALTER TABLE health_upgrades ADD CONSTRAINT health_upgrades_status_check
    CHECK (status IN ('IDEA', 'PLANNED', 'ACTIVE', 'PAUSED', 'COMPLETED', 'ABANDONED')) NOT VALID;
ALTER TABLE health_upgrades VALIDATE CONSTRAINT health_upgrades_status_check;

-- UpgradeType. PROTOCOL is deprecated in Java but still a constant, and rows may carry it.
ALTER TABLE health_upgrades ADD CONSTRAINT health_upgrades_type_check
    CHECK (type IN ('HABIT', 'ONE_TIME_ACTION', 'PRODUCT_REPLACEMENT', 'ROUTINE', 'GOAL', 'EXPERIMENT',
                    'LEARNING_TASK', 'MEDICAL_PREVENTIVE', 'PROTOCOL')) NOT VALID;
ALTER TABLE health_upgrades VALIDATE CONSTRAINT health_upgrades_type_check;

-- Difficulty. Nullable: an upgrade need not have one.
ALTER TABLE health_upgrades ADD CONSTRAINT health_upgrades_difficulty_check
    CHECK (difficulty IN ('EASY', 'MEDIUM', 'HARD')) NOT VALID;
ALTER TABLE health_upgrades VALIDATE CONSTRAINT health_upgrades_difficulty_check;

-- TrackingType.
ALTER TABLE tracking_configs ADD CONSTRAINT tracking_configs_tracking_type_check
    CHECK (tracking_type IN ('BOOLEAN', 'NUMERIC', 'RATING', 'TEXT')) NOT VALID;
ALTER TABLE tracking_configs VALIDATE CONSTRAINT tracking_configs_tracking_type_check;

-- Frequency. Nullable.
ALTER TABLE tracking_configs ADD CONSTRAINT tracking_configs_frequency_check
    CHECK (frequency IN ('DAILY', 'WEEKLY', 'MONTHLY')) NOT VALID;
ALTER TABLE tracking_configs VALIDATE CONSTRAINT tracking_configs_frequency_check;

-- NotificationType.
ALTER TABLE notifications ADD CONSTRAINT notifications_type_check
    CHECK (type IN ('UPGRADE_CREATED', 'UPGRADE_PLANNED', 'UPGRADE_ACTIVATED', 'UPGRADE_PAUSED',
                    'UPGRADE_COMPLETED', 'UPGRADE_ABANDONED', 'STREAK_ACHIEVED', 'UPGRADE_OVERDUE',
                    'REFLECTION_ADDED', 'REMINDER', 'CHECKIN_REMINDER')) NOT VALID;
ALTER TABLE notifications VALIDATE CONSTRAINT notifications_type_check;

-- NotificationCategory.
ALTER TABLE notifications ADD CONSTRAINT notifications_category_check
    CHECK (category IN ('INFO', 'SUCCESS', 'WARNING', 'REMINDER')) NOT VALID;
ALTER TABLE notifications VALIDATE CONSTRAINT notifications_category_check;

-- BR-19: a rating is a whole number from 1 to 5. The columns are INTEGER, so "whole" is the type's.
-- Nullable: an entry or a reflection need not be rated.
ALTER TABLE progress_entries ADD CONSTRAINT progress_entries_rating_check
    CHECK (rating BETWEEN 1 AND 5) NOT VALID;
ALTER TABLE progress_entries VALIDATE CONSTRAINT progress_entries_rating_check;

ALTER TABLE reflections ADD CONSTRAINT reflections_difficulty_rating_check
    CHECK (difficulty_rating BETWEEN 1 AND 5) NOT VALID;
ALTER TABLE reflections VALIDATE CONSTRAINT reflections_difficulty_rating_check;

ALTER TABLE reflections ADD CONSTRAINT reflections_benefit_rating_check
    CHECK (benefit_rating BETWEEN 1 AND 5) NOT VALID;
ALTER TABLE reflections VALIDATE CONSTRAINT reflections_benefit_rating_check;

-- BR-20: a numeric progress value is never negative. Nullable: only a numeric entry carries one.
ALTER TABLE progress_entries ADD CONSTRAINT progress_entries_numeric_value_check
    CHECK (numeric_value >= 0) NOT VALID;
ALTER TABLE progress_entries VALIDATE CONSTRAINT progress_entries_numeric_value_check;

-- A reminder without a time can never fire, and one with neither true nor false is neither on nor off.
-- ReminderRequest requires the time and Reminder.create always writes the flag, so only a write that
-- bypassed the domain could store either; ReminderDto already declares both non-null. SET NOT NULL scans
-- the table under its lock, which is instant while it holds no data (ADR-072).
ALTER TABLE reminders ALTER COLUMN reminder_time SET NOT NULL;
ALTER TABLE reminders ALTER COLUMN enabled SET NOT NULL;
