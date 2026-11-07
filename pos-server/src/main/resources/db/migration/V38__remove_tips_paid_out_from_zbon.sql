-- Remove tip payout columns — tips were never part of VAT revenue,
-- so tracking payout state at Z-Bon level was incorrect.
ALTER TABLE zbon DROP COLUMN IF EXISTS tips_paid_out;

ALTER TABLE employee_zbon_summary DROP COLUMN IF EXISTS is_paid_out;
ALTER TABLE employee_zbon_summary DROP COLUMN IF EXISTS paid_out_at;
