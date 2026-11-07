-- Create location_types table
CREATE TABLE IF NOT EXISTS location_types (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    english_name VARCHAR(100) NOT NULL,
    chinese_name VARCHAR(100) NOT NULL,
    is_virtual BOOLEAN NOT NULL DEFAULT false,
    is_takeaway BOOLEAN NOT NULL DEFAULT false,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Seed 5 default location types with fixed IDs
INSERT INTO location_types (id, name, english_name, chinese_name, is_virtual, is_takeaway, sort_order) VALUES
    (1, 'Innenbereich', 'Indoor', '室内', false, false, 1),
    (2, 'Außenbereich', 'Outdoor', '户外', false, false, 2),
    (3, 'Abholung', 'Takeaway', '外卖', false, true, 3),
    (4, 'Gutschein', 'Voucher', '代金券', true, false, 4),
    (5, 'Schnellverkauf', 'Quick Sale', '快速销售', true, false, 5);

-- Reset sequence to start after seeded IDs
SELECT setval('location_types_id_seq', 5);

-- Add location_type_id FK column to r_table
ALTER TABLE r_table ADD COLUMN location_type_id BIGINT;

-- Populate from existing enum string column
UPDATE r_table SET location_type_id = CASE location_type
    WHEN 'INDOOR' THEN 1
    WHEN 'OUTDOOR' THEN 2
    WHEN 'TAKEAWAY' THEN 3
    WHEN 'VOUCHER' THEN 4
    WHEN 'QUICK_SALE' THEN 5
    ELSE 1
END;

-- Make NOT NULL and add FK constraint
ALTER TABLE r_table ALTER COLUMN location_type_id SET NOT NULL;
ALTER TABLE r_table ADD CONSTRAINT fk_r_table_location_type
    FOREIGN KEY (location_type_id) REFERENCES location_types(id);

-- Drop old enum column
ALTER TABLE r_table DROP COLUMN location_type;

-- Same for room_element
ALTER TABLE room_element ADD COLUMN location_type_id BIGINT;

UPDATE room_element SET location_type_id = CASE location_type
    WHEN 'INDOOR' THEN 1
    WHEN 'OUTDOOR' THEN 2
    WHEN 'TAKEAWAY' THEN 3
    WHEN 'VOUCHER' THEN 4
    WHEN 'QUICK_SALE' THEN 5
    ELSE 1
END;

ALTER TABLE room_element ALTER COLUMN location_type_id SET NOT NULL;
ALTER TABLE room_element ADD CONSTRAINT fk_room_element_location_type
    FOREIGN KEY (location_type_id) REFERENCES location_types(id);

ALTER TABLE room_element DROP COLUMN location_type;
