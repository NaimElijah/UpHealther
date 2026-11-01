-- The demo account V2 seeded leaves every database, and everything it owns goes with it (#84).
--
-- V2 wrote it into every database the application migrates, production included, and V3 and V6 set its
-- password to one this repository publishes. Anyone could sign in to it. Its id is fixed, so it is the one
-- account id an operator can know on a fresh install, and naming it in ADMIN_BOOTSTRAP_USER_ID would make
-- that published sign-in an administrator. An applied migration is never edited, so V2 cannot stop
-- writing it; this deletes it instead.
--
-- Matched on the fixed id alone, never on the address: an account holding the address under another id
-- was registered by somebody, and is theirs. Every foreign key to users is ON DELETE CASCADE (ADR-063), so
-- the account's areas, upgrades, tracking configurations, progress, reflections, reminders, notifications
-- and sessions go with it, and each session's spent tokens with the session.
--
-- A development run gets the account back from db/demo/afterMigrate__demo_account.sql, a Flyway callback
-- that only backend/config/application.yml and docker-compose.yml name. The jar's own configuration never
-- does, so no deployment holds it again.

-- Bounds the wait for the row locks the delete and its cascades take (ADR-072).
SET LOCAL lock_timeout = '5s';

DELETE FROM users WHERE id = 'a0000000-0000-0000-0000-000000000001';
