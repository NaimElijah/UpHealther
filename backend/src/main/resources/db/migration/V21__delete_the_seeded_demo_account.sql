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
-- A development run gets the account back from backend/config/db/demo/afterMigrate__demo_account.sql, a
-- Flyway callback outside the jar and the image, which only backend/config/application.yml and
-- docker-compose.yml name. No deployment carries the script, so none holds the account again.

-- Bounds the wait for the row locks the delete and its cascades take (ADR-072).
--
-- No up-front LOCK TABLE, unlike ADR-072's DDL migrations. The delete and its cascades take only ROW
-- EXCLUSIVE on each table, which no statement the application runs conflicts with, and row locks on the
-- demo account's own rows, which no other account's request touches. A lock strong enough to fix an order,
-- SHARE ROW EXCLUSIVE or above, would block every write to users and the nine tables its cascade reaches
-- for the whole migration. The timeout above bounds each row wait instead.
SET LOCAL lock_timeout = '5s';

DELETE FROM users WHERE id = 'a0000000-0000-0000-0000-000000000001';
