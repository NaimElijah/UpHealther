-- V7: Store every email trimmed and lowercased, and make the database refuse any other form.
-- FR-4 compares addresses case-insensitively through EmailAddress.normalise. Rows written before this
-- migration were stored as typed, so two accounts can differ only by case or spacing. Merging them is a
-- decision about people's data rather than something a migration may do silently, so it stops instead.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM users
        GROUP BY lower(btrim(email))
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'V7: accounts exist whose emails differ only by case or surrounding spaces; resolve them before migrating';
    END IF;
END $$;

UPDATE users
SET email = lower(btrim(email)),
    updated_at = now()
WHERE email <> lower(btrim(email));

-- The constraint checks only what Java's Locale.ROOT lowercasing and any database collation agree on:
-- no surrounding spaces and no ASCII capitals. A lower() comparison would depend on the collation and
-- could refuse an address the application considers normal.
ALTER TABLE users
    ADD CONSTRAINT users_email_normalised CHECK (email = btrim(email) AND email !~ '[A-Z]');
