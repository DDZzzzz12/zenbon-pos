-- Add shop_id FK to opening_hours
ALTER TABLE opening_hours ADD COLUMN shop_id BIGINT REFERENCES shop_info(shop_id);
UPDATE opening_hours SET shop_id = (SELECT shop_id FROM shop_info LIMIT 1);
ALTER TABLE opening_hours ALTER COLUMN shop_id SET NOT NULL;

-- Drop unique constraint to allow multiple slots per day (e.g., lunch + dinner)
ALTER TABLE opening_hours DROP CONSTRAINT IF EXISTS uq_opening_hours_day;

-- Add shop_id FK to opening_hours_override
ALTER TABLE opening_hours_override ADD COLUMN shop_id BIGINT REFERENCES shop_info(shop_id);
UPDATE opening_hours_override SET shop_id = (SELECT shop_id FROM shop_info LIMIT 1);
ALTER TABLE opening_hours_override ALTER COLUMN shop_id SET NOT NULL;
