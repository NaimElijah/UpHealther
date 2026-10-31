-- Deleting a health area moves the version of every upgrade it detaches (#340, D6.2; ADR-073).
--
-- health_upgrades_area_owner_fkey (V13) detaches an area's upgrades with ON DELETE SET NULL (area_id).
-- That is an UPDATE PostgreSQL runs itself, so Hibernate never sees it and the optimistic-lock version
-- stays where it was. A request that loaded an upgrade before the delete and saved it after then passed
-- the version check and wrote the deleted area's id back; the area key refused that at the commit, and
-- the request was answered 500 where a lost race is a 409 (BR-14).
--
-- This trigger moves the version for exactly that write: area_id going from a value to NULL with the
-- version left as it was. Hibernate's own updates always move the version, so they never meet the
-- condition, and an edit that clears the area counts once. The stale save then matches no row, and is
-- refused at its commit as the optimistic-lock conflict every other lost race is, which the audit trail
-- records as a refusal on the rollback path. The referential action's UPDATE names area_id, so the
-- column-list trigger fires for it; UpgradePersistenceIT shows it does.

-- Bounds the wait for the lock CREATE TRIGGER takes on health_upgrades (ADR-072).
SET LOCAL lock_timeout = '5s';

CREATE FUNCTION health_upgrades_bump_version_on_area_detach() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    NEW.version := OLD.version + 1;
    RETURN NEW;
END
$$;

CREATE TRIGGER health_upgrades_area_detach_bumps_version
    BEFORE UPDATE OF area_id ON health_upgrades
    FOR EACH ROW
    WHEN (OLD.area_id IS NOT NULL AND NEW.area_id IS NULL AND NEW.version = OLD.version)
    EXECUTE FUNCTION health_upgrades_bump_version_on_area_detach();
