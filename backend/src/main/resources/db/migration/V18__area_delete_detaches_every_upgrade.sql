-- Deleting an area detaches every upgrade filed under it, whoever owns the upgrade (#340; ADR-073).
--
-- V13 replaced V1's single-column area key with health_upgrades_area_owner_fkey, on (area_id, user_id). A
-- composite key's referential action matches every key column, so its ON DELETE SET NULL (area_id) runs as
-- "WHERE area_id = $1 AND user_id = $2", and clears only the area's owner's upgrades. A row filed before
-- BR-18 under another user's area does not match: deleting that area left it naming an area with no row
-- behind it, where V1's key cleared it (FR-8).
--
-- So the single-column key comes back beside the composite one, under V1's name. Its SET NULL matches
-- area_id alone and detaches every row; the composite key goes on refusing a foreign area on every write
-- that sets one. On a row both keys match, whichever action runs second finds area_id already NULL and
-- changes nothing, so V14's trigger moves the version once. idx_health_upgrades_area_id (V11) leads with
-- area_id, so the delete's lookup is served by an index.
--
-- Added NOT VALID and then validated, as ADR-072 lays out. V1's key held every area_id to an existing area
-- until V13, and V13 and this migration ship in the same release, so only a database that ran V13 without
-- this one can hold a row naming a deleted area. Its VALIDATE then fails the start, as any row outside a
-- constraint does (ADR-073, decision 4).

-- Bounds the wait for the locks ADD CONSTRAINT takes on health_upgrades and health_areas (ADR-072).
SET LOCAL lock_timeout = '5s';

ALTER TABLE health_upgrades ADD CONSTRAINT health_upgrades_area_id_fkey
    FOREIGN KEY (area_id) REFERENCES health_areas (id) ON DELETE SET NULL NOT VALID;
ALTER TABLE health_upgrades VALIDATE CONSTRAINT health_upgrades_area_id_fkey;
