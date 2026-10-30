-- A child row's owner is its parent's owner, and the schema holds it (#340, D6.11; ADR-073).
--
-- progress_entries.user_id and reflections.user_id copy the owner of the upgrade they belong to, and the
-- owner-scoped reads filter on them (BR-15). health_upgrades.area_id must name an area of the upgrade's own
-- owner (BR-18). Each held only because the one writer stamps it correctly. A composite foreign key on
-- (parent id, owner) makes a row whose owner differs from its parent's unrepresentable.

-- Bounds the wait for every lock below (ADR-072).
SET LOCAL lock_timeout = '5s';

-- What the composite keys reference. A foreign key needs a unique constraint over exactly its target
-- columns; id alone is already unique, so the pair is too, and these add only the index that says so.
ALTER TABLE health_upgrades ADD CONSTRAINT health_upgrades_id_user_id_key UNIQUE (id, user_id);
ALTER TABLE health_areas ADD CONSTRAINT health_areas_id_user_id_key UNIQUE (id, user_id);

-- Each single-column key is replaced, keeping its ON DELETE (ADR-063), and named rather than left to
-- PostgreSQL, so that code can tell a violation of one from any other. Added NOT VALID and then validated,
-- as ADR-072 lays out; a row whose owner already differs fails the VALIDATE, and so the start.
ALTER TABLE progress_entries DROP CONSTRAINT progress_entries_upgrade_id_fkey;
ALTER TABLE progress_entries ADD CONSTRAINT progress_entries_upgrade_owner_fkey
    FOREIGN KEY (upgrade_id, user_id) REFERENCES health_upgrades (id, user_id) ON DELETE CASCADE NOT VALID;
ALTER TABLE progress_entries VALIDATE CONSTRAINT progress_entries_upgrade_owner_fkey;

ALTER TABLE reflections DROP CONSTRAINT reflections_upgrade_id_fkey;
ALTER TABLE reflections ADD CONSTRAINT reflections_upgrade_owner_fkey
    FOREIGN KEY (upgrade_id, user_id) REFERENCES health_upgrades (id, user_id) ON DELETE CASCADE NOT VALID;
ALTER TABLE reflections VALIDATE CONSTRAINT reflections_upgrade_owner_fkey;

-- SET NULL (area_id), PostgreSQL 15's column list: deleting an area clears only the upgrade's area_id
-- (FR-8). A plain SET NULL would clear user_id too, which is NOT NULL, and the delete would fail.
--
-- Deliberately left NOT VALID, with no VALIDATE. A row filed before BR-18 may name another user's area,
-- and UpgradeService.update keeps such a row editable by checking only a changed area. Validating would
-- refuse to start on a database holding one. Unvalidated, the key still checks every new row, and every
-- update that changes area_id or user_id; an update that leaves both as they were, on a row an earlier
-- transaction wrote, is not re-checked.
ALTER TABLE health_upgrades DROP CONSTRAINT health_upgrades_area_id_fkey;
ALTER TABLE health_upgrades ADD CONSTRAINT health_upgrades_area_owner_fkey
    FOREIGN KEY (area_id, user_id) REFERENCES health_areas (id, user_id) ON DELETE SET NULL (area_id) NOT VALID;
