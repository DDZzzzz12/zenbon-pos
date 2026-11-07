-- System records the application depends on can no longer be edited or deleted by an operator.
-- The virtual location types (Gutschein id 4, Schnellverkauf id 5, seeded by V54) and the virtual
-- VOUCHER table are flagged; the services refuse update/delete of a flagged row (409
-- SYSTEM_RECORD_PROTECTED) and the clients hide the controls. User-created rows stay editable.
--
-- A fresh install never runs the UPDATEs below: SchemaManager.bootstrapInitialData and
-- ConfigurationRepository.ensureVoucherTable set the flag when they seed those rows.
ALTER TABLE location_types ADD COLUMN IF NOT EXISTS is_system BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE r_table ADD COLUMN IF NOT EXISTS is_system BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE location_types SET is_system = TRUE WHERE id IN (4, 5);
UPDATE r_table SET is_system = TRUE WHERE table_number = 'VOUCHER' AND location_type_id = 4;
