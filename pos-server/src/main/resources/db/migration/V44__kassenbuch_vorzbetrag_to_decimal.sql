-- Migrate vorz_betrag from VARCHAR(14) formatted string ("+100,50") to DECIMAL(12,2) numeric value.
ALTER TABLE kassenbuch_line_item ADD COLUMN vorz_betrag_new DECIMAL(12, 2);

UPDATE kassenbuch_line_item
SET vorz_betrag_new = CAST(REPLACE(vorz_betrag, ',', '.') AS DECIMAL(12, 2));

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM kassenbuch_line_item WHERE vorz_betrag_new IS NULL AND vorz_betrag IS NOT NULL) THEN
        RAISE EXCEPTION 'Migration failed: some vorz_betrag values could not be converted to decimal';
    END IF;
END $$;

ALTER TABLE kassenbuch_line_item DROP COLUMN vorz_betrag;
ALTER TABLE kassenbuch_line_item RENAME COLUMN vorz_betrag_new TO vorz_betrag;
ALTER TABLE kassenbuch_line_item ALTER COLUMN vorz_betrag SET NOT NULL;
