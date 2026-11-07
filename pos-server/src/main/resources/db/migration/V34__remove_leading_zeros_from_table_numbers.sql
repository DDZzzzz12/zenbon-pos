-- Remove leading zeros from table numbers
-- T001 -> 1 (strip T prefix and leading zeros)
-- TE001 -> TE1 (keep TE prefix, strip leading zeros from numeric part)
-- A001 -> A1 (keep A prefix, strip leading zeros from numeric part)

-- Indoor tables: T prefix removed entirely, leading zeros stripped
UPDATE r_table
SET table_number = CAST(CAST(SUBSTRING(table_number FROM 2) AS INTEGER) AS VARCHAR)
WHERE table_number ~ '^T[0-9]+$';

-- Outdoor tables: keep TE prefix, strip leading zeros from numeric part
UPDATE r_table
SET table_number = 'TE' || CAST(CAST(SUBSTRING(table_number FROM 3) AS INTEGER) AS VARCHAR)
WHERE table_number ~ '^TE[0-9]+$';

-- Takeaway tables: keep A prefix, strip leading zeros from numeric part
UPDATE r_table
SET table_number = 'A' || CAST(CAST(SUBSTRING(table_number FROM 2) AS INTEGER) AS VARCHAR)
WHERE table_number ~ '^A[0-9]+$';
