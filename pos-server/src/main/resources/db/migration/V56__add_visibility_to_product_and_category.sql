-- Add visibility scope to product and category tables
ALTER TABLE product ADD COLUMN IF NOT EXISTS visibility VARCHAR(50) NOT NULL DEFAULT 'EVERYWHERE';
ALTER TABLE product_category ADD COLUMN IF NOT EXISTS visibility VARCHAR(50) NOT NULL DEFAULT 'EVERYWHERE';
