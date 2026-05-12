-- V2: Seed data

-- Demo user: demo@healthupgrades.com / demo123
INSERT INTO users (id, name, email, password_hash, created_at, updated_at)
VALUES (
    'a0000000-0000-0000-0000-000000000001',
    'Demo User',
    'demo@healthupgrades.com',
    '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhem',
    now(),
    now()
);

-- Health areas
INSERT INTO health_areas (id, user_id, name, description, priority, icon, color, created_at, updated_at)
VALUES
    ('b0000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000001', 'Hydration', 'Daily water intake and hydration habits', 1, 'water_drop', '#2196F3', now(), now()),
    ('b0000000-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000001', 'Fitness', 'Physical exercise and movement', 2, 'fitness_center', '#4CAF50', now(), now()),
    ('b0000000-0000-0000-0000-000000000003', 'a0000000-0000-0000-0000-000000000001', 'Sleep', 'Sleep quality and schedule', 3, 'bedtime', '#9C27B0', now(), now()),
    ('b0000000-0000-0000-0000-000000000004', 'a0000000-0000-0000-0000-000000000001', 'Nutrition', 'Diet and eating habits', 4, 'restaurant', '#FF9800', now(), now()),
    ('b0000000-0000-0000-0000-000000000005', 'a0000000-0000-0000-0000-000000000001', 'Mental Health', 'Mindfulness and stress management', 5, 'self_improvement', '#E91E63', now(), now()),
    ('b0000000-0000-0000-0000-000000000006', 'a0000000-0000-0000-0000-000000000001', 'Healthy Products', 'Replacing harmful products with healthy alternatives', 6, 'eco', '#009688', now(), now());

-- Sample upgrades
INSERT INTO health_upgrades (id, user_id, area_id, title, description, type, status, difficulty, planned_start_date, motivation, version, created_at, updated_at)
VALUES
    ('c0000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000001', 'b0000000-0000-0000-0000-000000000001',
     'Drink 2L water daily', 'Track daily water intake to reach 2 liters', 'HABIT', 'ACTIVE', 'EASY',
     CURRENT_DATE - INTERVAL '7 days', 'Stay hydrated for better energy and focus', 0, now(), now()),

    ('c0000000-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000001', 'b0000000-0000-0000-0000-000000000002',
     'Walk 20 minutes after dinner', 'Post-dinner walk for digestion and activity', 'HABIT', 'ACTIVE', 'EASY',
     CURRENT_DATE - INTERVAL '5 days', 'Improve digestion and build a daily movement habit', 0, now(), now()),

    ('c0000000-0000-0000-0000-000000000003', 'a0000000-0000-0000-0000-000000000001', 'b0000000-0000-0000-0000-000000000005',
     'Meditate 5 minutes every morning', 'Start each day with a short meditation session', 'HABIT', 'PLANNED', 'MEDIUM',
     CURRENT_DATE + INTERVAL '3 days', 'Reduce stress and improve focus throughout the day', 0, now(), now()),

    ('c0000000-0000-0000-0000-000000000004', 'a0000000-0000-0000-0000-000000000001', 'b0000000-0000-0000-0000-000000000004',
     'Cook 3 homemade meals per week', 'Prepare healthy homemade meals to control ingredients', 'GOAL', 'ACTIVE', 'MEDIUM',
     CURRENT_DATE - INTERVAL '10 days', 'Eat healthier and save money', 0, now(), now()),

    ('c0000000-0000-0000-0000-000000000005', 'a0000000-0000-0000-0000-000000000001', 'b0000000-0000-0000-0000-000000000006',
     'Replace dish soap with natural alternative', 'Switch to eco-friendly dish soap without harsh chemicals', 'EXPERIMENT', 'IDEA', 'EASY',
     NULL, 'Reduce chemical exposure in the home', 0, now(), now()),

    ('c0000000-0000-0000-0000-000000000006', 'a0000000-0000-0000-0000-000000000001', 'b0000000-0000-0000-0000-000000000003',
     'Sleep before 23:30', 'Establish a consistent sleep schedule by going to bed before 23:30', 'HABIT', 'ACTIVE', 'HARD',
     CURRENT_DATE - INTERVAL '14 days', 'Improve sleep quality and wake up refreshed', 0, now(), now());

-- Tracking configs for active upgrades
INSERT INTO tracking_configs (id, upgrade_id, tracking_type, frequency, target_numeric_value, target_unit, required_daily)
VALUES
    ('d0000000-0000-0000-0000-000000000001', 'c0000000-0000-0000-0000-000000000001', 'NUMERIC', 'DAILY', 2.0, 'liters', true),
    ('d0000000-0000-0000-0000-000000000002', 'c0000000-0000-0000-0000-000000000002', 'BOOLEAN', 'DAILY', NULL, NULL, true),
    ('d0000000-0000-0000-0000-000000000003', 'c0000000-0000-0000-0000-000000000004', 'NUMERIC', 'WEEKLY', 3.0, 'meals', false),
    ('d0000000-0000-0000-0000-000000000004', 'c0000000-0000-0000-0000-000000000006', 'BOOLEAN', 'DAILY', NULL, NULL, true);
