-- The demo account, for a development run only (#84): demo@healthupgrades.com, with six areas, six
-- upgrades and four tracking configurations, so a fresh clone has something to look at.
--
-- A Flyway callback, not a migration. Flyway runs it after every migrate and never records it in
-- flyway_schema_history, so a database it ran on still validates against a configuration without it.
--
-- It lives under backend/config/, which neither the jar nor the image ever contains, as the development
-- signing key does (ADR-042), and it is read from the filesystem, never the classpath. A run from backend/
-- names it through backend/config/application.yml, as filesystem:config/db/demo; the compose stack mounts
-- this folder read-only into the backend container and names the mount. A deployment has neither the file
-- nor the folder: a callback location copied into one names a directory that is not there, which Flyway
-- skips with an error in the log, and no account is created (DemoAccountSeedContractTest).
--
-- Created whole or not at all. When the account already exists, or another account holds its address, the
-- block returns before writing anything, so whatever an owner deleted or changed stays that way across
-- every restart, and an account somebody registered under the address is left alone.
--
-- Written against the schema as of V21, and as the domain would write each row, where V2 did not:
-- - an ACTIVE upgrade has a start date, as HealthUpgrade.activate always sets one, where V2's had none.
--   Each takes its planned start date, which the activate endpoint accepts as the day it started;
-- - the areas carry V5's emoji, not V2's icon-font names;
-- - the password is V6's, which meets BR-17's eight characters, not V2's placeholder.
-- The ids are fixed, and V2's: the rows below name each other by them, and the account's own id is what
-- makes a second run find it already there.

-- Bounds the wait for every lock the inserts take, as every migration from V10 on bounds its own (ADR-072).
SET LOCAL lock_timeout = '5s';

DO $$
DECLARE
    demo_account CONSTANT UUID := 'a0000000-0000-0000-0000-000000000001';
    accounts_created INTEGER;
BEGIN
    -- No conflict target, so the primary key and the unique address both count as a conflict.
    INSERT INTO users (id, name, email, password_hash, role, enabled)
    VALUES (demo_account, 'Demo User', 'demo@healthupgrades.com',
            '$2a$10$Wcg8FBqRw0X/W6jcdfKFs.SnWasGS/u.lOQmXhHc7Es79fgbw/zWy', 'USER', TRUE)
    ON CONFLICT DO NOTHING;

    GET DIAGNOSTICS accounts_created = ROW_COUNT;
    IF accounts_created = 0 THEN
        RETURN;
    END IF;

    INSERT INTO health_areas (id, user_id, name, description, priority, icon, color)
    VALUES
        ('b0000000-0000-0000-0000-000000000001', demo_account, 'Hydration',
         'Daily water intake and hydration habits', 1, '💧', '#2196F3'),
        ('b0000000-0000-0000-0000-000000000002', demo_account, 'Fitness',
         'Physical exercise and movement', 2, '🏃', '#4CAF50'),
        ('b0000000-0000-0000-0000-000000000003', demo_account, 'Sleep',
         'Sleep quality and schedule', 3, '🌙', '#9C27B0'),
        ('b0000000-0000-0000-0000-000000000004', demo_account, 'Nutrition',
         'Diet and eating habits', 4, '🥗', '#FF9800'),
        ('b0000000-0000-0000-0000-000000000005', demo_account, 'Mental Health',
         'Mindfulness and stress management', 5, '🧘', '#E91E63'),
        ('b0000000-0000-0000-0000-000000000006', demo_account, 'Healthy Products',
         'Replacing harmful products with healthy alternatives', 6, '🌿', '#009688');

    INSERT INTO health_upgrades (id, user_id, area_id, title, description, type, status, difficulty,
                                 planned_start_date, actual_start_date, motivation)
    VALUES
        ('c0000000-0000-0000-0000-000000000001', demo_account, 'b0000000-0000-0000-0000-000000000001',
         'Drink 2L water daily', 'Track daily water intake to reach 2 liters', 'HABIT', 'ACTIVE', 'EASY',
         CURRENT_DATE - 7, CURRENT_DATE - 7, 'Stay hydrated for better energy and focus'),
        ('c0000000-0000-0000-0000-000000000002', demo_account, 'b0000000-0000-0000-0000-000000000002',
         'Walk 20 minutes after dinner', 'Post-dinner walk for digestion and activity', 'HABIT', 'ACTIVE', 'EASY',
         CURRENT_DATE - 5, CURRENT_DATE - 5, 'Improve digestion and build a daily movement habit'),
        ('c0000000-0000-0000-0000-000000000003', demo_account, 'b0000000-0000-0000-0000-000000000005',
         'Meditate 5 minutes every morning', 'Start each day with a short meditation session', 'HABIT', 'PLANNED',
         'MEDIUM', CURRENT_DATE + 3, NULL, 'Reduce stress and improve focus throughout the day'),
        ('c0000000-0000-0000-0000-000000000004', demo_account, 'b0000000-0000-0000-0000-000000000004',
         'Cook 3 homemade meals per week', 'Prepare healthy homemade meals to control ingredients', 'GOAL',
         'ACTIVE', 'MEDIUM', CURRENT_DATE - 10, CURRENT_DATE - 10, 'Eat healthier and save money'),
        ('c0000000-0000-0000-0000-000000000005', demo_account, 'b0000000-0000-0000-0000-000000000006',
         'Replace dish soap with natural alternative', 'Switch to eco-friendly dish soap without harsh chemicals',
         'EXPERIMENT', 'IDEA', 'EASY', NULL, NULL, 'Reduce chemical exposure in the home'),
        ('c0000000-0000-0000-0000-000000000006', demo_account, 'b0000000-0000-0000-0000-000000000003',
         'Sleep before 23:30', 'Establish a consistent sleep schedule by going to bed before 23:30', 'HABIT',
         'ACTIVE', 'HARD', CURRENT_DATE - 14, CURRENT_DATE - 14, 'Improve sleep quality and wake up refreshed');

    INSERT INTO tracking_configs (id, upgrade_id, tracking_type, frequency, target_numeric_value, target_unit,
                                  required_daily)
    VALUES
        ('d0000000-0000-0000-0000-000000000001', 'c0000000-0000-0000-0000-000000000001', 'NUMERIC', 'DAILY', 2.0,
         'liters', TRUE),
        ('d0000000-0000-0000-0000-000000000002', 'c0000000-0000-0000-0000-000000000002', 'BOOLEAN', 'DAILY', NULL,
         NULL, TRUE),
        ('d0000000-0000-0000-0000-000000000003', 'c0000000-0000-0000-0000-000000000004', 'NUMERIC', 'WEEKLY', 3.0,
         'meals', FALSE),
        ('d0000000-0000-0000-0000-000000000004', 'c0000000-0000-0000-0000-000000000006', 'BOOLEAN', 'DAILY', NULL,
         NULL, TRUE);
END
$$;
