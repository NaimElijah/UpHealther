-- V3: Fix the demo user's password hash.
-- The hash seeded in V2 was a placeholder that did not match "demo123", so the demo login failed.
-- This is the correct BCrypt hash for "demo123". Applied as an UPDATE (not by editing V2) so Flyway
-- checksums on already-migrated databases stay intact.
UPDATE users
SET password_hash = '$2a$10$jNbye8EhQ.PcgwIBRD.9LujAo51uufY4idX2Hm1mtkBThj2/o5I4e',
    updated_at = now()
WHERE email = 'demo@healthupgrades.com';
