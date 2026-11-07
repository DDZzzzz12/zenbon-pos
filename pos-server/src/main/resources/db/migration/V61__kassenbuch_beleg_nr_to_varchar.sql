-- Migrate beleg_nr from BIGINT to VARCHAR with YYYY-MM-NNNN format
-- and remove the unique constraint (shop_id, beleg_nr)

-- Drop the existing unique constraint on (shop_id, beleg_nr)
ALTER TABLE kassenbuch_header DROP CONSTRAINT IF EXISTS kassenbuch_header_shop_id_beleg_nr_unique;

-- Drop the standalone beleg_nr index
DROP INDEX IF EXISTS kassenbuch_header_beleg_nr;

-- Convert existing beleg_nr values to YYYY-MM-NNNN format based on created_at
ALTER TABLE kassenbuch_header
    ALTER COLUMN beleg_nr TYPE VARCHAR(20)
    USING TO_CHAR(created_at, 'YYYY-MM') || '-' || LPAD(beleg_nr::TEXT, 4, '0');

-- Re-create performance index on beleg_nr (non-unique)
CREATE INDEX kassenbuch_header_beleg_nr ON kassenbuch_header (beleg_nr);
