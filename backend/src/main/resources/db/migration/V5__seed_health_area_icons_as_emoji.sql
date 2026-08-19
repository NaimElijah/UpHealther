-- The seeded health areas carried Material Symbols ligature names in `icon` ('water_drop',
-- 'fitness_center', ...), but no icon font is loaded anywhere in the frontend and the form labels the
-- field "Icon (emoji)". The page drew those names as their own literal text. They are rewritten here as
-- the emoji they were always meant to stand for.
--
-- A new migration rather than an edit to V2: Flyway stores a checksum per applied version, so changing
-- an already-applied file makes every existing database refuse to start with a checksum mismatch.
--
-- Each row is matched on its id *and* on the value being replaced, so an account that has already set
-- its own icon keeps it. Every glyph is a single code point with default emoji presentation, so nothing
-- depends on how a variation selector survives the driver or the column.

UPDATE health_areas SET icon = '💧' WHERE id = 'b0000000-0000-0000-0000-000000000001' AND icon = 'water_drop';
UPDATE health_areas SET icon = '🏃' WHERE id = 'b0000000-0000-0000-0000-000000000002' AND icon = 'fitness_center';
UPDATE health_areas SET icon = '🌙' WHERE id = 'b0000000-0000-0000-0000-000000000003' AND icon = 'bedtime';
UPDATE health_areas SET icon = '🥗' WHERE id = 'b0000000-0000-0000-0000-000000000004' AND icon = 'restaurant';
UPDATE health_areas SET icon = '🧘' WHERE id = 'b0000000-0000-0000-0000-000000000005' AND icon = 'self_improvement';
UPDATE health_areas SET icon = '🌿' WHERE id = 'b0000000-0000-0000-0000-000000000006' AND icon = 'eco';
