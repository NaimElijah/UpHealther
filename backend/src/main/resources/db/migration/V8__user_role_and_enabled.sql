-- Roles (NFR-33) and the switch that turns an account off (NFR-34).
--
-- Both columns carry a default, so the rows already in the table acquire one without a backfill, and
-- both are NOT NULL, so "no role" and "neither enabled nor disabled" are unrepresentable. The defaults
-- stay on the columns rather than being dropped afterwards: registration writes neither field, and an
-- account that arrived without a role would be unauthorised rather than an ordinary user.
ALTER TABLE users ADD COLUMN role VARCHAR(16) NOT NULL DEFAULT 'USER';
ALTER TABLE users ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE;

-- The set of roles is written twice - once as a Java enum, once here - because Hibernate's
-- ddl-auto: validate checks a column's type and never its contents. Without this constraint a stray
-- UPDATE stores a role the application cannot read back, and the row fails on load rather than on write.
ALTER TABLE users ADD CONSTRAINT users_role_check CHECK (role IN ('USER', 'ADMIN'));
