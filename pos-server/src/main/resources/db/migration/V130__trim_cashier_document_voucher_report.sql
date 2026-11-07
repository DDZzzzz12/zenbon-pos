-- Trim DOCUMENT_READ and VOUCHER_REPORT from the seeded CASHIER system group (product decision D1).
--
-- Stage 2b moved both down from TERMINAL_DEVICE into CASHIER only to keep every human role's reach
-- byte-identical, and left tightening to a later stage. No cashier/till/terminal flow calls a route
-- gated by either (till vouchers are VOUCHER_READ/VOUCHER_WRITE; the till's document viewers render
-- in-memory bytes), so CASHIER no longer holds them. MANAGER keeps both: V85 seeded them as explicit
-- rows, and CapabilityBundles now lists them on MANAGER itself.
--
-- A fresh install seeds the groups from CapabilityBundles in SchemaManager and never needs this; it
-- is for installs seeded by V85 (frozen). Only the SEEDED system group is touched: an
-- operator-created group that happens to hold the same capabilities is not a system group and keeps
-- them. Idempotent: a second run deletes nothing.

DELETE FROM permission_group_capability
WHERE capability IN ('DOCUMENT_READ', 'VOUCHER_REPORT')
  AND group_id IN (SELECT id FROM permission_group WHERE code = 'CASHIER' AND is_system = TRUE);

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM permission_group_capability c
        JOIN permission_group g ON g.id = c.group_id
        WHERE g.code = 'CASHIER' AND c.capability IN ('DOCUMENT_READ', 'VOUCHER_REPORT')
    ) THEN
        RAISE EXCEPTION 'V130: CASHIER still holds DOCUMENT_READ or VOUCHER_REPORT';
    END IF;
    IF (SELECT COUNT(*) FROM permission_group_capability c JOIN permission_group g ON g.id = c.group_id
         WHERE g.code = 'MANAGER' AND c.capability IN ('DOCUMENT_READ', 'VOUCHER_REPORT')) <> 2 THEN
        RAISE EXCEPTION 'V130: MANAGER lost DOCUMENT_READ or VOUCHER_REPORT';
    END IF;
END $$;
