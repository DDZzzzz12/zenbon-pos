-- Why items were moved to another table. Nullable: rows written before this migration have no reason.
ALTER TABLE item_transfer ADD COLUMN reason VARCHAR(500);
