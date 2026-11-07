-- Add training-specific columns to Z-Bon table
-- Training bills are included in Z-Bon but tracked separately from real revenue
ALTER TABLE zbon ADD COLUMN training_revenue DECIMAL(19, 4) NOT NULL DEFAULT 0;
ALTER TABLE zbon ADD COLUMN training_bill_count INT NOT NULL DEFAULT 0;
