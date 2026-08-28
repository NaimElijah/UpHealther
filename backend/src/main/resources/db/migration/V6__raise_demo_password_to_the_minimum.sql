-- V6: Raise the demo user's password to the minimum the API now enforces.
-- BR-17 sets the minimum at eight characters, and "demo123" is seven. The account kept working
-- because a seeded hash never passes through validation, but shipping a credential the system itself
-- would refuse is the kind of contradiction nobody should have to discover by trying it.
-- The password is now "demo1234". Applied as an UPDATE rather than by editing V2 or V3, whose Flyway
-- checksums cover their comments as well as their SQL and must not change on an already-migrated
-- database.
UPDATE users
SET password_hash = '$2a$10$Wcg8FBqRw0X/W6jcdfKFs.SnWasGS/u.lOQmXhHc7Es79fgbw/zWy',
    updated_at = now()
WHERE email = 'demo@healthupgrades.com';
