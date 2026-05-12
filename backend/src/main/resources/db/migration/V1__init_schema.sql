-- V1: Initial schema

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_users_email ON users(email);

CREATE TABLE health_areas (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    priority INTEGER,
    icon VARCHAR(100),
    color VARCHAR(50),
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_health_areas_user_id ON health_areas(user_id);

CREATE TABLE health_upgrades (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    area_id UUID REFERENCES health_areas(id) ON DELETE SET NULL,
    title VARCHAR(255) NOT NULL,
    description TEXT,
    type VARCHAR(50) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'IDEA',
    difficulty VARCHAR(50),
    planned_start_date DATE,
    actual_start_date DATE,
    target_end_date DATE,
    motivation TEXT,
    success_criteria TEXT,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_health_upgrades_user_id ON health_upgrades(user_id);
CREATE INDEX idx_health_upgrades_area_id ON health_upgrades(area_id);
CREATE INDEX idx_health_upgrades_status ON health_upgrades(status);

CREATE TABLE tracking_configs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    upgrade_id UUID NOT NULL UNIQUE REFERENCES health_upgrades(id) ON DELETE CASCADE,
    tracking_type VARCHAR(50) NOT NULL,
    frequency VARCHAR(50),
    target_numeric_value DOUBLE PRECISION,
    target_unit VARCHAR(100),
    required_daily BOOLEAN
);

CREATE INDEX idx_tracking_configs_upgrade_id ON tracking_configs(upgrade_id);

CREATE TABLE progress_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    upgrade_id UUID NOT NULL REFERENCES health_upgrades(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    date DATE NOT NULL,
    completed BOOLEAN,
    numeric_value DOUBLE PRECISION,
    unit VARCHAR(100),
    rating INTEGER,
    note TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uq_progress_upgrade_date UNIQUE (upgrade_id, date)
);

CREATE INDEX idx_progress_entries_upgrade_id ON progress_entries(upgrade_id);
CREATE INDEX idx_progress_entries_user_id ON progress_entries(user_id);
CREATE INDEX idx_progress_entries_date ON progress_entries(date);

CREATE TABLE reminders (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    upgrade_id UUID NOT NULL REFERENCES health_upgrades(id) ON DELETE CASCADE,
    reminder_time TIME,
    days_of_week VARCHAR(50),
    enabled BOOLEAN,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_reminders_upgrade_id ON reminders(upgrade_id);

CREATE TABLE reflections (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    upgrade_id UUID NOT NULL REFERENCES health_upgrades(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    date DATE NOT NULL,
    difficulty_rating INTEGER,
    benefit_rating INTEGER,
    what_worked TEXT,
    what_did_not_work TEXT,
    next_adjustment TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_reflections_upgrade_id ON reflections(upgrade_id);
CREATE INDEX idx_reflections_user_id ON reflections(user_id);
CREATE INDEX idx_reflections_date ON reflections(date);
