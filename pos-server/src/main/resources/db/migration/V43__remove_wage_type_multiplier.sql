ALTER TABLE wage_type DROP COLUMN IF EXISTS multiplier;

-- Seed default wage type if none exists
INSERT INTO wage_type (id, code, name, description, is_default)
SELECT gen_random_uuid(), 200, 'Stundenlohn', 'Standard-Lohnart für geleistete Arbeitsstunden', true
WHERE NOT EXISTS (SELECT 1 FROM wage_type WHERE is_default = true);
